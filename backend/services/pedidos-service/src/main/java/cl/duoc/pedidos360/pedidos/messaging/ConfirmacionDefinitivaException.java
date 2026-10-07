package cl.duoc.pedidos360.pedidos.messaging;
public class ConfirmacionDefinitivaException extends RuntimeException {
    public enum Reason { INVALID_MESSAGE, PEDIDO_INEXISTENTE, PEDIDO_CANCELADO }
    private final Reason reason;
    public ConfirmacionDefinitivaException(Reason reason, Throwable cause) {
        super(reason.name(),cause); this.reason=reason;
    }
    public Reason reason() { return reason; }
}
