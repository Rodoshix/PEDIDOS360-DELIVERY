package cl.duoc.pedidos360.messaging.actor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emite y verifica el contexto de actor con HMAC-SHA256 sobre una representacion canonica.
 *
 * <p>El algoritmo es HMAC simetrico porque los seis servicios ya comparten un broker dentro del
 * vhost de negocio y la operacion puede provisionar el mismo secreto a emisor y verificadores.
 * El material se inyecta desde fuera de Git mediante {@link SigningKeyProvider} y su rotacion esta
 * soportada por {@code keyId}: un sobre con clave retirada se rechaza.
 *
 * <p>Si no hay clave disponible, {@link #emitir} falla cerrado en vez de emitir un sobre sin
 * autenticidad. {@link #verificar} exige firma valida, emisor esperado, destino permitido y plazo
 * vigente, ademas de coherencia entre el plazo del actor y el del request.
 */
public final class ActorContextSigner {

    private static final String HMAC = "HmacSHA256";
    private static final String VERSION = "p360act1";
    private static final Set<String> CAMPOS = Set.of("v", "tenantId", "sujetoId", "roles", "scopes",
            "emitidoEn", "expiraEn", "audiencia", "keyId");

    /** Tolerancia de reloj admitida al comparar emision y expiracion. Configurable por constructor. */
    private final Duration tolerancia;
    private final SigningKeyProvider claves;
    private final Clock reloj;
    private final JsonMapper json;

    public ActorContextSigner(SigningKeyProvider claves) {
        this(claves, Clock.systemUTC(), Duration.ofSeconds(5));
    }

    public ActorContextSigner(SigningKeyProvider claves, Clock reloj, Duration tolerancia) {
        if (claves == null) throw new IllegalArgumentException("proveedor de claves requerido");
        if (reloj == null) throw new IllegalArgumentException("reloj requerido");
        if (tolerancia == null || tolerancia.isNegative())
            throw new IllegalArgumentException("tolerancia no puede ser negativa");
        this.claves = claves;
        this.reloj = reloj;
        this.tolerancia = tolerancia;
        this.json = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    /**
     * Firma el contexto para el destino indicado.
     *
     * <p>No se recorta el plazo: si {@code expiraEn} supera el plazo del request, el rechazo ocurre
     * aqui y no en el consumidor.
     */
    public String emitir(ActorContext contexto, Instant plazoRequest) {
        if (contexto == null) throw new ActorContextException(ActorContextException.Reason.SOBRE_AUSENTE,
                "contexto de actor requerido");
        if (plazoRequest == null) throw new ActorContextException(ActorContextException.Reason.PLAZO_INCOHERENTE,
                "plazo del request requerido");
        if (contexto.expiraEn().isAfter(plazoRequest))
            throw new ActorContextException(ActorContextException.Reason.PLAZO_INCOHERENTE,
                    "la vigencia del actor no puede superar el plazo del request");
        SigningKey clave = claves.claveParaFirmar().orElseThrow(() -> new ActorContextException(
                ActorContextException.Reason.CLAVE_DESCONOCIDA, "no hay clave de firma disponible"));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("v", VERSION);
        claims.put("tenantId", contexto.tenantId().toString());
        claims.put("sujetoId", contexto.sujetoId().toString());
        claims.put("roles", contexto.roles().stream().sorted().toList());
        claims.put("scopes", contexto.scopes().stream().sorted().toList());
        claims.put("emitidoEn", contexto.emitidoEn().toString());
        claims.put("expiraEn", contexto.expiraEn().toString());
        claims.put("audiencia", contexto.audiencia());
        claims.put("keyId", clave.keyId().toString());
        byte[] payload = json.writeValueAsBytes(claims);
        return VERSION + "." + b64(payload) + "." + b64(hmac(clave.material(), VERSION + "." + b64(payload)));
    }

    /** Verifica el sobre y devuelve el contexto solo si es autentico, vigente y dirigido a este consumidor. */
    public ActorContext verificar(String sobre, String emisorEsperado, List<String> destinosPermitidos,
            Instant plazoRequest) {
        if (sobre == null || sobre.isBlank()) throw new ActorContextException(
                ActorContextException.Reason.SOBRE_AUSENTE, "sobre de actor ausente");
        if (destinosPermitidos == null || destinosPermitidos.isEmpty()) throw new ActorContextException(
                ActorContextException.Reason.DESTINO_INVALIDO, "el consumidor no declaro destinos permitidos");
        String[] partes = sobre.split("\\.", -1);
        if (partes.length != 3) throw new ActorContextException(ActorContextException.Reason.FORMATO_INVALIDO,
                "el sobre no tiene tres segmentos");
        if (!VERSION.equals(partes[0])) throw new ActorContextException(ActorContextException.Reason.FORMATO_INVALIDO,
                "version de sobre no soportada");
        Map<String, Object> claims = claims(partes[1]);
        if (!CAMPOS.equals(claims.keySet())) throw new ActorContextException(
                ActorContextException.Reason.FORMATO_INVALIDO, "campos del sobre no corresponden al contrato");
        if (!VERSION.equals(texto(claims.get("v")))) throw new ActorContextException(
                ActorContextException.Reason.FORMATO_INVALIDO, "version de claims no soportada");
        UUID keyId = uuid(texto(claims.get("keyId")));
        SigningKey clave = claves.clavePorId(keyId).orElseThrow(() -> new ActorContextException(
                ActorContextException.Reason.CLAVE_DESCONOCIDA, "clave de firma desconocida o retirada"));
        byte[] esperado = hmac(clave.material(), partes[0] + "." + partes[1]);
        byte[] recibido = b64(partes[2]);
        if (!MessageDigest.isEqual(esperado, recibido)) throw new ActorContextException(
                ActorContextException.Reason.FIRMA_INVALIDA, "firma del sobre no coincide");
        if (emisorEsperado == null || !emisorEsperado.equals(texto(claims.get("tenantId"))))
            throw new ActorContextException(ActorContextException.Reason.EMISOR_INVALIDO,
                    "emisor del sobre no es el esperado");
        String audiencia = texto(claims.get("audiencia"));
        if (!destinosPermitidos.contains(audiencia)) throw new ActorContextException(
                ActorContextException.Reason.DESTINO_INVALIDO, "el sobre no esta dirigido a este consumidor");
        Instant emitidoEn = instante(texto(claims.get("emitidoEn")));
        Instant expiraEn = instante(texto(claims.get("expiraEn")));
        Instant ahora = reloj.instant();
        if (emitidoEn.isAfter(ahora.plus(tolerancia))) throw new ActorContextException(
                ActorContextException.Reason.PLAZO_INCOHERENTE, "emision en el futuro fuera de tolerancia");
        if (!expiraEn.isAfter(emitidoEn)) throw new ActorContextException(
                ActorContextException.Reason.PLAZO_INCOHERENTE, "vigencia del actor no es positiva");
        // El vencimiento propio del actor se comprueba antes de contrastarlo con el plazo del request:
        // si el sobre expiro, la causa es la vigencia del actor, no una incoherencia de plazos.
        if (!expiraEn.isAfter(ahora)) throw new ActorContextException(
                ActorContextException.Reason.PLAZO_VENCIDO, "vigencia del actor expirada");
        if (plazoRequest == null || expiraEn.isAfter(plazoRequest))
            throw new ActorContextException(ActorContextException.Reason.PLAZO_INCOHERENTE,
                    "la vigencia del actor supera el plazo del request");
        return new ActorContext(uuid(texto(claims.get("tenantId"))), uuid(texto(claims.get("sujetoId"))),
                textos(claims.get("roles")), textos(claims.get("scopes")), emitidoEn, expiraEn, audiencia, keyId);
    }

    /** Verificacion de una clave conocida, sin emitir: expuesta para diagnostico de configuracion. */
    public Optional<UUID> claveVigente() {
        return claves.claveParaFirmar().map(SigningKey::keyId);
    }

    private Map<String, Object> claims(String payloadB64) {
        byte[] raw;
        try {
            raw = b64(payloadB64);
        } catch (RuntimeException notBase64) {
            throw new ActorContextException(ActorContextException.Reason.FORMATO_INVALIDO,
                    "payload del sobre no es base64url", notBase64);
        }
        if (raw.length == 0 || raw.length > 8192) throw new ActorContextException(
                ActorContextException.Reason.FORMATO_INVALIDO, "payload del sobre fuera de rango");
        Map<String, Object> claims;
        try {
            claims = json.readValue(raw, new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (RuntimeException invalid) {
            throw new ActorContextException(ActorContextException.Reason.FORMATO_INVALIDO,
                    "payload del sobre no es JSON valido", invalid);
        }
        if (claims == null) throw new ActorContextException(ActorContextException.Reason.FORMATO_INVALIDO,
                "payload del sobre vacio");
        return claims;
    }

    private static byte[] hmac(byte[] material, String mensaje) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(material, HMAC));
            return mac.doFinal(mensaje.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException imposible) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", imposible);
        }
    }

    private static String b64(byte[] valor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(valor);
    }

    private static byte[] b64(String valor) {
        return Base64.getUrlDecoder().decode(valor);
    }

    private static String texto(Object valor) {
        if (valor instanceof String texto) return texto;
        throw new ActorContextException(ActorContextException.Reason.FORMATO_INVALIDO, "campo de texto invalido");
    }

    private static UUID uuid(String valor) {
        try {
            UUID uuid = UUID.fromString(valor);
            if (!uuid.toString().equalsIgnoreCase(valor)) throw new IllegalArgumentException("no canonico");
            return uuid;
        } catch (RuntimeException invalid) {
            throw new ActorContextException(ActorContextException.Reason.FORMATO_INVALIDO, "identificador invalido",
                    invalid);
        }
    }

    private static Instant instante(String valor) {
        try {
            return Instant.parse(valor);
        } catch (RuntimeException invalid) {
            throw new ActorContextException(ActorContextException.Reason.FORMATO_INVALIDO, "instante invalido",
                    invalid);
        }
    }

    private static Set<String> textos(Object valor) {
        if (!(valor instanceof List<?> lista)) throw new ActorContextException(
                ActorContextException.Reason.FORMATO_INVALIDO, "lista de autorizacion invalida");
        var result = new java.util.LinkedHashSet<String>();
        for (Object elemento : lista) {
            if (!(elemento instanceof String texto) || texto.isBlank() || texto.length() > 64
                    || !texto.matches("[A-Za-z0-9_.:-]+")) throw new ActorContextException(
                    ActorContextException.Reason.FORMATO_INVALIDO, "elemento de autorizacion invalido");
            result.add(texto);
        }
        return result;
    }
}
