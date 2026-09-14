package cl.duoc.pedidos360.pedidos.dto;

import java.time.Instant;
import java.util.List;

public record PedidoResponse(
        Long pedidoId,
        Long usuarioId,
        Long restauranteId,
        String direccionEntrega,
        String estado,
        Long total,
        String moneda,
        Instant fechaCreacion,
        List<LineaPedidoResponse> lineas) {
}
