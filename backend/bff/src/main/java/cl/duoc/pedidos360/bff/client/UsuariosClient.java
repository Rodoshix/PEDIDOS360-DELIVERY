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
public class UsuariosClient {
    private final HttpClient client;
    private final URI origin;
    private final long timeoutMs;

    public UsuariosClient(HttpClient client, Environment env) {
        this.client = client;
        this.origin = ConnectionConfiguration.origin(env.getProperty("bff.usuarios-url", "http://127.0.0.1:8081"));
        this.timeoutMs = env.getProperty("bff.upstream-timeout-ms", Long.class, 5000L);
        if (timeoutMs < 100 || timeoutMs > 30000) throw new IllegalArgumentException("Timeout fuera de rango.");
    }

    public ResponseEntity<?> call(String method, String path, String body, JwtAuthenticationToken token) {
        // Defensa adicional: solo rutas construidas por el controlador, sin URL aportada por el usuario.
        if (!path.matches("/usuarios(?:/me|/[1-9][0-9]*|\\?pagina=[0-9]+&tamanio=[0-9]+)?"))
            throw new IllegalArgumentException("Ruta interna no permitida.");
        var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + token.getToken().getTokenValue())
                .header("Accept", "application/json");
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
            response.headers().firstValue("location").filter(value -> value.matches("/usuarios/[1-9][0-9]*"))
                    .ifPresent(value -> result.header("Location", value));
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

    private ResponseEntity<ProblemDetail> error(int status) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),
                status >= 500 ? "Usuarios no está disponible temporalmente." : "La operación no pudo completarse.");
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }
}
