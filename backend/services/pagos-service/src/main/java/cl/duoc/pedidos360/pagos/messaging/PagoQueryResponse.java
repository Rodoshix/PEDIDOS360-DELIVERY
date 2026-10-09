package cl.duoc.pedidos360.pagos.messaging;

import cl.duoc.pedidos360.pagos.dto.PagoResponse;
import java.time.Instant;

/** Transport-specific whitelist; changes to HTTP DTOs cannot silently expose additional data. */
public record PagoQueryResponse(Long pagoId, Long pedidoId, Long usuarioId, Long monto,
        String moneda, String metodo, String estado, Instant fecha) {
    public static PagoQueryResponse from(PagoResponse p) {
        return new PagoQueryResponse(p.pagoId(), p.pedidoId(), p.usuarioId(), p.monto(),
                p.moneda(), p.metodo(), p.estado(), p.fecha());
    }
}
