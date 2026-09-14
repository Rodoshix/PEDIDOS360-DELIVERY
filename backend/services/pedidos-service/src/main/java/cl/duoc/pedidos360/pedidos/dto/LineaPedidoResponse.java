package cl.duoc.pedidos360.pedidos.dto;

public record LineaPedidoResponse(
        Long lineaId,
        Long productoId,
        int cantidad,
        Long precioUnitario,
        Long subtotal) {
}
