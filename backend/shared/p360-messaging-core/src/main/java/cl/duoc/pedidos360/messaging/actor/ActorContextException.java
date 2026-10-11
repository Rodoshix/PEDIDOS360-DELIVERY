package cl.duoc.pedidos360.messaging.actor;

/**
 * Fallo de autenticidad, vigencia o destino de un contexto de actor.
 *
 * <p>Cualquier causa es definitiva: el mensaje no se procesa, no se reintenta y se diagnostica.
 * Nunca incluye el token ni claims completos para no filtrar material sensible.
 */
public class ActorContextException extends RuntimeException {

    public enum Reason {
        SOBRE_AUSENTE,
        FORMATO_INVALIDO,
        CLAVE_DESCONOCIDA,
        FIRMA_INVALIDA,
        EMISOR_INVALIDO,
        DESTINO_INVALIDO,
        PLAZO_VENCIDO,
        PLAZO_INCOHERENTE
    }

    private final Reason reason;

    public ActorContextException(Reason reason, String detalle) {
        super(detalle);
        this.reason = reason;
    }

    public ActorContextException(Reason reason, String detalle, Throwable causa) {
        super(detalle, causa);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
