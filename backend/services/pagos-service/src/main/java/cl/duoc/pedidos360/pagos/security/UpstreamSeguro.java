package cl.duoc.pedidos360.pagos.security;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Orígenes fijos, sin redirects ni reintentos; nunca propaga detalles remotos. */
@Component
public class UpstreamSeguro implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public static URI origen(String value) {
        URI uri = URI.create(value);
        boolean local = java.util.List.of("localhost", "127.0.0.1", "[::1]").contains(
            uri.getHost() == null ? "" : uri.getHost());
        if (!( "https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null || !uri.getPath().isEmpty() || uri.getPort() == 0
            || uri.getPort() > 65535) throw new IllegalArgumentException("Origen upstream inválido.");
        return uri;
    }
    public JsonNode get(URI url, String bearer) {
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        var pending = http.sendAsync(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() == 404)
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Recurso upstream no encontrado.");
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Acceso upstream rechazado.");
            if (response.statusCode() != 200 || !response.headers().firstValue("content-type")
                .orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) throw new IllegalStateException();
            var json = JsonMapper.builder().build().readTree(response.body());
            if (json == null || !json.isObject()) throw new IllegalStateException();
            return json;
        } catch (org.springframework.web.server.ResponseStatusException e) { throw e;
        } catch (Exception e) {
            pending.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Servicio temporalmente no disponible.");
        }
    }
    @Override public void close() { http.close(); }
}

