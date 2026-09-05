package cl.duoc.pedidos360.usuarios;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import cl.duoc.pedidos360.usuarios.entity.Usuario;
import cl.duoc.pedidos360.usuarios.repository.UsuarioRepository;
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
        "usuarios.identidad-local.enabled=true",
        "usuarios.identidad-local.tenant-id=11111111-1111-1111-1111-111111111111",
        "usuarios.identidad-local.object-id=22222222-2222-2222-2222-222222222222"
})
@ActiveProfiles("local")
@Import(PostgresTestConfiguration.class)
abstract class UsuarioApiTestBase {
    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OBJECT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final Map<String, String> PERFIL = Map.of(
            "nombre", "Ana", "apellido", "Pérez", "email", "ana@example.test");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    JsonMapper mapper;

    @BeforeEach
    void limpiarBaseTemporal() {
        usuarios.deleteAll();
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

    Usuario guardar(UUID tenant, UUID objectId) {
        return usuarios.saveAndFlush(new Usuario(tenant, objectId, "Otra", "Persona",
                "otra@example.test", null));
    }
}
