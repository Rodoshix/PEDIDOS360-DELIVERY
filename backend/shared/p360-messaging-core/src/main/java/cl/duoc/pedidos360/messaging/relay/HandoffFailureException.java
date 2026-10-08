package cl.duoc.pedidos360.messaging.relay;

/**
 * La transferencia de responsabilidad del mensaje no quedo confirmada.
 *
 * <p>Implica no confirmar el request original: ya sea hacia el retry corto, hacia la DLQ o hacia la
 * respuesta correlacionada. Se aplica recuperacion acotada y nunca {@code requeue=true}.
 */
public class HandoffFailureException extends RuntimeException {
    /** Excepción de transporte/espera: no sabemos si el broker aceptó la publicación. */
    public boolean resultadoIncierto() { return getCause() != null; }

    public HandoffFailureException(String detalle) {
        super(detalle);
    }

    public HandoffFailureException(String detalle, Throwable causa) {
        super(detalle, causa);
    }
}
