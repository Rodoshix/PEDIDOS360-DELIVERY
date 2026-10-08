package cl.duoc.pedidos360.messaging.relay;

/** Publicación sin transferencia confirmada; su estado no depende de la causa Java. */
public class HandoffFailureException extends RuntimeException {
    public enum ResultadoPublicacion {
        /** No se invocó el envío al transporte. */
        NO_ENVIADO,
        /** El broker devolvió nack o return concluyente. */
        RECHAZADO_CONFIRMADO,
        /** Se inició el envío y no se obtuvo un resultado concluyente. */
        INCIERTO,
        /** Publicación confirmada; el plazo del solicitante puede agotarse después. */
        CONFIRMADO
    }

    private final ResultadoPublicacion resultado;

    /** Compatible con validaciones anteriores al envío. */
    public HandoffFailureException(String detalle) {
        this(detalle, ResultadoPublicacion.NO_ENVIADO);
    }

    public HandoffFailureException(String detalle, ResultadoPublicacion resultado) {
        this(detalle, resultado, null);
    }

    public HandoffFailureException(String detalle, ResultadoPublicacion resultado, Throwable causa) {
        super(detalle, causa);
        this.resultado = java.util.Objects.requireNonNull(resultado);
    }

    public ResultadoPublicacion resultado() { return resultado; }
    public boolean resultadoIncierto() { return resultado == ResultadoPublicacion.INCIERTO; }
}
