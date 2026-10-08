package cl.duoc.pedidos360.bff.messaging;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;

/**
 * Construye el contexto de actor firmado a partir del token ya validado por Entra.
 *
 * <p>El tenant ({@code tid}) y la identidad ({@code oid}) salen de claims que la cadena HTTP ya
 * verifico; no se aceptan desde el cuerpo de la peticion. Los roles y scopes se toman de las
 * autoridades calculadas por el recurso OAuth2, no de texto aportado por el cliente.
 *
 * <p>El destino se elige por consulta: cada sobre se firma para la cola funcional concreta, de modo
 * que un mensaje no pueda reutilizarse en otro dominio.
 *
 * <p>Nunca se transporta el JWT original: solo el sobre firmado con tenant, identidad, roles,
 * scopes, emision, expiracion, destino y clave.
 */
public final class BffActorContextFactory {

    private final ActorContextSigner firmante;
    private final BffActorProperties properties;

    public BffActorContextFactory(ActorContextSigner firmante, BffActorProperties properties) {
        this.firmante = firmante;
        this.properties = properties;
    }

    public Set<String> destinosPermitidos() {
        return properties.destinos();
    }

    /** Emite el sobre firmado del actor autenticado para la cola destino indicada. */
    public String emitir(JwtAuthenticationToken token, String colaDestino, Instant emision, Instant plazoRequest) {
        if (token == null || !token.isAuthenticated())
            throw new IllegalStateException("se requiere un token autenticado para emitir contexto de actor.");
        if (!properties.destinos().contains(colaDestino))
            throw new IllegalArgumentException("destino no habilitado para el BFF");
        var jwt = token.getToken();
        UUID tenant = uuid(jwt.getClaimAsString("tid"));
        UUID sujeto = uuid(jwt.getClaimAsString("oid"));
        Set<String> roles = new LinkedHashSet<>();
        Set<String> scopes = new LinkedHashSet<>();
        for (var autoridad : token.getAuthorities()) {
            String value = autoridad.getAuthority();
            if (value.startsWith("ROLE_")) roles.add(value.substring("ROLE_".length()));
            else if (value.startsWith("SCOPE_")) scopes.add(value.substring("SCOPE_".length()));
        }
        Instant expira = emision.plus(properties.ttl());
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(emision))
            throw new org.springframework.security.access.AccessDeniedException("JWT vigente requerido");
        if (expira.isAfter(plazoRequest)) expira = plazoRequest;
        if (expira.isAfter(jwt.getExpiresAt())) expira = jwt.getExpiresAt();
        expira = cl.duoc.pedidos360.messaging.identity.IdentityProofCodec.millis(expira);
        if (!expira.isAfter(emision)) throw new cl.duoc.pedidos360.messaging.relay.QueryTimeoutException("vigencia del actor agotada");
        ActorContext contexto = new ActorContext(tenant, sujeto, roles, scopes, emision, expira, colaDestino,
                claveDeFirma());
        return firmante.emitir(contexto, plazoRequest);
    }

    /** Identificador de la clave vigente, expuesto para diagnostico sin revelar material. */
    public UUID claveDeFirma() {
        return firmante.claveVigente().orElseThrow(() -> new IllegalStateException(
                "no hay clave de firma del actor configurada; el BFF no puede emitir contexto verificable."));
    }

    private static UUID uuid(String valor) {
        if (valor == null) throw new IllegalStateException("el token no trae el claim requerido.");
        try {
            return UUID.fromString(valor);
        } catch (RuntimeException invalido) {
            throw new IllegalStateException("el claim del token no es un UUID valido.", invalido);
        }
    }
}
