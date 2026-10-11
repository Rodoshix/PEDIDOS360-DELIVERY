package cl.duoc.pedidos360.messaging.relay;

/**
 * El broker no esta disponible o rechazo la publicacion.
 *
 * <p>El BFF lo traduce a una respuesta equivalente al HTTP 502 sin activar ninguna politica de
 * reintento implicita.
 */
public class QueryUnavailableException extends RuntimeException {
    public QueryUnavailableException(String detalle, Throwable causa) {
        super(detalle, causa);
    }
}
