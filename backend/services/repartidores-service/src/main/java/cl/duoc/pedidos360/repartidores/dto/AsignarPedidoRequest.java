package cl.duoc.pedidos360.repartidores.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AsignarPedidoRequest(
        @NotNull(message = "El pedido es obligatorio.")
        @Positive(message = "El identificador del pedido debe ser positivo.") Long pedidoId,
        @Size(max = 500, message = "La nota admite hasta 500 caracteres.") String nota) {

    public AsignarPedidoRequest {
        nota = nota == null || nota.isBlank() ? null : nota.strip();
    }
}
