package cl.duoc.pedidos360.pedidos.messaging;
public class RetryPublicationException extends Exception {
 public enum Reason { BROKER_NACK, RETURNED, TIMEOUT_OR_CONNECTION }
 private final Reason reason;
 public RetryPublicationException(Reason reason) { super(reason.name()); this.reason=reason; }
 public RetryPublicationException(Reason reason,Throwable cause) { super(reason.name(),cause); this.reason=reason; }
 public Reason reason() { return reason; }
}
