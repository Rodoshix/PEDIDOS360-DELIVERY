package cl.duoc.pedidos360.pagos;

import cl.duoc.pedidos360.pagos.security.EntraTestTokens;
import cl.duoc.pedidos360.pagos.entity.*;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Five production applications, real listeners, PostgreSQL and RabbitMQ.
 * Only Entra and broker/DB provisioning are local fixtures; no remote response fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BffQueriesEndToEndTests {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String OTHER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    final RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:4.1.8-management-alpine");
    final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    Path root;
    URLClassLoader loader;
    ConfigurableApplicationContext usuarios, restaurantes, pagos, httpBff, rabbitBff;
    long own, foreign, external;

    @TestConfiguration(proxyBeanMethods=false) static class Keys {
        @Bean @Primary @org.springframework.beans.factory.annotation.Qualifier("entraDelegadoDecoder")
        JwtDecoder testDecoder() { return EntraTestTokens.decoder(); }
    }

    @BeforeAll void prepare() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("backend/bff/pom.xml"))) root = root.getParent();
        var output = root.resolve("backend/services/pagos-service/target/bff-queries-e2e-classes");
        Files.createDirectories(output);
        var sources = new ArrayList<Path>();
        for (String module : List.of("bff", "services/usuarios-service", "services/restaurantes-service", "services/productos-service")) {
            try (var files = Files.walk(root.resolve("backend/" + module + "/src/main/java"))) {
                sources.addAll(files.filter(p -> p.toString().endsWith(".java")).toList());
            }
        }
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            assertThat(compiler.getTask(null, manager, null, List.of("--release", "21", "-parameters", "-classpath",
                    System.getProperty("java.class.path"), "-d", output.toString()), null,
                    manager.getJavaFileObjectsFromPaths(sources)).call()).isTrue();
        }
        loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
        postgres.start(); broker.start();
        var cf = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(broker.getHost(), broker.getAmqpPort());
        try {
            cf.setUsername(broker.getAdminUsername()); cf.setPassword(broker.getAdminPassword());
            var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(cf);
            for (String exchange : List.of("p360.queries", "p360.retry", "p360.dlx"))
                admin.declareExchange(new org.springframework.amqp.core.DirectExchange(exchange, true, false));
            admin.declareQueue(new org.springframework.amqp.core.Queue("p360.bff.consultas.respuestas.q", true));
            for (var route : Map.of("usuarios", "usuario.consultar-actual", "restaurantes", "restaurante.listar",
                    "productos", "producto.listar-disponibles", "pagos", "pago.consultar").entrySet()) {
                String q = "p360." + route.getKey() + ".consultas.q";
                admin.declareQueue(new org.springframework.amqp.core.Queue(q, true));
                admin.declareQueue(new org.springframework.amqp.core.Queue("p360." + route.getKey() + ".consultas.dlq", true));
                admin.declareBinding(new org.springframework.amqp.core.Binding(q, org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                        "p360.queries", route.getValue() + ".v1", Map.of()));
                admin.declareBinding(new org.springframework.amqp.core.Binding("p360." + route.getKey() + ".consultas.dlq",
                        org.springframework.amqp.core.Binding.DestinationType.QUEUE, "p360.dlx", route.getValue() + ".failed", Map.of()));
            }
        } finally { cf.destroy(); }
        // Separate disposable query account; no changes to operational permissions.
        assertThat(broker.execInContainer("rabbitmqctl", "add_user", "queries", "test-only").getExitCode()).isZero();
        assertThat(broker.execInContainer("rabbitmqctl", "set_permissions", "-p", "/", "queries", "^$",
                "^(amq\\.default|p360\\.retry|p360\\.dlx)$", "^p360\\.pagos\\.consultas\\.q$").getExitCode()).isZero();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("pagos").defaultSchema("pagos").locations(migrations("pagos")).target("1").load().migrate();
        try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); var s = c.createStatement()) {
            s.execute("INSERT INTO pagos.pagos(id,pedido_id,usuario_id,monto,moneda,metodo,estado,clave_idempotencia) VALUES(900,900,10,1000,'CLP','EFECTIVO','PENDIENTE','legacy')");
        }
        usuarios = start("usuarios", true, Map.of());
        restaurantes = start("restaurantes", false, Map.of()); start("productos", false, Map.of());
        pagos = start("pagos", true, Map.of("entra.usuarios-url", url(usuarios)));
        var jdbc = usuarios.getBean(JdbcTemplate.class);
        jdbc.update("INSERT INTO usuarios.usuarios(id,tenant_id,entra_object_id,nombre,apellido,email) VALUES(10,?::uuid,?::uuid,'Test','User','local@example.test')", EntraTestTokens.TENANT, EntraTestTokens.USER);
        var repo = pagos.getBean(PagoRepository.class);
        own = repo.saveAndFlush(new Pago(UUID.fromString(EntraTestTokens.TENANT), 101L, 10L, 1000L, "CLP", MetodoPago.EFECTIVO, EstadoPago.PENDIENTE, "own")).getId();
        foreign = repo.saveAndFlush(new Pago(UUID.fromString(EntraTestTokens.TENANT), 102L, 20L, 1000L, "CLP", MetodoPago.EFECTIVO, EstadoPago.PENDIENTE, "foreign")).getId();
        external = repo.saveAndFlush(new Pago(UUID.fromString(OTHER), 103L, 10L, 1000L, "CLP", MetodoPago.EFECTIVO, EstadoPago.PENDIENTE, "external")).getId();
        httpBff = start("bff", true, Map.of("pedidos360.messaging.relay-mode", "DISABLED"));
        rabbitBff = start("bff", true, Map.of("bff.queries.usuarios", "RABBITMQ", "bff.queries.restaurantes", "RABBITMQ",
                "bff.queries.productos", "RABBITMQ", "bff.queries.pagos", "RABBITMQ"));
    }
    String migrations(String service) { return "filesystem:" + root.resolve("backend/services/" + service + "-service/src/main/resources/db/migration").toString().replace('\\', '/'); }
    ConfigurableApplicationContext start(String service, boolean entra, Map<String,String> overrides) throws Exception {
        var p = new LinkedHashMap<String,String>();
        String module = service.equals("bff") ? "backend/bff" : "backend/services/" + service + "-service";
        p.put("spring.config.location", "file:" + root.resolve(module + "/src/main/resources/application.yml").toString().replace('\\','/'));
        p.put("spring.config.import", ""); p.put("spring.profiles.active", "test-production");
        p.put("server.port", "0"); p.put("server.address", "127.0.0.1"); p.put("logging.level.root", "ERROR");
        p.put("logging.level.cl.duoc.pedidos360.messaging", "INFO");
        p.put("spring.datasource.url", postgres.getJdbcUrl()); p.put("spring.datasource.username", postgres.getUsername()); p.put("spring.datasource.password", postgres.getPassword());
        p.put("spring.flyway.locations", migrations(service)); p.put("spring.flyway.schemas", service); p.put("spring.flyway.default-schema", service);
        p.put("spring.jpa.properties.hibernate.default_schema", service);
        // Catalog production POMs exclude Spring Security; the hosting Pagos test classpath includes it.
        if(service.equals("restaurantes") || service.equals("productos")) p.put("spring.autoconfigure.exclude", "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration");
        if(service.equals("bff")) p.put("spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
        p.put("entra.enabled", Boolean.toString(entra)); p.put("entra.tenant-id", EntraTestTokens.TENANT); p.put("entra.api-client-id", EntraTestTokens.API); p.put("entra.frontend-client-id", EntraTestTokens.FRONTEND);
        p.put("spring.rabbitmq.host", broker.getHost()); p.put("spring.rabbitmq.port", broker.getAmqpPort().toString()); p.put("spring.rabbitmq.virtual-host", "/");
        p.put("spring.rabbitmq.username", broker.getAdminUsername()); p.put("spring.rabbitmq.password", broker.getAdminPassword()); p.put("spring.rabbitmq.dynamic", "false");
        p.put("pedidos360.messaging.relay-mode", "ACTIVE"); p.put("pedidos360.messaging.role", service.equals("bff") ? "BFF" : "SERVICE");
        p.put("pedidos360.messaging.actor.emisor", EntraTestTokens.TENANT); p.put("pedidos360.messaging.actor.public-jwks", PagosQueryKeys.actors());
        p.put("pedidos360.messaging.identity-proof.enabled", Boolean.toString(service.equals("bff") || service.equals("usuarios") || service.equals("pagos")));
        p.put("pedidos360.messaging.identity-proof.public-jwks", PagosQueryKeys.proofs());
        if (service.equals("usuarios")) { p.put("pedidos360.messaging.identity-proof.private-jwk", PagosQueryKeys.PROOF.toJSONString()); p.put("pedidos360.messaging.identity-proof.key-id", PagosQueryKeys.PROOF_ID.toString()); }
        if (service.equals("pagos")) {
            p.put("pagos.consultas.rabbitmq.host", broker.getHost()); p.put("pagos.consultas.rabbitmq.port", broker.getAmqpPort().toString()); p.put("pagos.consultas.rabbitmq.virtual-host", "/"); p.put("pagos.consultas.rabbitmq.username", "queries"); p.put("pagos.consultas.rabbitmq.password", "test-only");
        }
        if (service.equals("bff")) {
            p.put("pedidos360.bff.actor.emisor", EntraTestTokens.TENANT);
            p.put("pedidos360.messaging.actor.private-jwk", PagosQueryKeys.ACTOR.toJSONString()); p.put("pedidos360.messaging.actor.clave-id", PagosQueryKeys.ACTOR_ID.toString()); p.put("bff.pedidos-pagos-enabled", "true");
            for (var ctx : contexts) { String name = ctx.getEnvironment().getProperty("spring.application.name");
                if (name != null && name.endsWith("-service")) p.put("bff." + name.substring(0, name.length()-8) + "-url", url(ctx)); }
        }
        p.putAll(overrides);
        Class<?> app = service.equals("pagos") ? PagosApplication.class : loader.loadClass("cl.duoc.pedidos360." + service + "." + switch(service) {
            case "bff" -> "BffApplication"; case "usuarios" -> "UsuariosApplication"; case "restaurantes" -> "RestaurantesApplication"; case "productos" -> "ProductosApplication"; default -> throw new IllegalStateException(); });
        var original = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
        try {
            var b = new SpringApplicationBuilder(app).web(WebApplicationType.SERVLET); if (entra) b.sources(Keys.class);
            var ctx = b.run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new)); contexts.add(ctx);
            if(!service.equals("bff")) {
                assertThat(ctx.getBean(cl.duoc.pedidos360.messaging.relay.QueryConsumer.class)).isNotNull();
                assertThat(ctx.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class).getListenerContainers()).hasSize(1);
            }
            return ctx;
        } finally { Thread.currentThread().setContextClassLoader(original); }
    }
    static String url(ConfigurableApplicationContext ctx) { return "http://127.0.0.1:" + ((WebServerApplicationContext) ctx).getWebServer().getPort(); }
    HttpResponse<String> get(ConfigurableApplicationContext ctx, String path, boolean admin) throws Exception {
        try(var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(url(ctx) + path)).header("Authorization", "Bearer " + EntraTestTokens.token(admin ? Map.of("roles", List.of("ADMIN")) : Map.of())).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @ParameterizedTest @ValueSource(strings={"/usuarios/me", "/restaurantes", "/productos/restaurante/1/disponibles", "/productos/restaurante/99999/disponibles"})
    void equivalentPublicBodies(String path) throws Exception {
        var http = get(httpBff, path, false); var rabbit = get(rabbitBff, path, false);
        assertThat(http.statusCode()).isEqualTo(200); assertThat(rabbit.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(rabbit.body())).isEqualTo(JSON.readTree(http.body()));
        assertThat(rabbit.body()).doesNotContain("pruebaIdentidad", "tenantId", "entraObjectId");
        if(path.equals("/restaurantes") || path.equals("/productos/restaurante/1/disponibles"))
            assertThat(JSON.readTree(rabbit.body()).size()).isGreaterThan(0);
        if(path.contains("99999")) assertThat(JSON.readTree(rabbit.body()).isEmpty()).isTrue();
    }
    @Test void emptyRestaurantListHasSameHttpAndRabbitContract() throws Exception {
        var jdbc=restaurantes.getBean(JdbcTemplate.class);
        var original=jdbc.queryForList("SELECT * FROM restaurantes.restaurantes");
        try {
            jdbc.update("DELETE FROM restaurantes.restaurantes");
            var http=get(httpBff,"/restaurantes",false); var rabbit=get(rabbitBff,"/restaurantes",false);
            assertThat(http.statusCode()).isEqualTo(200); assertThat(rabbit.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(http.body()).isEmpty()).isTrue(); assertThat(JSON.readTree(rabbit.body())).isEqualTo(JSON.readTree(http.body()));
        } finally {
            for(var row:original) jdbc.update("INSERT INTO restaurantes.restaurantes(id,nombre,descripcion,direccion,estado) VALUES(?,?,?,?,?)",
                    row.get("id"),row.get("nombre"),row.get("descripcion"),row.get("direccion"),row.get("estado"));
        }
    }
    @Test void paymentOwnerAndAdminUseActualUsuariosProofAndPagoService() throws Exception {
        for (boolean admin : List.of(false, true)) {
            var http = get(httpBff, "/pagos/" + own, admin); var rabbit = get(rabbitBff, "/pagos/" + own, admin);
            assertThat(rabbit.statusCode()).isEqualTo(200); assertThat(JSON.readTree(rabbit.body())).isEqualTo(JSON.readTree(http.body()));
            assertThat(JSON.readTree(rabbit.body()).size()).isEqualTo(8);
        }
        assertThat(get(rabbitBff, "/pagos/" + foreign, false).statusCode()).isEqualTo(403);
        assertThat(get(rabbitBff, "/pagos/" + foreign, true).statusCode()).isEqualTo(200);
        assertThat(pagos.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM pagos.confirmacion_outbox", Long.class)).isZero();
    }
    @Test void externalMissingAndUnknownRemain404ForClientAndAdmin() throws Exception {
        for (boolean admin : List.of(false, true)) for (long id : List.of(external, 900L, Long.MAX_VALUE)) {
            assertThat(get(rabbitBff, "/pagos/" + id, admin).statusCode()).isEqualTo(404);
            assertThat(get(httpBff, "/pagos/" + id, admin).statusCode()).isEqualTo(404);
        }
    }
    @Test void inactiveAndMissingProfileNeverReachPayments() throws Exception {
        var jdbc = usuarios.getBean(JdbcTemplate.class);
        try {
            jdbc.update("UPDATE usuarios.usuarios SET activo=false WHERE id=10");
            assertThat(get(rabbitBff, "/pagos/" + own, false).statusCode()).isEqualTo(403);
            jdbc.update("DELETE FROM usuarios.usuarios WHERE id=10");
            assertThat(get(rabbitBff, "/pagos/" + own, false).statusCode()).isEqualTo(404);
        } finally {
            jdbc.update("INSERT INTO usuarios.usuarios(id,tenant_id,entra_object_id,nombre,apellido,email) VALUES(10,?::uuid,?::uuid,'Test','User','local@example.test') ON CONFLICT(id) DO UPDATE SET activo=true", EntraTestTokens.TENANT, EntraTestTokens.USER);
        }
    }
    @AfterAll void stop() throws Exception {
        Collections.reverse(contexts); contexts.forEach(ConfigurableApplicationContext::close);
        if(loader!=null)loader.close(); broker.close(); postgres.close();
    }
}
