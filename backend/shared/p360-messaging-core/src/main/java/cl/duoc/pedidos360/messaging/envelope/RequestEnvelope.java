package cl.duoc.pedidos360.messaging.envelope;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Envelope comun de consulta (request/reply).
 *
 * <p>Los metadatos del mensaje viajan en el cuerpo y en las propiedades AMQP:
 * {@code message_id} coincide con {@code messageId}; {@code correlation_id} y {@code reply_to}
 * son propiedades AMQP y no se duplican en el cuerpo.
 *
 * <p>Reglas:
 * <ul>
 *   <li>{@code messageId} es UUID canonico estable: un retry conserva el mismo identificador.</li>
 *   <li>{@code expiresAt} es un plazo absoluto. Un retry no lo renueva. Si ya vencio, el mensaje no
 *       se ejecuta ni se reintenta.</li>
 *   <li>{@code actor} es un sobre firmado verificable, no identidad ni roles en texto plano.</li>
 *   <li>{@code payload} contiene solo los parametros minimos de la operacion. Nunca JWT, secretos
 *       ni perfiles completos.</li>
 * </ul>
 */
public record RequestEnvelope(UUID messageId, String type, int version, Instant occurredAt, Instant expiresAt,
        String actor, String operacion, JsonNode payload) {

    /** Tipo unico de las consultas request/reply aprobadas. */
    public static final String TYPE = "ConsultaPedidos360";

    /** Version inicial del contrato. */
    public static final int VERSION = 1;

    public RequestEnvelope {
        if (messageId == null) throw new IllegalArgumentException("messageId requerido");
        if (!TYPE.equals(type)) throw new IllegalArgumentException("type no soportado");
        if (version != VERSION) throw new IllegalArgumentException("version no soportada");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt requerido");
        if (expiresAt == null || !expiresAt.isAfter(occurredAt))
            throw new IllegalArgumentException("expiresAt debe ser posterior a occurredAt");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor requerido");
        if (operacion == null || operacion.isBlank()) throw new IllegalArgumentException("operacion requerida");
        if (payload == null || !payload.isObject())
            throw new IllegalArgumentException("payload debe ser un objeto JSON");
    }

    /** Crea el envelope de una operacion con su plazo absoluto. */
    public static RequestEnvelope crear(UUID messageId, String operacion, JsonNode payload, String actor,
            Instant occurredAt, Instant expiresAt) {
        return new RequestEnvelope(messageId, TYPE, VERSION, occurredAt, expiresAt, actor, operacion, payload);
    }

    /** Resultado de la validacion de plazo: no se procesa ni se reintenta un mensaje vencido. */
    public boolean vencido(Instant ahora) {
        return !expiresAt.isAfter(ahora);
    }
}
