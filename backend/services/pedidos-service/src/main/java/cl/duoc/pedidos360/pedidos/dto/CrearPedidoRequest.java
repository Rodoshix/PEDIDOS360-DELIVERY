package cl.duoc.pedidos360.pedidos.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CrearPedidoRequest(
        @NotNull Long restauranteId,
        @NotBlank @Size(max = 255) String direccionEntrega,
        @NotEmpty List<LineaPedidoRequest> items) {
}
