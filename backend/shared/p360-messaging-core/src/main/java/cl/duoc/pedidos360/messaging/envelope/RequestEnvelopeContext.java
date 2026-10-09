package cl.duoc.pedidos360.messaging.envelope;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lectura y escritura estricta del envelope.
 *
 * <p>Rechaza campos extra o faltantes, campos duplicados, contenido posterior al JSON, UUID no
 * canonico e instantes que no sean UTC. La misma exigencia se aplica al emitir y al consumir.
 */
public final class RequestEnvelopeContext {

    private static final java.util.Set<String> CAMPOS = java.util.Set.of("messageId", "type", "version",
            "occurredAt", "expiresAt", "actor", "operacion", "payload");

    private final JsonMapper json;

    public RequestEnvelopeContext() {
        this(JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build());
    }

    public RequestEnvelopeContext(JsonMapper json) {
        this.json = json;
    }

    /** Serializa el envelope en el orden estable del contrato. */
    public byte[] escribir(RequestEnvelope envelope) {
        return json.writeValueAsBytes(aMapa(envelope));
    }

    /** Devuelve el mismo envelope con un plazo distinto, sin renovarlo: solo se documenta. */
    public JsonNode leerArbol(byte[] cuerpo) {
        return json.readTree(cuerpo);
    }

    /** Parsea con validacion estricta de esquema. Lanza {@link EnvelopeException} si no corresponde. */
    public RequestEnvelope leer(byte[] cuerpo, int maxBytes) {
        if (cuerpo == null || cuerpo.length == 0) throw new EnvelopeException(EnvelopeException.Reason.VACIO,
                "cuerpo del mensaje vacio");
        if (cuerpo.length > maxBytes) throw new EnvelopeException(EnvelopeException.Reason.EXCEDE_TAMANO,
                "cuerpo del mensaje excede el maximo configurado");
        JsonNode raiz;
        try {
            raiz = json.readTree(cuerpo);
        } catch (RuntimeException invalid) {
            throw new EnvelopeException(EnvelopeException.Reason.JSON_INVALIDO, "cuerpo no es JSON valido", invalid);
        }
        if (raiz == null || !raiz.isObject()) throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                "el envelope debe ser un objeto JSON");
        if (!JsonFields.exactos(raiz, CAMPOS)) throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                "campos del envelope no corresponden al contrato");
        if (!raiz.path("messageId").isString() || !raiz.path("type").isString()
                || !raiz.path("version").isIntegralNumber() || !raiz.path("occurredAt").isString()
                || !raiz.path("expiresAt").isString() || !raiz.path("actor").isString()
                || !raiz.path("operacion").isString() || !raiz.path("payload").isObject())
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                    "tipos del envelope no corresponden al contrato");
        UUID messageId = uuidCanonico(raiz.path("messageId").stringValue());
        Instant occurredAt = instanteUtc(raiz.path("occurredAt").stringValue());
        Instant expiresAt = instanteUtc(raiz.path("expiresAt").stringValue());
        int version;
        try {
            version = raiz.path("version").intValue();
        } catch (RuntimeException invalid) {
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO, "version fuera de rango", invalid);
        }
        try {
            return new RequestEnvelope(messageId, raiz.path("type").stringValue(), version, occurredAt, expiresAt,
                    raiz.path("actor").stringValue(), raiz.path("operacion").stringValue(), raiz.path("payload"));
        } catch (IllegalArgumentException invalid) {
            // Only construction invariants are protocol failures, not arbitrary business exceptions.
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                    "envelope no corresponde al contrato", invalid);
        }
    }

    /**
     * Serializa una respuesta correlacionada.
     *
     * <p>Los ocho campos del contrato se escriben siempre, incluidos {@code payload} y {@code error}
     * cuando no aplican: se emiten como {@code null} en vez de omitirse. Esa exigencia evita que un
     * mensaje ambiguo se confunda con uno completo y mantiene la validacion estricta del receptor.
     */
    public byte[] escribirRespuesta(QueryResponse respuesta) {
        var mapa = new LinkedHashMap<String, Object>();
        mapa.put("operacion", respuesta.operacion());
        mapa.put("correlationId", respuesta.correlationId());
        mapa.put("messageId", respuesta.messageId().toString());
        mapa.put("success", respuesta.success());
        mapa.put("status", respuesta.status());
        mapa.put("payload", respuesta.payload());
        mapa.put("error", respuesta.error());
        mapa.put("timestamp", respuesta.timestamp().toString());
        return json.writeValueAsBytes(mapa);
    }

    private static Map<String, Object> aMapa(RequestEnvelope envelope) {
        var mapa = new LinkedHashMap<String, Object>();
        mapa.put("messageId", envelope.messageId().toString());
        mapa.put("type", envelope.type());
        mapa.put("version", envelope.version());
        mapa.put("occurredAt", envelope.occurredAt().toString());
        mapa.put("expiresAt", envelope.expiresAt().toString());
        mapa.put("actor", envelope.actor());
        mapa.put("operacion", envelope.operacion());
        mapa.put("payload", envelope.payload());
        return mapa;
    }

    private static UUID uuidCanonico(String valor) {
        try {
            UUID uuid = UUID.fromString(valor);
            if (!uuid.toString().equals(valor)) throw new IllegalArgumentException("no canonico");
            return uuid;
        } catch (RuntimeException invalid) {
            throw new EnvelopeException(EnvelopeException.Reason.IDENTIDAD_INVALIDA,
                    "messageId no es un UUID canonico", invalid);
        }
    }

    private static Instant instanteUtc(String valor) {
        if (valor == null || !valor.endsWith("Z")) throw new EnvelopeException(
                EnvelopeException.Reason.TIEMPO_INVALIDO, "se requiere un instante UTC terminado en Z");
        try {
            return Instant.parse(valor);
        } catch (RuntimeException invalid) {
            throw new EnvelopeException(EnvelopeException.Reason.TIEMPO_INVALIDO, "instante no valido", invalid);
        }
    }
}
