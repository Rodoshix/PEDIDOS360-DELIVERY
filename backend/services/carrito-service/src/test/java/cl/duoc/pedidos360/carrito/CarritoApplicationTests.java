package cl.duoc.pedidos360.carrito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@Import(PostgresTestConfiguration.class)
class CarritoApplicationTests {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
    }

    @Test
    void saludPublicaArrancaConPostgresSinAzureNiOtrosServicios() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request("/actuator/health").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var health = new JsonMapper().readValue(response.body(), Map.class);
            assertThat(health.get("status")).isEqualTo("UP");
            assertThat(health.containsKey("components")).isFalse();
            assertThat(health.containsKey("details")).isFalse();
        }
    }

    @Test
    void noCreaUsuariosNiContrasenasAutomaticasDeDesarrollo() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void bloqueaConsultaSinSesionYNoCreaCookieNiRedirige() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request("/carrito").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/problem+json");
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }
    }

    @Test
    void bearerYRolesEnCabecerasNoAutorizanOperaciones() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String method : new String[] { "GET", "POST", "PUT", "DELETE" }) {
                var response = client.send(request("/carrito/items/101")
                        .header("Authorization", "Bearer token-falso")
                        .header("X-User-Id", "1").header("X-Roles", "ADMIN")
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(method).isEqualTo(401);
                assertThat(response.body()).doesNotContain("token-falso", "ADMIN");
            }
        }
    }

    @Test
    void noExponeOtrosEndpointsDeActuatorNiLogin() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[] { "/actuator/env", "/actuator/configprops", "/login" }) {
                var response = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(401);
            }
        }
    }
}
