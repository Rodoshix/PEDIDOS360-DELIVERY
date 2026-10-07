package cl.duoc.pedidos360.messaging.envelope;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Respuesta correlacionada de una consulta.
 *
 * <p>Formato aprobado:
 *
 * <pre>
 * {
 *   "operacion": "usuario.consultar-actual.v1",
 *   "correlationId": "&lt;correlacion del request&gt;",
 *   "messageId": "&lt;messageId del request&gt;",
 *   "success": true,
 *   "status": 200,
 *   "payload": { ... },
 *   "error": { "code": "...", "title": "...", "detail": "...", "status": 404 },
 *   "timestamp": "2026-10-07T01:30:00Z"
 * }
 * </pre>
 *
 * <p>{@code correlationId} tambien viaja como propiedad AMQP para que el BFF pueda resolver la
 * correlacion sin abrir el cuerpo. {@code messageId} referencia el request atendido.
 *
 * <p>{@code status} es el codigo equivalente al contrato HTTP existente: un error de negocio
 * esperado (403, 404, 409) viaja como respuesta con ACK, nunca como retry.
 *
 * <p>{@code timestamp} es UTC. Nunca incluye JWT, tokens ni perfiles completos.
 */
public record QueryResponse(String operacion, String correlationId, UUID messageId, boolean success, int status,
        JsonNode payload, ErrorDetail error, Instant timestamp) {

    /** Error equivalente al problem detail HTTP. */
    public record ErrorDetail(String code, String title, String detail, int status) {
        public ErrorDetail {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code requerido");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title requerido");
            if (status < 400 || status > 599) throw new IllegalArgumentException("status de error fuera de rango");
        }
    }

    public QueryResponse {
        if (operacion == null || operacion.isBlank()) throw new IllegalArgumentException("operacion requerida");
        if (correlationId == null || correlationId.isBlank())
            throw new IllegalArgumentException("correlationId requerido");
        if (messageId == null) throw new IllegalArgumentException("messageId requerido");
        if (status < 100 || status > 599) throw new IllegalArgumentException("status fuera de rango");
        if (timestamp == null) throw new IllegalArgumentException("timestamp requerido");
        if (success) {
            if (status < 200 || status > 299) throw new IllegalArgumentException("exito exige status 2xx");
            if (error != null) throw new IllegalArgumentException("exito no admite error");
        } else if (status < 400) {
            throw new IllegalArgumentException("fallo exige status de error");
        }
    }

    /** Respuesta exitosa con el cuerpo equivalente al HTTP 200 del servicio. */
    public static QueryResponse exito(RequestEnvelope request, String correlationId, JsonNode payload, Instant ahora) {
        return new QueryResponse(request.operacion(), correlationId, request.messageId(), true, 200, payload, null,
                ahora);
    }

    /** Respuesta de error de negocio esperado; conserva la semantica del contrato HTTP. */
    public static QueryResponse error(RequestEnvelope request, String correlationId, ErrorDetail error,
            Instant ahora) {
        return new QueryResponse(request.operacion(), correlationId, request.messageId(), false, error.status(), null,
                error, ahora);
    }
}
