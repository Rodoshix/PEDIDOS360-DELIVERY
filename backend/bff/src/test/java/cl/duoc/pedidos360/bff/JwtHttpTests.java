package cl.duoc.pedidos360.bff;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import cl.duoc.pedidos360.bff.security.EntraTestTokens;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "entra.enabled=true",
    "entra.tenant-id=11111111-1111-1111-1111-111111111111",
    "entra.api-client-id=22222222-2222-2222-2222-222222222222",
    "entra.frontend-client-id=33333333-3333-3333-3333-333333333333",
    "usuarios.identidad-local.enabled=false",
    "spring.config.import="
})
@Import(JwtHttpTests.Keys.class)
class JwtHttpTests {
    static final com.sun.net.httpserver.HttpServer upstream = start();
    static volatile int status = 200;
    static volatile long delay;
    static volatile String lastToken, lastCookie, lastUser, lastRoles, lastMethod, lastPath, lastBody;
    static final java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();

    static com.sun.net.httpserver.HttpServer start() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                lastToken = exchange.getRequestHeaders().getFirst("Authorization");
                lastCookie = exchange.getRequestHeaders().getFirst("Cookie");
                lastUser = exchange.getRequestHeaders().getFirst("X-User-Id");
                lastRoles = exchange.getRequestHeaders().getFirst("X-Roles");
                lastMethod = exchange.getRequestMethod();
                lastPath = exchange.getRequestURI().toString();
                lastBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                try { Thread.sleep(delay); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Set-Cookie", "internal=secret");
                exchange.getResponseHeaders().set("Location", status == 302 ? "http://127.0.0.1:1/leak" : "/usuarios/7");
                byte[] body = (status >= 400 ? "{\"detail\":\"INTERNAL_SECRET\"}" : "{\"id\":7}").getBytes();
                exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
                if (status != 204) exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @org.springframework.test.context.DynamicPropertySource
    static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("bff.usuarios-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        registry.add("bff.upstream-timeout-ms", () -> 500);
    }
    @org.junit.jupiter.api.BeforeEach void reset() { status = 200; delay = 0; hits.set(0); }
    @org.junit.jupiter.api.AfterAll static void stop() { upstream.stop(0); }
    @LocalServerPort int port;
    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean @Primary JwtDecoder testDecoder() { return EntraTestTokens.decoder(); }
    }
    HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        return call(method, path, token, body, Map.of());
    }
    HttpResponse<String> call(String method, String path, String token, String body, Map<String,String> headers) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("X-User-Id", "attacker").header("X-Roles", "ADMIN").header("Cookie", "session=attacker");
            headers.forEach(request::header);
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void sinTokenNiCabecerasFalsificadasAutentican() throws Exception {
        assertThat(call("GET", "/usuarios/me", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/usuarios/me", "falso", null).statusCode()).isEqualTo(401);
        assertThat(hits.get()).isZero();
    }
    @Test void scopeYRolSonObligatorios() throws Exception {
        for (var claims : List.of(Map.<String,Object>of("scp", "otro"),
                Map.<String,Object>of("roles", List.of("SUPERADMIN")))) {
            assertThat(call("GET", "/usuarios/me", EntraTestTokens.token(claims), null).statusCode()).isEqualTo(403);
        }
    }
    @Test void audienciaDelFrontendNoSirve() throws Exception {
        assertThat(call("GET", "/usuarios/me", EntraTestTokens.token(Map.of("aud", EntraTestTokens.FRONTEND)), null)
                .statusCode()).isEqualTo(401);
    }
    
    @Test void tokenValidoAccedeSinCrearSesion() throws Exception {
        String token = EntraTestTokens.token(Map.of());
        var response = call("GET", "/usuarios/me", token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
        assertThat(lastToken).isEqualTo("Bearer " + token);
        assertThat(lastCookie).isNull();
        assertThat(lastUser).isNull();
        assertThat(lastRoles).isNull();
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
    }
    @Test void reenviaCreacionActualizacionYBaja() throws Exception {
        String token = EntraTestTokens.token(Map.of());
        status = 201;
        var response = call("POST", "/usuarios", token, "{\"nombre\":\"Ana\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("location")).hasValue("/usuarios/7");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastBody).contains("Ana");
        status = 200;
        assertThat(call("PUT", "/usuarios/7", token, "{\"nombre\":\"Otra\"}").statusCode()).isEqualTo(200);
        assertThat(lastMethod).isEqualTo("PUT");
        status = 204;
        assertThat(call("DELETE", "/usuarios/7", token, null).statusCode()).isEqualTo(204);
        assertThat(lastMethod).isEqualTo("DELETE");
    }
    @Test void paginaYRechazaRutasNoPermitidas() throws Exception {
        var token = EntraTestTokens.token(Map.of());
        assertThat(call("GET", "/usuarios?pagina=2&tamanio=10", token, null).statusCode()).isEqualTo(200);
        assertThat(lastPath).isEqualTo("/usuarios?pagina=2&tamanio=10");
        int before = hits.get();
        assertThat(call("GET", "/usuarios?pagina=-1", token, null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/usuarios/0", token, null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/usuarios/7/extra", token, null).statusCode()).isEqualTo(404);
        assertThat(hits.get()).isEqualTo(before);
    }
    @Test void preservaErroresControladosSinExponerDetalleInterno() throws Exception {
        for (int code : List.of(400,401,403,404,409,429,500)) {
            status = code;
            var response = call("GET", "/usuarios/me", EntraTestTokens.token(Map.of()), null);
            assertThat(response.statusCode()).isEqualTo(code == 500 ? 502 : code);
            assertThat(response.body()).doesNotContain("INTERNAL_SECRET");
        }
    }
    @Test void noSigueRedirecciones() throws Exception {
        status = 302;
        assertThat(call("GET", "/usuarios/me", EntraTestTokens.token(Map.of()), null).statusCode()).isEqualTo(502);
        assertThat(hits.get()).isEqualTo(1);
    }
    @Test void preflightSoloDesdeOrigenPermitido() throws Exception {
        var allowed = call("OPTIONS", "/usuarios/me", null, null, Map.of("Origin", "http://localhost:5173",
                "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "authorization"));
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("access-control-allow-origin")).hasValue("http://localhost:5173");
        assertThat(allowed.headers().firstValue("access-control-allow-credentials")).isEmpty();
        assertThat(call("OPTIONS", "/usuarios/me", null, null, Map.of("Origin", "https://evil.example",
                "Access-Control-Request-Method", "GET")).statusCode()).isEqualTo(403);
        assertThat(hits.get()).isZero();
    }
    @Test void origenAjenoNoLlegaAlServicioYErroresTienenCors() throws Exception {
        assertThat(call("GET", "/usuarios/me", EntraTestTokens.token(Map.of()), null,
                Map.of("Origin", "https://evil.example")).statusCode()).isEqualTo(403);
        var response = call("GET", "/usuarios/me", null, null, Map.of("Origin", "http://localhost:5173"));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("access-control-allow-origin")).hasValue("http://localhost:5173");
        assertThat(hits.get()).isZero();
    }
    @Test void esperaAcotada() throws Exception {
        delay = 900;
        assertThat(call("GET", "/usuarios/me", EntraTestTokens.token(Map.of()), null).statusCode()).isEqualTo(504);
        Thread.sleep(500); // permitir terminar el handler temporal antes de la siguiente prueba
    }
}
