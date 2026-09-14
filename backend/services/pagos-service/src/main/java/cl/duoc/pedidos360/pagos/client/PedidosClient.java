package cl.duoc.pedidos360.pagos.client;

/** Confirma un pedido en pedidos-service (transición CREADO → CONFIRMADO). */
public interface PedidosClient {

    PedidoResumen obtener(Long pedidoId);

    void confirmar(Long pedidoId);
}
