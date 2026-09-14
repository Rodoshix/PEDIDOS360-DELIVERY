package cl.duoc.pedidos360.carrito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "carrito.identidad-local.enabled=true",
        "carrito.identidad-local.tenant-id=11111111-1111-1111-1111-111111111111",
        "carrito.identidad-local.object-id=22222222-2222-2222-2222-222222222222",
        "carrito.identidad-local.roles=CLIENTE"
})
@ActiveProfiles("local")
@Import(PostgresTestConfiguration.class)
abstract class CarritoApiTestBase {
    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OBJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    @Value("${local.server.port}") private int port;
    @Autowired CarritoRepository carritos;
    @Autowired JsonMapper mapper;

    @BeforeEach
    void limpiarBaseTemporal() { carritos.deleteAll(); }

    HttpResponse<String> llamar(String metodo, String ruta, Object body) throws Exception {
        return enviar(metodo, ruta, body == null ? null : mapper.writeValueAsString(body), "application/json", Map.of());
    }

    HttpResponse<String> enviar(String metodo, String ruta, String body, String tipo, Map<String, String> headers) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta)).timeout(Duration.ofSeconds(10));
            headers.forEach(request::header);
            if (body != null) request.header("Content-Type", tipo);
            return client.send(request.method(metodo, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }

    HttpResponse<String> agregar(long productoId, int cantidad) throws Exception {
        return llamar("POST", "/carrito/items", Map.of("productoId", productoId, "cantidad", cantidad));
    }
}
