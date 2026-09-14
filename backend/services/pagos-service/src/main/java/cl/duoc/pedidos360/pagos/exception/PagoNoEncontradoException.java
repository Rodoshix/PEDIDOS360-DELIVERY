package cl.duoc.pedidos360.pagos.exception;

/** Pago inexistente o no visible para el solicitante. */
public class PagoNoEncontradoException extends RuntimeException {
    public PagoNoEncontradoException(String message) {
        super(message);
    }
}
