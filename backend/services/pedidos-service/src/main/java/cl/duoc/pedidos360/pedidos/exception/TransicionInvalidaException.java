package cl.duoc.pedidos360.pedidos.exception;

/** Transición de estado no permitida por la máquina de estados. */
public class TransicionInvalidaException extends RuntimeException {
    public TransicionInvalidaException(String message) {
        super(message);
    }
}
