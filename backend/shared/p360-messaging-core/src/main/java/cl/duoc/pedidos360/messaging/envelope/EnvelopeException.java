package cl.duoc.pedidos360.messaging.envelope;

/**
 * Envelope invalido: esquema, identidad, tiempo o tamano.
 *
 * <p>Es un fallo definitivo. El mensaje no se ejecuta, no se reintenta, se diagnostica y va a DLQ.
 */
public class EnvelopeException extends RuntimeException {

    public enum Reason {
        VACIO,
        EXCEDE_TAMANO,
        JSON_INVALIDO,
        ESQUEMA_INVALIDO,
        IDENTIDAD_INVALIDA,
        TIEMPO_INVALIDO
    }

    private final Reason reason;

    public EnvelopeException(Reason reason, String detalle) {
        super(detalle);
        this.reason = reason;
    }

    public EnvelopeException(Reason reason, String detalle, Throwable causa) {
        super(detalle, causa);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
