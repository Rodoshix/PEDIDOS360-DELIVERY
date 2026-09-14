package cl.duoc.pedidos360.repartidores;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import cl.duoc.pedidos360.repartidores.entity.EstadoDisponibilidad;
import cl.duoc.pedidos360.repartidores.entity.Repartidor;
import cl.duoc.pedidos360.repartidores.entity.Vehiculo;
import cl.duoc.pedidos360.repartidores.repository.AsignacionRepartidorRepository;
import cl.duoc.pedidos360.repartidores.repository.RepartidorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "repartidores.identidad-local.enabled=true",
        "repartidores.identidad-local.tenant-id=11111111-1111-1111-1111-111111111111",
        "repartidores.identidad-local.object-id=22222222-2222-2222-2222-222222222222"
})
@ActiveProfiles("local")
@Import(PostgresTestConfiguration.class)
abstract class RepartidorApiTestBase {
    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OBJECT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final Map<String, String> PERFIL = Map.of(
            "nombre", "Repartidor Uno", "vehiculo", "MOTO");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    RepartidorRepository repartidores;

    @Autowired
    AsignacionRepartidorRepository asignaciones;

    @Autowired
    JsonMapper mapper;

    @BeforeEach
    void limpiarBaseTemporal() {
        asignaciones.deleteAll();
        repartidores.deleteAll();
    }

    HttpResponse<String> llamar(String metodo, String ruta, Object body) throws Exception {
        return llamar(metodo, ruta, body, Map.of());
    }

    HttpResponse<String> llamar(String metodo, String ruta, Object body,
                               Map<String, String> headers) throws Exception {
        return enviarTexto(metodo, ruta, body == null ? null : mapper.writeValueAsString(body),
                "application/json", headers);
    }

    HttpResponse<String> enviarTexto(String metodo, String ruta, String body,
                                    String contentType, Map<String, String> headers) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta))
                    .timeout(Duration.ofSeconds(10));
            headers.forEach(builder::header);
            var contenido = HttpRequest.BodyPublishers.noBody();
            if (body != null) {
                builder.header("Content-Type", contentType);
                contenido = HttpRequest.BodyPublishers.ofString(body);
            }
            return client.send(builder.method(metodo, contenido).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    Repartidor guardar(UUID tenant, UUID objectId) {
        return repartidores.saveAndFlush(new Repartidor(tenant, objectId,
                "Otra", "+56900000000", Vehiculo.AUTO, null));
    }
}
