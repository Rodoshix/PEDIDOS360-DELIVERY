package cl.duoc.pedidos360.pagos;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import cl.duoc.pedidos360.pagos.security.EntraTestTokens;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reuses the five-application fixture and its independent query assertions.
 * Adds real Pedidos/Carrito. Entra signing authority and provisioning remain fixtures.
 * No browser, HTTP response stubs, or changes to operational infrastructure. */
class PurchaseRabbitIntegrationTests extends BffQueriesEndToEndTests {
    ConfigurableApplicationContext carrito, pedidos;
    String paymentPort;

    @BeforeAll void addPurchaseServices() throws Exception {
        var sources = new ArrayList<Path>();
        for (String service : List.of("pedidos", "carrito")) try (var files = Files.walk(root.resolve("backend/services/" + service + "-service/src/main/java"))) {
            sources.addAll(files.filter(p -> p.toString().endsWith(".java")).toList());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            assertThat(compiler.getTask(null, manager, null, List.of("--release", "21", "-parameters", "-classpath",
                    System.getProperty("java.class.path"), "-d", root.resolve("backend/services/pagos-service/target/bff-queries-e2e-classes").toString()), null,
                    manager.getJavaFileObjectsFromPaths(sources)).call()).isTrue();
        }
        for (String user : List.of("p360-pedidos-carrito-publisher", "p360-carrito-consumer")) {
            assertThat(broker.execInContainer("rabbitmqctl", "add_user", user, "test-only").getExitCode()).isZero();
            assertThat(broker.execInContainer("rabbitmqctl", "set_permissions", "-p", "/", user, "^$",
                user.endsWith("publisher") ? "^p360\\.commands$" : "^(p360\\.retry|p360\\.dlx)$",
                user.endsWith("publisher") ? "^$" : "^p360\\.carrito\\.vaciado\\.q$").getExitCode()).isZero();
        }
        var cf = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(broker.getHost(), broker.getAmqpPort());
        try {
            cf.setUsername(broker.getAdminUsername()); cf.setPassword(broker.getAdminPassword());
            var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(cf);
            admin.declareExchange(new DirectExchange("p360.pedidos.commands"));
            admin.declareQueue(new org.springframework.amqp.core.Queue("p360.pedidos.confirmacion.q", true));
            admin.declareBinding(new Binding("p360.pedidos.confirmacion.q", Binding.DestinationType.QUEUE, "p360.pedidos.commands", "pedido.confirmar.v1", null));
            admin.declareExchange(new DirectExchange("p360.commands"));
            admin.declareQueue(new org.springframework.amqp.core.Queue("p360.carrito.vaciado.q", true));
            admin.declareBinding(new Binding("p360.carrito.vaciado.q", Binding.DestinationType.QUEUE, "p360.commands", "carrito.vaciar-por-pedido.v1", null));
        } finally { cf.destroy(); }
        carrito = startPurchase("carrito"); pedidos = startPurchase("pedidos");
        for (var old : List.of(httpBff, rabbitBff, pagos)) { old.close(); contexts.remove(old); }
        pagos = paymentService();
        httpBff = start("bff", true, Map.of("pedidos360.messaging.relay-mode", "DISABLED"));
        rabbitBff = start("bff", true, Map.of("bff.queries.usuarios", "RABBITMQ", "bff.queries.restaurantes", "RABBITMQ",
                "bff.queries.productos", "RABBITMQ", "bff.queries.pagos", "RABBITMQ"));
    }

    ConfigurableApplicationContext paymentService() throws Exception {
        var settings = new LinkedHashMap<String,String>(Map.of("entra.usuarios-url", url(usuarios), "pagos.pedidos.base-url", url(pedidos),
            "pagos.reconciliacion.enabled", "false", "pedidos360.messaging.coordination-mode", "RABBITMQ",
            "pedidos360.messaging.dispatch-interval-ms", "3600000"));
        if (paymentPort != null) settings.put("server.port", paymentPort);
        var ctx = start("pagos", true, settings);
        paymentPort = Integer.toString(((org.springframework.boot.web.server.context.WebServerApplicationContext)ctx).getWebServer().getPort());
        return ctx;
    }

    ConfigurableApplicationContext startPurchase(String service) throws Exception {
        var p = new LinkedHashMap<String,String>();
        p.put("spring.config.location", "file:" + root.resolve("backend/services/" + service + "-service/src/main/resources/application.yml").toString().replace('\\','/'));
        p.put("spring.config.import", ""); p.put("spring.profiles.active", "test-production");
        p.put("server.port", "0"); p.put("server.address", "127.0.0.1"); p.put("logging.level.root", "ERROR");
        p.put("spring.datasource.url", postgres.getJdbcUrl()); p.put("spring.datasource.username", postgres.getUsername()); p.put("spring.datasource.password", postgres.getPassword());
        p.put("spring.flyway.locations", migrations(service)); p.put("spring.flyway.schemas", service); p.put("spring.flyway.default-schema", service);
        p.put("spring.jpa.properties.hibernate.default_schema", service);
        p.put("entra.enabled", "true"); p.put("entra.tenant-id", EntraTestTokens.TENANT); p.put("entra.api-client-id", EntraTestTokens.API); p.put("entra.frontend-client-id", EntraTestTokens.FRONTEND);
        p.put("entra.usuarios-url", url(usuarios));
        p.put(service + ".productos-url", url(contexts.stream().filter(c -> "productos-service".equals(c.getEnvironment().getProperty("spring.application.name"))).findFirst().orElseThrow()));
        p.put("carrito.catalogo-http-enabled", "true"); if (service.equals("pedidos")) p.put("pedidos.carrito-url", url(carrito));
        p.put("spring.rabbitmq.host", broker.getHost()); p.put("spring.rabbitmq.port", broker.getAmqpPort().toString()); p.put("spring.rabbitmq.virtual-host", "/");
        p.put("spring.rabbitmq.username", broker.getAdminUsername()); p.put("spring.rabbitmq.password", broker.getAdminPassword()); p.put("spring.rabbitmq.dynamic", "false");
        p.put("pedidos360.messaging.coordination-mode", service.equals("pedidos") ? "RABBITMQ" : "HTTP");
        p.put("pedidos360.messaging.reliability.platform-ready", "true");
        p.put("pedidos360.messaging.carrito.mode", "RABBITMQ"); p.put("pedidos360.messaging.carrito.platform-ready", "true");
        p.put("pedidos360.messaging.carrito.host", broker.getHost()); p.put("pedidos360.messaging.carrito.port", broker.getAmqpPort().toString()); p.put("pedidos360.messaging.carrito.virtual-host", "/");
        p.put("pedidos360.messaging.carrito.username", service.equals("pedidos") ? "p360-pedidos-carrito-publisher" : "p360-carrito-consumer"); p.put("pedidos360.messaging.carrito.password", "test-only");
        p.put("pedidos360.messaging.carrito.dispatch-interval-ms", "3600000");
        var original = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
        try {
            var app = loader.loadClass("cl.duoc.pedidos360." + service + "." + (service.equals("pedidos") ? "PedidosApplication" : "CarritoApplication"));
            var ctx = new SpringApplicationBuilder(app).sources(Keys.class).web(WebApplicationType.SERVLET)
                .run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
            contexts.add(ctx); return ctx;
        } finally { Thread.currentThread().setContextClassLoader(original); }
    }

    HttpResponse<String> write(String path, String body, String key) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var b = HttpRequest.newBuilder(URI.create(url(rabbitBff) + path)).header("Authorization", "Bearer " + EntraTestTokens.token(Map.of()))
                .header("Content-Type", "application/json");
            if (key != null) b.header("Idempotency-Key", key);
            return client.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    void dispatchCart() throws Exception { var bean = pedidos.getBean("carritoOutboxDispatcher"); bean.getClass().getMethod("dispatch").invoke(bean); }

    @ParameterizedTest @ValueSource(strings={"TARJETA", "EFECTIVO"})
    void purchaseCommitsBothOutboxesAndRecoversStoppedConsumer(String method) throws Exception {
        var cartDb = carrito.getBean(JdbcTemplate.class); var orderDb = pedidos.getBean(JdbcTemplate.class);
        var paymentDb = pagos.getBean(JdbcTemplate.class);
        var added = write("/carrito/items", "{\"productoId\":1,\"cantidad\":1}", null);
        assertThat(added.statusCode()).isEqualTo(200);
        var created = write("/pedidos", "{\"restauranteId\":1,\"direccionEntrega\":\"Local integration\",\"items\":[{\"productoId\":1,\"cantidad\":1}]}", null);
        assertThat(created.statusCode()).isEqualTo(201);
        long order = JSON.readTree(created.body()).path("pedidoId").longValue();
        assertThat(orderDb.queryForObject("SELECT count(*) FROM pedidos.carrito_vaciado_outbox WHERE pedido_id=?", Long.class, order)).isEqualTo(1);
        dispatchCart();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(cartDb.queryForObject("SELECT estado FROM carrito.vaciado_por_pedido WHERE pedido_id=?", String.class, order)).isEqualTo("EMPTIED"));
        assertThat(JSON.readTree(get(rabbitBff, "/carrito", false).body()).path("items").size()).isZero();
        var listener = pedidos.getBean(RabbitListenerEndpointRegistry.class).getListenerContainer("pedidoConfirmacionListener");
        listener.stop();
        long payment = 0;
        try {
            var paid = write("/pagos", "{\"pedidoId\":" + order + ",\"metodo\":\"" + method + "\"}", "purchase-" + order);
            assertThat(paid.statusCode()).isEqualTo(201); payment = JSON.readTree(paid.body()).path("pagoId").longValue();
            assertThat(JSON.readTree(paid.body()).path("estado").stringValue()).isEqualTo(method.equals("TARJETA") ? "APROBADO" : "PENDIENTE");
            UUID id = paymentDb.queryForObject("SELECT message_id FROM pagos.confirmacion_outbox WHERE pago_id=?", UUID.class, payment);
            // Restart application contexts with persistent disposable DB/broker, before publishing.
            pagos.close(); contexts.remove(pagos); pagos = paymentService();
            assertThat(pagos.getBean(cl.duoc.pedidos360.pagos.messaging.RabbitProperties.class).batchSize()).isEqualTo(10);
            assertThat(pagos.getBean(cl.duoc.pedidos360.pagos.security.TenantSistema.class).obtener().toString()).isEqualTo(EntraTestTokens.TENANT);
            pagos.getBean(cl.duoc.pedidos360.pagos.messaging.OutboxDispatcher.class).dispatch();
            // The initial scheduled dispatch can hold the row: manual dispatch correctly uses SKIP LOCKED.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pagos.getBean(JdbcTemplate.class).queryForMap("SELECT o.estado,o.last_error,o.attempts,o.next_attempt_at,clock_timestamp() AS db_now,p.tenant_id,p.tenant_origin,p.coordinacion FROM pagos.confirmacion_outbox o JOIN pagos.pagos p ON p.id=o.pago_id WHERE o.message_id=?", id)).containsEntry("estado", "PUBLISHED"));
            assertThat(orderDb.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id=?", String.class, order)).isEqualTo("CREADO");
            listener.start();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(orderDb.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id=?", String.class, order)).isEqualTo("CONFIRMADO"));
            assertThat(pagos.getBean(cl.duoc.pedidos360.pagos.service.PagoService.class).reconciliarConfirmacionesPendientes()).isZero();
        } finally {
            listener.start();
            // Keep inherited query assertions independent of the purchase cases.
            if (payment > 0) { var db = pagos.getBean(JdbcTemplate.class); db.update("DELETE FROM pagos.confirmacion_outbox WHERE pago_id=?", payment); db.update("DELETE FROM pagos.pagos WHERE id=?", payment); }
        }
    }
}
