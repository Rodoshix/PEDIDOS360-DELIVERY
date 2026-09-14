package cl.duoc.pedidos360.pagos.dto;

import cl.duoc.pedidos360.pagos.entity.MetodoPago;
import jakarta.validation.constraints.NotNull;

public record CrearPagoRequest(
        @NotNull Long pedidoId,
        @NotNull MetodoPago metodo) {
}
