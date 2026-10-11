package cl.duoc.pedidos360.carrito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import cl.duoc.pedidos360.carrito.messaging.CarritoReceiptStore;
import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import cl.duoc.pedidos360.carrito.security.EntraTestTokens;
import cl.duoc.pedidos360.carrito.service.CatalogoProductos;
import cl.duoc.pedidos360.messaging.command.CarritoCommandProperties;
import cl.duoc.pedidos360.messaging.command.VaciarCarritoPorPedido;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = CarritoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "entra.enabled=true", "carrito.identidad-local.enabled=false", "carrito.catalogo-http-enabled=false",
    "entra.tenant-id=11111111-1111-1111-1111-111111111111",
    "entra.api-client-id=22222222-2222-2222-2222-222222222222",
    "entra.frontend-client-id=33333333-3333-3333-3333-333333333333", "spring.config.import=",
    "pedidos360.messaging.carrito.mode=${CARRITO_PEDIDOS_MODE}", "CARRITO_PEDIDOS_MODE=RABBITMQ",
    "pedidos360.messaging.carrito.platform-ready=true"
})
@Import({PostgresTestConfiguration.class, CarritoRabbitHttpGuardTests.Keys.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CarritoRabbitHttpGuardTests {
    static final UUID TENANT = UUID.fromString(EntraTestTokens.TENANT);
    static final UUID OWNER = UUID.fromString(EntraTestTokens.USER);
    static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4.1.8-management-alpine");
    static {
        BROKER.start();
        var connection = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
        try {
            connection.setUsername(BROKER.getAdminUsername()); connection.setPassword(BROKER.getAdminPassword());
            new RabbitAdmin(connection).declareQueue(new Queue(CarritoCommandProperties.QUEUE, true));
        } finally {
            connection.destroy();
        }
    }
    @DynamicPropertySource static void messaging(DynamicPropertyRegistry r) {
        r.add("pedidos360.messaging.carrito.host", BROKER::getHost);
        r.add("pedidos360.messaging.carrito.port", BROKER::getAmqpPort);
        r.add("pedidos360.messaging.carrito.virtual-host", () -> "/");
        r.add("pedidos360.messaging.carrito.username", BROKER::getAdminUsername);
        r.add("pedidos360.messaging.carrito.password", BROKER::getAdminPassword);
        r.add("pedidos360.messaging.carrito.consumer-user", BROKER::getAdminUsername);
    }
    @TestConfiguration(proxyBeanMethods = false) static class Keys {
        @Bean @Primary JwtDecoder testDecoder() { return EntraTestTokens.decoder(); }
    }
    @LocalServerPort int port;
    @Autowired CarritoRepository carritos;
    @Autowired CarritoReceiptStore receipts;
    @Autowired JsonMapper mapper;
    @MockitoBean CatalogoProductos catalogo;
    ConfigurableApplicationContext bff;
    int bffPort;

    @BeforeAll void startBff() {
        bff = new SpringApplicationBuilder(cl.duoc.pedidos360.bff.BffApplication.class, Keys.class).run(
            "--server.port=0", "--server.address=127.0.0.1", "--spring.config.import=",
            "--entra.enabled=true", "--entra.tenant-id=" + EntraTestTokens.TENANT,
            "--entra.api-client-id=" + EntraTestTokens.API, "--entra.frontend-client-id=" + EntraTestTokens.FRONTEND,
            "--bff.carrito-url=http://127.0.0.1:" + port,
            "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
        bffPort = Integer.parseInt(bff.getEnvironment().getProperty("local.server.port"));
    }
    @AfterAll void stopBff() { if (bff != null) bff.close(); }
    @BeforeEach void fixture() {
        carritos.deleteAll();
        when(catalogo.obtener(101L)).thenReturn(new CatalogoProductos.Producto(101L, 20L, "Prueba", 6990, true));
    }
    HttpResponse<String> call(int target, String method, String path, String token, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target + path))
                .timeout(java.time.Duration.ofSeconds(10));
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    String token() { return EntraTestTokens.token(Map.of()); }
    void seed() throws Exception { assertThat(call(port, "POST", "/carrito/items", token(), "{\"productoId\":101,\"cantidad\":1}").statusCode()).isEqualTo(200); }
    JsonNode cart() throws Exception { return mapper.readTree(call(port, "GET", "/carrito", token(), null).body()); }
    VaciarCarritoPorPedido command() {
        var c = carritos.findByTenantIdAndEntraObjectId(TENANT, OWNER).orElseThrow();
        return new VaciarCarritoPorPedido(UUID.randomUUID(), "VaciarCarritoPorPedido", 1, Instant.now(), 100L, c.getId(), c.getVersion(), new VaciarCarritoPorPedido.Propietario(TENANT, OWNER));
    }
    @Test void completeDeleteDirectAndThroughRealBffIs409WithoutAnyChange() throws Exception {
        seed(); var before = cart();
        for (int target : new int[] {port, bffPort}) {
            assertThat(call(target, "DELETE", "/carrito", token(), null).statusCode()).isEqualTo(409);
            assertThat(cart()).isEqualTo(before);
        }
    }
    @Test void emptyCartDeleteStillFailsClosedWithoutCreatingCart() throws Exception {
        assertThat(call(port, "DELETE", "/carrito", token(), null).statusCode()).isEqualTo(409);
        assertThat(carritos.count()).isZero();
    }
    @Test void individualWritesRemainAvailableAndAdvanceVersion() throws Exception {
        seed(); long v = cart().get("version").longValue();
        assertThat(call(port, "PUT", "/carrito/items/101", token(), "{\"cantidad\":2}").statusCode()).isEqualTo(200);
        assertThat(cart().get("version").longValue()).isGreaterThan(v); v = cart().get("version").longValue();
        assertThat(call(port, "DELETE", "/carrito/items/101", token(), null).statusCode()).isEqualTo(204);
        assertThat(cart().get("version").longValue()).isGreaterThan(v);
    }
    @Test void laterVersionIsPreservedByConsumer() throws Exception {
        seed(); var command = command();
        assertThat(call(port, "POST", "/carrito/items", token(), "{\"productoId\":101,\"cantidad\":1}").statusCode()).isEqualTo(200);
        var before = cart(); receipts.accept(command, false);
        assertThat(receipts.process(command, false)).isEqualTo(CarritoReceiptStore.Result.OMITTED_VERSION_CHANGED);
        assertThat(cart()).isEqualTo(before);
    }
    @Test void consumerCanEmptyAndRedeliveryDoesNotRepeatMutation() throws Exception {
        seed(); var command = command(); receipts.accept(command, false);
        assertThat(receipts.process(command, false)).isEqualTo(CarritoReceiptStore.Result.EMPTIED);
        var after = cart(); assertThat(after.get("items").size()).isZero();
        receipts.accept(command, false);
        assertThat(receipts.process(command, false)).isEqualTo(CarritoReceiptStore.Result.EMPTIED);
        assertThat(cart()).isEqualTo(after);
    }
    @Test void otherOwnerAndTenantCannotReadOrChangeOriginalCart() throws Exception {
        seed(); var before = cart();
        var other = EntraTestTokens.token(Map.of("oid", "55555555-5555-5555-5555-555555555555"));
        assertThat(mapper.readTree(call(port, "GET", "/carrito", other, null).body()).get("items").size()).isZero();
        assertThat(call(port, "PUT", "/carrito/items/101", other, "{\"cantidad\":2}").statusCode()).isEqualTo(404);
        assertThat(call(port, "DELETE", "/carrito/items/101", other, null).statusCode()).isEqualTo(404);
        var foreign = EntraTestTokens.token(Map.of("tid", "66666666-6666-6666-6666-666666666666"));
        for (String method : List.of("GET", "DELETE")) assertThat(call(port, method, "/carrito", foreign, null).statusCode()).isEqualTo(401);
        assertThat(cart()).isEqualTo(before);
    }
    @Test void authenticationAndRolesStillPrecedeConflictBothRoutes() throws Exception {
        seed(); var before = cart();
        for (int target : new int[] {port, bffPort}) {
            assertThat(call(target, "DELETE", "/carrito", null, null).statusCode()).isEqualTo(401);
            assertThat(call(target, "DELETE", "/carrito", "invalid", null).statusCode()).isEqualTo(401);
            assertThat(call(target, "DELETE", "/carrito", EntraTestTokens.token(Map.of("roles", List.of("RESTAURANTE"))), null).statusCode()).isEqualTo(403);
            assertThat(call(target, "DELETE", "/carrito", EntraTestTokens.token(Map.of("scp", "other")), null).statusCode()).isEqualTo(403);
        }
        assertThat(cart()).isEqualTo(before);
    }
}
