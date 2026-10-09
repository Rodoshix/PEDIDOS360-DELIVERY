package cl.duoc.pedidos360.rabbitadmin;

import org.springframework.http.HttpStatus;

public final class AdminFailure extends RuntimeException {
    private final HttpStatus status;
    public AdminFailure(HttpStatus status, String message) { super(message); this.status=status; }
    public HttpStatus status() { return status; }
    public static AdminFailure validation() { return new AdminFailure(HttpStatus.BAD_REQUEST,"Solicitud sandbox inválida."); }
    public static AdminFailure conflict() { return new AdminFailure(HttpStatus.CONFLICT,"Recurso ausente, protegido, ocupado o incompatible."); }
    public static AdminFailure unavailable() { return new AdminFailure(HttpStatus.SERVICE_UNAVAILABLE,"Broker no disponible; resultado no confirmado."); }
}
