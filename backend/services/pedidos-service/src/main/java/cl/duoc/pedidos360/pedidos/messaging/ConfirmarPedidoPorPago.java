package cl.duoc.pedidos360.pedidos.messaging;
import java.time.Instant;
import java.util.UUID;
/** Contract V1. No payment or identity data leaves the producer. */
public record ConfirmarPedidoPorPago(UUID messageId, String type, int version,
        Instant occurredAt, long pedidoId, long pagoId) {
    public static ConfirmarPedidoPorPago crear(long pedidoId, long pagoId) {
        return new ConfirmarPedidoPorPago(UUID.randomUUID(), "ConfirmarPedidoPorPago", 1,
                Instant.now(), pedidoId, pagoId);
    }
}
