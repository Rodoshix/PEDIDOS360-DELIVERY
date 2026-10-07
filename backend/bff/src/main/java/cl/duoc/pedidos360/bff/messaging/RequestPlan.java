package cl.duoc.pedidos360.bff.messaging;

import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;

/**
 * Plan de una consulta: el envelope y la correlacion de esta espera.
 *
 * <p>La correlacion es unica por espera; el {@code messageId} es del mensaje logico y un retry lo
 * conserva.
 */
public record RequestPlan(RequestEnvelope envelope, String correlationId) {}
