package cl.duoc.pedidos360.pagos.exception;

import org.springframework.http.HttpStatus;

public class PagoException extends RuntimeException {

    private final HttpStatus status;

    public PagoException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
