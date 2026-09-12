package cl.duoc.pedidos360.carrito;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import cl.duoc.pedidos360.carrito.security.EntraTestTokens;
import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "entra.enabled=true", "carrito.identidad-local.enabled=false", "carrito.catalogo-http-enabled=true",
    "entra.tenant-id=11111111-1111-1111-1111-111111111111",
    "entra.api-client-id=22222222-2222-2222-2222-222222222222",
    "entra.frontend-client-id=33333333-3333-3333-3333-333333333333", "spring.config.import="
})
@Import({PostgresTestConfiguration.class, CarritoEntraHttpTests.Keys.class})
class CarritoEntraHttpTests {
    static volatile int catalogStatus = 200;
    static volatile String price = "6990.00";
    static volatile String authorization;
    static final com.sun.net.httpserver.HttpServer catalog = start();
    static com.sun.net.httpserver.HttpServer start() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/productos/101", exchange -> {
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                var body = ("{\"id\":101,\"restauranteId\":20,\"nombre\":\"Prueba\",\"precio\":" + price + ",\"disponible\":true}").getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "/productos/101");
                exchange.sendResponseHeaders(catalogStatus, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("carrito.productos-url", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    }
    @TestConfiguration(proxyBeanMethods = false) static class Keys {
        @Bean @Primary JwtDecoder testDecoder() { return EntraTestTokens.decoder(); }
    }
    @LocalServerPort int port;
    @Autowired CarritoRepository repository;
    @Autowired JsonMapper mapper;
    @BeforeEach void reset() { repository.deleteAll(); catalogStatus = 200; price = "6990.00"; }
    @AfterAll static void stop() { catalog.stop(0); }
    HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("X-User-Id", "attacker").header("X-Roles", "ADMIN");
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void jwtRealFirmadoAislaCarritosYConsultaCatalogoHttp() throws Exception {
        String one = EntraTestTokens.token(Map.of());
        String two = EntraTestTokens.token(Map.of("oid", "55555555-5555-5555-5555-555555555555", "roles", List.of("ADMIN")));
        assertThat(call("POST", "/carrito/items", one, "{\"productoId\":101,\"cantidad\":2}").statusCode()).isEqualTo(200);
        assertThat(authorization).isNull();
        assertThat(mapper.readTree(call("GET", "/carrito", one, null).body()).get("total").longValue()).isEqualTo(13980);
        assertThat(mapper.readTree(call("GET", "/carrito", two, null).body()).get("items").size()).isZero();
        assertThat(call("DELETE", "/carrito/items/101", two, null).statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/carrito/items/101", one, "{\"cantidad\":3}").statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(call("GET", "/carrito", one, null).body()).get("total").longValue()).isEqualTo(20970);
        assertThat(repository.count()).isEqualTo(1);
    }
    @Test void rechazaCabecerasFalsasYTokenSinPermisos() throws Exception {
        assertThat(call("GET", "/carrito", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/carrito", "falso", null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/carrito", EntraTestTokens.token(Map.of("scp", "otro")), null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/carrito", EntraTestTokens.token(Map.of("roles", List.of())), null).statusCode()).isEqualTo(403);
        assertThat(repository.count()).isZero();
    }
    @Test void catalogoNoRedirigeNiTruncaPreciosNiGuardaAnteFallo() throws Exception {
        String token = EntraTestTokens.token(Map.of());
        for (int status : List.of(302, 500, 404)) {
            catalogStatus = status;
            assertThat(call("POST", "/carrito/items", token, "{\"productoId\":101,\"cantidad\":1}").statusCode())
                    .isEqualTo(status == 404 ? 404 : 503);
            assertThat(repository.count()).isZero();
        }
        catalogStatus = 200;
        price = "6990.50";
        assertThat(call("POST", "/carrito/items", token, "{\"productoId\":101,\"cantidad\":1}").statusCode()).isEqualTo(503);
        assertThat(repository.count()).isZero();
    }
}
