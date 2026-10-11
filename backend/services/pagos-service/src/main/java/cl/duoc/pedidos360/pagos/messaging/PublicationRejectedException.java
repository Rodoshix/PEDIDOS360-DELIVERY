package cl.duoc.pedidos360.pagos.messaging;
/** Safe diagnostic codes; never persist remote error text or credentials. */
public class PublicationRejectedException extends RuntimeException {
    public enum Reason { BROKER_NACK, UNROUTABLE }
    private final Reason reason;
    public PublicationRejectedException(Reason reason) { super(reason.name()); this.reason=reason; }
    public Reason reason() { return reason; }
}
