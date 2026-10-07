package cl.duoc.pedidos360.messaging.envelope;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validacion estricta de una respuesta correlacionada.
 *
 * <p>Se comparte entre el BFF, que resuelve la correlacion, y cualquier diagnostico, para que la
 * forma del mensaje se compruebe en un solo lugar. Rechaza campos extra, faltantes, duplicados y
 * contenido posterior al JSON.
 */
public final class ResponseSchema {

    private static final Set<String> CAMPOS = Set.of("operacion", "correlationId", "messageId", "success",
            "status", "payload", "error", "timestamp");

    private final JsonMapper json;

    public ResponseSchema() {
        this(JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build());
    }

    public ResponseSchema(JsonMapper json) {
        this.json = json;
    }

    /** Parsea la respuesta. Vacio significa que el cuerpo no corresponde al contrato. */
    public Optional<QueryResponse> leer(byte[] cuerpo, int maxBytes) {
        if (cuerpo == null || cuerpo.length == 0 || cuerpo.length > maxBytes) return Optional.empty();
        JsonNode raiz;
        try {
            raiz = json.readTree(cuerpo);
        } catch (RuntimeException invalido) {
            return Optional.empty();
        }
        if (raiz == null || !raiz.isObject()) return Optional.empty();
        if (!JsonFields.exactos(raiz, CAMPOS)) return Optional.empty();
        if (!raiz.path("operacion").isString() || !raiz.path("correlationId").isString()
                || !raiz.path("messageId").isString() || !raiz.path("success").isBoolean()
                || !raiz.path("status").isIntegralNumber() || !raiz.path("timestamp").isString())
            return Optional.empty();
        boolean exito = raiz.path("success").booleanValue();
        JsonNode payload = raiz.path("payload");
        JsonNode error = raiz.path("error");
        // El contrato exige los ocho campos: el que no aplica viaja como null explicito, no ausente.
        if (exito) {
            if (!payload.isObject() || !error.isNull()) return Optional.empty();
        } else if (!error.isObject() || !payload.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new QueryResponse(raiz.path("operacion").stringValue(),
                    raiz.path("correlationId").stringValue(),
                    UUID.fromString(raiz.path("messageId").stringValue()), exito, raiz.path("status").intValue(),
                    exito ? payload : null, exito ? null : error(error),
                    Instant.parse(raiz.path("timestamp").stringValue())));
        } catch (RuntimeException invalido) {
            return Optional.empty();
        }
    }

    /** Validacion minima para descartar respuestas ajenas al contrato sin construirlas. */
    public boolean estructuraValida(byte[] cuerpo, int maxBytes) {
        return leer(cuerpo, maxBytes).isPresent();
    }

    private static QueryResponse.ErrorDetail error(JsonNode nodo) {
        if (!nodo.path("code").isString() || !nodo.path("title").isString()
                || !nodo.path("status").isIntegralNumber()) throw new IllegalArgumentException("error invalido");
        String detalle = nodo.path("detail").isString() ? nodo.path("detail").stringValue() : "";
        return new QueryResponse.ErrorDetail(nodo.path("code").stringValue(), nodo.path("title").stringValue(), detalle,
                nodo.path("status").intValue());
    }
}
