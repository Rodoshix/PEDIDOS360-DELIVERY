package cl.duoc.pedidos360.pedidos;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import cl.duoc.pedidos360.pedidos.dto.*;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import cl.duoc.pedidos360.pedidos.security.IdentidadUsuario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;

/** Base efímera de Testcontainers. Token de Entra solo por entorno y sin imprimir. */
@EnabledIfEnvironmentVariable(named = "RUN_ENTRA_WORKER_LIVE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "pedidos.interno.enabled=true", "pedidos.identidad-local.enabled=false",
    "pedidos.interno.tenant-id=${ENTRA_TENANT_ID}", "pedidos.interno.audience=${ENTRA_API_CLIENT_ID}",
    "pedidos.interno.worker-client-id=${PAGOS_WORKER_CLIENT_ID}", "pedidos.interno.jwk-set-uri="
})
@Import(PostgresTestConfiguration.class)
class WorkerEntraLiveTests {
    @LocalServerPort int port;
    @Autowired PedidoService pedidos;
    @Test void workerConfirmaIdempotentementePeroNoAccedeComoUsuario() throws Exception {
        var actor = new IdentidadUsuario(10L, Set.of(IdentidadUsuario.Rol.CLIENTE));
        var pedido = pedidos.crear(actor, new CrearPedidoRequest(20L, "Prueba aislada worker",
            List.of(new LineaPedidoRequest(101L, 1))));
        String token = System.getenv("WORKER_ACCESS_TOKEN");
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/internal/pedidos/" + pedido.pedidoId() + "/confirmacion-pago"))
                .header("Authorization", "Bearer " + token).PUT(HttpRequest.BodyPublishers.noBody()).build();
            for (int attempt = 0; attempt < 2; attempt++)
                assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
            var delegated = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/pedidos/me"))
                .header("Authorization", "Bearer " + token).GET().build();
            assertThat(http.send(delegated, HttpResponse.BodyHandlers.discarding()).statusCode()).isIn(401, 403);
            assertThat(pedidos.obtener(actor, pedido.pedidoId()).estado()).isEqualTo("CONFIRMADO");
        }
    }
}
