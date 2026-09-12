package cl.duoc.pedidos360.carrito.service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import cl.duoc.pedidos360.carrito.exception.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consulta a un origen fijo sin cookies, identidad del navegador ni redirects. */
@Component
@ConditionalOnProperty(name = "carrito.catalogo-http-enabled", havingValue = "true")
public class CatalogoHttp implements CatalogoProductos, AutoCloseable {
    private final HttpClient client;
    private final URI origin;
    private final long timeout;
    private final JsonMapper mapper;

    public CatalogoHttp(Environment env, JsonMapper mapper) {
        if (env.getProperty("carrito.identidad-local.enabled", Boolean.class, false))
            throw new IllegalStateException("Catálogo real e identidad local no pueden coexistir.");
        URI configured = URI.create(env.getRequiredProperty("carrito.productos-url"));
        boolean local = java.util.List.of("localhost", "127.0.0.1", "[::1]").contains(
                configured.getHost() == null ? "" : configured.getHost());
        if (!("https".equals(configured.getScheme()) || (local && "http".equals(configured.getScheme())))
                || configured.getHost() == null || configured.getUserInfo() != null
                || configured.getQuery() != null || configured.getFragment() != null
                || !configured.getPath().isEmpty() || configured.getPort() == 0 || configured.getPort() > 65535)
            throw new IllegalArgumentException("Se requiere un origen de Productos válido.");
        origin = configured;
        timeout = env.getProperty("carrito.upstream-timeout-ms", Long.class, 5000L);
        if (timeout < 100 || timeout > 30000) throw new IllegalArgumentException("Timeout fuera de rango.");
        this.mapper = mapper;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public Producto obtener(Long id) {
        if (id == null || id < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "Producto inválido.");
        var request = HttpRequest.newBuilder(origin.resolve("/productos/" + id))
                .timeout(Duration.ofMillis(timeout)).header("Accept", "application/json").GET().build();
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(timeout, TimeUnit.MILLISECONDS);
            if (response.statusCode() == 404) throw new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado.");
            if (response.statusCode() != 200 || !response.headers().firstValue("content-type").orElse("")
                    .toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) throw unavailable();
            var json = mapper.readTree(response.body());
            if (json == null || !json.isObject()
                    || !positive(json.get("id")) || json.get("id").longValue() != id
                    || !positive(json.get("restauranteId"))
                    || !json.path("nombre").isString() || json.path("nombre").asText().isBlank()
                    || !json.path("disponible").isBoolean() || !json.path("precio").isNumber()) throw unavailable();
            // CLP enteros: no truncar decimales ni aceptar desbordamiento.
            long precio = new java.math.BigDecimal(json.get("precio").asText()).longValueExact();
            if (precio < 0 || precio > cl.duoc.pedidos360.carrito.entity.Carrito.MAX_PRECIO) throw unavailable();
            return new Producto(id, json.get("restauranteId").longValue(), json.get("nombre").asText(),
                    precio, json.get("disponible").booleanValue());
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ex) {
            pending.cancel(true);
            throw unavailable();
        }
    }
    private static boolean positive(tools.jackson.databind.JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0;
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo consultar el catálogo.");
    }
    @Override public void close() { client.close(); }
}
