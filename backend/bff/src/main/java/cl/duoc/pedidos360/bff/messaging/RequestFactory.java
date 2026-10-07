package cl.duoc.pedidos360.bff.messaging;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.QueryTopology;
import cl.duoc.pedidos360.messaging.Domain;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import tools.jackson.databind.JsonNode;

/**
 * Construye el envelope de una consulta y la correlacion de su espera.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>fijar el plazo absoluto ({@code expiresAt}) del presupuesto configurado;</li>
 *   <li>exigir que la vigencia del actor sea menor que ese plazo, para que un retry corto siempre
 *       encuentre contexto vigente;</li>
 *   <li>dirigir el sobre firmado a la cola funcional del dominio consultado.</li>
 * </ul>
 *
 * <p>Nunca hay un plazo por defecto sin comprobacion: una configuracion incoherente falla antes de
 * publicar, no despues.
 */
public final class RequestFactory {

    private final MessagingProperties properties;
    private final BffActorContextFactory actores;

    public RequestFactory(MessagingProperties properties, BffActorContextFactory actores) {
        this.properties = properties;
        this.actores = actores;
    }

    /** Prepara el plan de una consulta para el dominio indicado. */
    public RequestPlan planificar(Domain domain, String operacion, JsonNode payload, JwtAuthenticationToken token,
            Instant ahora) {
        if (domain == null) throw new IllegalArgumentException("dominio requerido");
        if (operacion == null || operacion.isBlank()) throw new IllegalArgumentException("operacion requerida");
        if (payload == null || !payload.isObject()) throw new IllegalArgumentException("payload debe ser un objeto JSON");
        if (ahora == null) throw new IllegalArgumentException("instante de emision requerido");
        if (properties.actorTtl().compareTo(properties.deadline()) >= 0)
            throw new IllegalStateException("pedidos360.messaging.actor-ttl debe ser menor que el deadline: "
                    + "un sobre que sobrevive al plazo impide reintentar con autorizacion vigente.");
        String colaDestino = QueryTopology.of(properties, domain).queue();
        Instant plazo = ahora.plus(properties.deadline());
        String sobre = actores.emitir(token, colaDestino, ahora, plazo);
        UUID messageId = UUID.randomUUID();
        return new RequestPlan(RequestEnvelope.crear(messageId, operacion, payload, sobre, ahora, plazo),
                UUID.randomUUID().toString());
    }

    public MessagingProperties properties() {
        return properties;
    }
}
