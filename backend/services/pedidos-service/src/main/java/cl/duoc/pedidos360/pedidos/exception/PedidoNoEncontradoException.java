package cl.duoc.pedidos360.pedidos.exception;

/** Recurso de pedido inexistente o, según la política de acceso, no visible para el solicitante. */
public class PedidoNoEncontradoException extends RuntimeException {
    public PedidoNoEncontradoException(String message) {
        super(message);
    }
}
