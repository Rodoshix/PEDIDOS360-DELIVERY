package cl.duoc.pedidos360.pagos.client;

/** Vista mínima del pedido necesaria para registrar el pago. */
public record PedidoResumen(
        Long pedidoId,
        Long usuarioId,
        String estado,
        Long total,
        String moneda) {
}
