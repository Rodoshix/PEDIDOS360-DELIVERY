package cl.duoc.pedidos360.carrito.dto;

import java.time.Instant;
import java.util.List;

public record CarritoResponse(Long id, Long restauranteId, String moneda, long total,
        Long version, Instant actualizadoEn, List<LineaResponse> items) {
    public record LineaResponse(Long productoId, String nombre, long precioUnitario, int cantidad, long subtotal) { }

    public static CarritoResponse vacio() {
        return new CarritoResponse(null, null, "CLP", 0L, null, null, List.of());
    }
}
