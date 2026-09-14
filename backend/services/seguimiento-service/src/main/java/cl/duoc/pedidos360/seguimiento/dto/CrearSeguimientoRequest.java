package cl.duoc.pedidos360.seguimiento.dto;

import cl.duoc.pedidos360.seguimiento.entity.EstadoSeguimiento;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CrearSeguimientoRequest(
        @NotNull(message = "El pedido es obligatorio.")
        @Positive(message = "El identificador del pedido debe ser positivo.") Long pedidoId,
        @NotNull(message = "El estado inicial es obligatorio.") EstadoSeguimiento estadoInicial) {

    public CrearSeguimientoRequest {
        if (pedidoId != null && pedidoId <= 0) {
            pedidoId = null;
        }
    }
}
