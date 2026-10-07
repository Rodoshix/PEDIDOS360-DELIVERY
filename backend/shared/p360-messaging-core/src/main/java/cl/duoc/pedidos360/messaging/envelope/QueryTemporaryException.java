package cl.duoc.pedidos360.messaging.envelope;

/**
 * Fallo transitorio de una consulta: habilita el unico retry corto del flujo.
 *
 * <p>Cualquier otra excepcion no clasificada se trata igual: se reintenta una vez y despues va a
 * DLQ. Un error de negocio esperado no usa este tipo.
 */
public class QueryTemporaryException extends RuntimeException {

    public QueryTemporaryException(String detalle, Throwable causa) {
        super(detalle, causa);
    }

    public QueryTemporaryException(String detalle) {
        super(detalle);
    }
}
