package cl.duoc.pedidos360.pagos.dto;

import java.time.Instant;

public record PagoResponse(
        Long pagoId,
        Long pedidoId,
        Long usuarioId,
        Long monto,
        String moneda,
        String metodo,
        String estado,
        Instant fecha) {
}
