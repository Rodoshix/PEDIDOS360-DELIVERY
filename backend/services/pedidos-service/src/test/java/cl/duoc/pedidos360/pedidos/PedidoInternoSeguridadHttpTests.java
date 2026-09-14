package cl.duoc.pedidos360.pedidos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import cl.duoc.pedidos360.pedidos.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.pedidos.dto.LineaPedidoRequest;
import cl.duoc.pedidos360.pedidos.security.IdentidadUsuario;
import cl.duoc.pedidos360.pedidos.security.IdentidadUsuario.Rol;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas HTTP del endpoint interno con decoder real (firma contra un JWKS local)
 * y de la separación de políticas: el worker no entra a rutas delegadas y un token
 * de usuario no entra al endpoint interno.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@Import(PostgresTestConfiguration.class)
class PedidoInternoSeguridadHttpTests {

    private static final String TENANT = "a048ca4e-cd7f-4a01-a43e-cb4deccf1ff2";
    private static final String AUD = "13c0f63f-2007-41c4-8d9f-02640b8a1886";
    private static final String WORKER = "worker-client-id";
    private static final String ROL = "Pedidos.Confirmar";

    private static JwksTestServer jwks;

    @BeforeAll
    static void iniciarJwks() throws Exception {
        jwks = new JwksTestServer(TENANT);
    }

    @AfterAll
    static void detenerJwks() {
        jwks.close();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("pedidos.interno.enabled", () -> "true");
        registry.add("pedidos.interno.tenant-id", () -> TENANT);
        registry.add("pedidos.interno.audience", () -> AUD);
        registry.add("pedidos.interno.worker-client-id", () -> WORKER);
        registry.add("pedidos.interno.rol-requerido", () -> ROL);
        registry.add("pedidos.interno.jwk-set-uri", jwks::jwkSetUri);
    }

    @Autowired
    private PedidoService pedidos;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

    @Test
    void workerValidoConfirmaElPedidoYDevuelve204() throws Exception {
        long pedidoId = crearPedido();

        var respuesta = put("/internal/pedidos/" + pedidoId + "/confirmacion-pago",
                jwks.tokenValido(TENANT, AUD, WORKER, ROL));

        assertThat(respuesta.statusCode()).isEqualTo(204);
    }

    @Test
    void firmaInvalidaSeRechazaCon401() throws Exception {
        long pedidoId = crearPedido();

        var respuesta = put("/internal/pedidos/" + pedidoId + "/confirmacion-pago",
                jwks.tokenFirmaInvalida(TENANT, AUD, WORKER, ROL));

        assertThat(respuesta.statusCode()).isEqualTo(401);
    }

    @Test
    void tokenDelegadoConScpSeRechazaEnElEndpointInterno() throws Exception {
        long pedidoId = crearPedido();

        var respuesta = put("/internal/pedidos/" + pedidoId + "/confirmacion-pago",
                jwks.tokenDelegado(TENANT, AUD, WORKER, ROL));

        assertThat(respuesta.statusCode()).isEqualTo(401);
    }

    @Test
    void sinTokenSeRechazaCon401() throws Exception {
        long pedidoId = crearPedido();

        var respuesta = put("/internal/pedidos/" + pedidoId + "/confirmacion-pago", null);

        assertThat(respuesta.statusCode()).isEqualTo(401);
    }

    @Test
    void elWorkerNoAccedeALasRutasDelegadas() throws Exception {
        // Un token de aplicación no debe servir para las rutas de usuario.
        var respuesta = get("/pedidos/me", jwks.tokenValido(TENANT, AUD, WORKER, ROL));

        assertThat(respuesta.statusCode()).isIn(401, 403);
    }

    private long crearPedido() {
        var pedido = pedidos.crear(new IdentidadUsuario(10L, Set.of(Rol.CLIENTE)),
                new CrearPedidoRequest(20L, "Av. Ejemplo 123", List.of(new LineaPedidoRequest(101L, 1))));
        return pedido.pedidoId();
    }

    private HttpResponse<String> put(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
