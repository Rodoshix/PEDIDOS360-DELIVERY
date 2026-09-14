package cl.duoc.pedidos360.pedidos.exception;

import org.springframework.http.HttpStatus;

public class PedidoException extends RuntimeException {

    private final HttpStatus status;

    public PedidoException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
