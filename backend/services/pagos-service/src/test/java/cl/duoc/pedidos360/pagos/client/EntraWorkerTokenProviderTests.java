package cl.duoc.pedidos360.pagos.client;

import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class EntraWorkerTokenProviderTests {
    com.sun.net.httpserver.HttpServer server;
    int hits;
    int status = 200;
    String body = "{\"token_type\":\"Bearer\",\"access_token\":\"test-token\",\"expires_in\":120}";
    String received;
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    HttpClient http;
    EntraWorkerTokenProvider provider;
    @BeforeEach void start() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            hits++;
            received = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/token");
            var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        };
        provider = new EntraWorkerTokenProvider(http, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
            "worker", "api", "fake+secret&", clock);
    }
    @AfterEach void stop() { http.close(); server.stop(0); }
    @Test void cacheYRenovacion() {
        assertThat(provider.token()).isEqualTo("test-token");
        assertThat(provider.token()).isEqualTo("test-token");
        assertThat(hits).isEqualTo(1);
        assertThat(received).contains("client_secret=fake%2Bsecret%26", "scope=api%3A%2F%2Fapi%2F.default");
        now = now.plusSeconds(61);
        provider.token();
        assertThat(hits).isEqualTo(2);
    }
    @Test void noSigueRedirectNiFiltraRespuesta() {
        status = 302; body = "fake-secret-sensitive";
        assertThatThrownBy(provider::token).hasMessage("No se pudo autenticar el worker de Pagos.").hasNoCause();
        assertThat(hits).isEqualTo(1);
    }
    @Test void noReutilizaTokenTrasFalloDeRenovacion() {
        provider.token(); now = now.plusSeconds(61); status = 401;
        assertThatThrownBy(provider::token).isInstanceOf(IllegalStateException.class);
        assertThat(hits).isEqualTo(2);
    }
    @Test void rechazaRespuestaSinVigencia() {
        body = "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\"}";
        assertThatThrownBy(provider::token).isInstanceOf(IllegalStateException.class).hasNoCause();
    }
    @Test void rechazaConfiguracionIncompleta() {
        var env = new org.springframework.mock.env.MockEnvironment();
        assertThatThrownBy(() -> new EntraWorkerTokenProvider(env)).isInstanceOf(IllegalStateException.class);
    }
}
