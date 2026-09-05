package cl.duoc.pedidos360.pedidos.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LineaPedidoRequest(
        @NotNull Long productoId,
        @Positive int cantidad) {
}
