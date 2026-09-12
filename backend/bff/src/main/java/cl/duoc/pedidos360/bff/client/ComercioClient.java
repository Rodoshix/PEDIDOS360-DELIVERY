package cl.duoc.pedidos360.bff.client;

import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import cl.duoc.pedidos360.bff.config.ConnectionConfiguration;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@Component
public class ComercioClient {
    private final HttpClient client;
    private final java.util.Map<String, URI> origins;
    private final long timeoutMs;

    public ComercioClient(HttpClient client, Environment env) {
        this.client = client;
        this.origins = java.util.Map.of(
            "restaurantes", ConnectionConfiguration.origin(env.getProperty("bff.restaurantes-url", "http://127.0.0.1:8082")),
            "productos", ConnectionConfiguration.origin(env.getProperty("bff.productos-url", "http://127.0.0.1:8083")),
            "carrito", ConnectionConfiguration.origin(env.getProperty("bff.carrito-url", "http://127.0.0.1:8084")));
        this.timeoutMs = env.getProperty("bff.upstream-timeout-ms", Long.class, 5000L);
        if (timeoutMs < 100 || timeoutMs > 30000) throw new IllegalArgumentException("Timeout fuera de rango.");
    }

    public ResponseEntity<?> call(String method, String path, String body, JwtAuthenticationToken token) {
        // Defensa adicional: solo rutas construidas por el controlador, sin URL aportada por el usuario.
        if (!allowed(method, path))
            throw new IllegalArgumentException("Ruta interna no permitida.");
        var origin = origins.get(path.split("/")[1]);
        var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofMillis(timeoutMs))
                
                .header("Accept", "application/json");
        // Catálogo solo se consulta: no necesita recibir el token del usuario.
        if (path.startsWith("/carrito")) builder.header("Authorization", "Bearer " + token.getToken().getTokenValue());
        if (body != null) builder.header("Content-Type", "application/json");
        var request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // El plazo incluye la recepción del cuerpo. No hay reintentos automáticos de escrituras.
            var response = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (Set.of(400, 401, 403, 404, 409, 429).contains(status)) return error(status);
            if (status != 200 && status != 201 && status != 204) return error(502);
            var result = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
            if (status == 204) return result.build();
            if (!response.headers().firstValue("content-type").orElse("").toLowerCase(java.util.Locale.ROOT)
                    .startsWith("application/json")) return error(502);
            return result.contentType(MediaType.APPLICATION_JSON).body(response.body());
        } catch (TimeoutException ex) {
            pending.cancel(true);
            return error(504);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return error(502);
        } catch (java.util.concurrent.ExecutionException ex) {
            return error(ex.getCause() instanceof HttpTimeoutException ? 504 : 502);
        }
    }

    static boolean allowed(String method, String path) {
        if ("GET".equals(method) && path.matches("/(?:restaurantes(?:/[1-9][0-9]*)?|productos(?:/[1-9][0-9]*|/restaurante/[1-9][0-9]*(?:/disponibles)?)?)")) return true;
        if (path.equals("/carrito")) return Set.of("GET", "DELETE").contains(method);
        if (path.equals("/carrito/items")) return method.equals("POST");
        return path.matches("/carrito/items/[1-9][0-9]*") && Set.of("PUT", "DELETE").contains(method);
    }

    private ResponseEntity<ProblemDetail> error(int status) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),
                status >= 500 ? "El servicio no está disponible temporalmente." : "La operación no pudo completarse.");
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }
}
