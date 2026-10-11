package cl.duoc.pedidos360.messaging.envelope;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Error de negocio esperado de una consulta.
 *
 * <p>Se traduce a una {@link QueryResponse} correlacionada con el mismo codigo del contrato HTTP
 * (por ejemplo 403 o 404) y el mensaje se confirma. No genera retry ni DLQ.
 */
public class QueryBusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public QueryBusinessException(HttpStatus status, String code, String detalle) {
        super(detalle);
        if (status == null || !status.is4xxClientError())
            throw new IllegalArgumentException("un error de negocio debe ser 4xx");
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static QueryBusinessException prohibido(String detalle) {
        return new QueryBusinessException(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", detalle);
    }

    public static QueryBusinessException noEncontrado(String detalle) {
        return new QueryBusinessException(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO", detalle);
    }

    public static QueryBusinessException conflicto(String detalle) {
        return new QueryBusinessException(HttpStatus.CONFLICT, "CONFLICTO", detalle);
    }

    /** Error equivalente serializable, sin datos sensibles. */
    public QueryResponse.ErrorDetail aError() {
        Map<Integer, String> titulos = Map.of(400, "Solicitud invalida", 403, "Acceso denegado",
                404, "Recurso no encontrado", 409, "Conflicto", 429, "Demasiadas solicitudes");
        String titulo = titulos.getOrDefault(status.value(), "Error de negocio");
        return new QueryResponse.ErrorDetail(code, titulo, getMessage(), status.value());
    }
}
