package cl.duoc.pedidos360.bff;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BffSecurityTests {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void saludPublica() throws Exception {
        var response = get("/actuator/health", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test void rutasPrivadasCerradasSinSesion() throws Exception {
        var response = get("/usuarios/me", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    }

    @Test void bearerFicticioNoAutentica() throws Exception {
        assertThat(get("/usuarios/me", "Bearer token-ficticio").statusCode()).isEqualTo(401);
    }

    @Test void noHayFormularioDeLoginNiActuatorAdicional() throws Exception {
        assertThat(get("/login", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/env", null).statusCode()).isEqualTo(401);
    }

    @Test void forwardedHeadersCannotAuthenticateARequest() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/usuarios/me"))
                .header("Forwarded", "for=127.0.0.1;proto=https;host=trusted.invalid")
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-Proto", "https")
                .header("X-User-Id", "1").header("X-Roles", "ADMIN").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    }

    @Test void oversizedHeaderIsRejectedAndHealthStillWorks() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .header("X-Bounded-Test", "x".repeat(20000)).GET().build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }
}
