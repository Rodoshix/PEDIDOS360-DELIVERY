package cl.duoc.pedidos360.repartidores;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class RepartidoresApplicationTests {

    @Value("${local.server.port}")
    private int port;

    @Test
    void healthRespondeUpSinBaseDeDatosNiAzure() throws Exception {
        try (var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            var health = new JsonMapper().readValue(response.body(), Map.class);
            assertThat(health.get("status")).isEqualTo("UP");
            assertThat(health.containsKey("components")).isFalse();
        }
    }
}
