package cl.duoc.pedidos360.messaging.identity;

/** Motivo estable sin prueba, claims ni causas que puedan revelar material sensible. */
public final class IdentityProofException extends org.springframework.security.access.AccessDeniedException {
    public enum Reason { FORMATO, FIRMA, CLAVE, VINCULO, TIEMPO_INCOHERENTE, VENCIDA }
    private final Reason reason;
    public IdentityProofException(Reason reason) {
        super("prueba de identidad rechazada: " + reason);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
