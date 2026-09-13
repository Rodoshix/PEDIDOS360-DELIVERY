package cl.duoc.pedidos360.bff;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import cl.duoc.pedidos360.bff.security.EntraTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "entra.enabled=true", "bff.pedidos-pagos-enabled=false",
    "entra.tenant-id=11111111-1111-1111-1111-111111111111",
    "entra.api-client-id=22222222-2222-2222-2222-222222222222",
    "entra.frontend-client-id=33333333-3333-3333-3333-333333333333",
    "spring.config.import="
})
@Import(JwtHttpTests.Keys.class)
class PedidosPagosDisabledTests {
    @LocalServerPort int port;
    @Test void inclusoAdminQuedaBloqueadoSinHabilitacionExplicita() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : java.util.List.of("/pedidos", "/pedidos/me", "/pagos/1")) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Authorization", "Bearer " + EntraTestTokens.token(Map.of("roles", java.util.List.of("ADMIN"))))
                    .GET().build();
                assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            }
        }
    }
}
