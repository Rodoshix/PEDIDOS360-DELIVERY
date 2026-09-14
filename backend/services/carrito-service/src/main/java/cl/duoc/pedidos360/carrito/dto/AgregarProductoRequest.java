package cl.duoc.pedidos360.carrito.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AgregarProductoRequest(
        @NotNull @Positive Long productoId,
        @NotNull @Min(1) @Max(99) Integer cantidad) { }
