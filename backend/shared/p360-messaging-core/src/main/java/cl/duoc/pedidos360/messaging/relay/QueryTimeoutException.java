package cl.duoc.pedidos360.messaging.relay;

/** Plazo de una consulta agotado sin respuesta correlacionada. */
public class QueryTimeoutException extends RuntimeException {
    public QueryTimeoutException(String detalle) {
        super(detalle);
    }
    public QueryTimeoutException(String detalle, Throwable causa) { super(detalle, causa); }
}
