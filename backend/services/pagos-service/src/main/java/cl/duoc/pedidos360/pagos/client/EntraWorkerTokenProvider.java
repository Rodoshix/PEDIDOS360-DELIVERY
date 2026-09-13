package cl.duoc.pedidos360.pagos.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Credencial solo backend; token opaco en memoria, sin logs ni reintentos implícitos. */
@Component
@ConditionalOnProperty(name = "pagos.worker.enabled", havingValue = "true")
public final class EntraWorkerTokenProvider implements TokenAplicacionProvider {
    private final HttpClient client;
    private final URI endpoint;
    private final String form;
    private final Clock clock;
    private String cached;
    private Instant refreshAt = Instant.MIN;

    @org.springframework.beans.factory.annotation.Autowired
    public EntraWorkerTokenProvider(Environment env) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build(),
            URI.create("https://login.microsoftonline.com/" + uuid(env.getProperty("pagos.worker.tenant-id")) + "/oauth2/v2.0/token"),
            uuid(env.getProperty("pagos.worker.client-id")), uuid(env.getProperty("pagos.worker.api-client-id")),
            env.getProperty("pagos.worker.client-secret"), Clock.systemUTC());
    }

    // Inyección solo de paquete para pruebas HTTP locales, no configurable en producción.
    EntraWorkerTokenProvider(HttpClient client, URI endpoint, String worker, String api, String secret, Clock clock) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("Falta credencial del worker.");
        this.client = client;
        this.endpoint = endpoint;
        this.clock = clock;
        this.form = "grant_type=client_credentials&client_id=" + encode(worker)
            + "&scope=" + encode("api://" + api + "/.default") + "&client_secret=" + encode(secret);
    }

    private static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalStateException("Configurar los UUID del worker y de Entra.");
        return java.util.UUID.fromString(value).toString();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Override public synchronized String token() {
        if (cached != null && clock.instant().isBefore(refreshAt)) return cached;
        cached = null;
        var started = clock.instant();
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException();
            var json = JsonMapper.builder().build().readTree(response.body());
            var access = json.get("access_token");
            var lifetime = json.get("expires_in");
            if (!"Bearer".equalsIgnoreCase(json.path("token_type").asText()) || access == null || !access.isTextual()
                || access.asText().isBlank() || access.asText().chars().anyMatch(Character::isWhitespace)
                || lifetime == null || !lifetime.isIntegralNumber() || !lifetime.canConvertToLong()
                || lifetime.longValue() <= 60 || lifetime.longValue() > 86400) throw new IllegalStateException();
            refreshAt = started.plusSeconds(lifetime.longValue() - 60);
            cached = access.asText();
            return cached;
        } catch (Exception error) {
            pending.cancel(true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            // No adjuntar causas: podrían contener respuesta remota o datos sensibles.
            throw new IllegalStateException("No se pudo autenticar el worker de Pagos.");
        }
    }
}
