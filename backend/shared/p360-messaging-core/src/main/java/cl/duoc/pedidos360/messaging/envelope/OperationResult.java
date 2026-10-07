package cl.duoc.pedidos360.messaging.envelope;

import tools.jackson.databind.JsonNode;

/**
 * Resultado de una operacion de dominio, independiente del transporte.
 *
 * <p>Permite que la misma operacion responda por HTTP (hoy) y por request/reply (tras el corte de
 * #70) sin duplicar la logica de autorizacion ni la consulta.
 *
 * @param operacion operacion atendida, equivalente a la routing key.
 * @param status codigo equivalente al contrato HTTP de la operacion que responde.
 * @param payload cuerpo JSON del resultado.
 */
public record OperationResult(String operacion, int status, JsonNode payload) {

    public OperationResult {
        if (operacion == null || operacion.isBlank()) throw new IllegalArgumentException("operacion requerida");
        if (status < 200 || status > 299) throw new IllegalArgumentException("el status de exito debe ser 2xx");
        if (payload == null) throw new IllegalArgumentException("payload requerido");
    }

    public static OperationResult ok(String operacion, JsonNode payload) {
        return new OperationResult(operacion, 200, payload);
    }
}
