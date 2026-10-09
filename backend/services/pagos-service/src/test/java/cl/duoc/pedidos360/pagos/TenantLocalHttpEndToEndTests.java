package cl.duoc.pedidos360.pagos;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.tools.ToolProvider;
import cl.duoc.pedidos360.pagos.security.EntraTestTokens;
import org.junit.jupiter.api.*;
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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Two production HTTP applications + real disposable PostgreSQL. Only Entra keys
 * and Usuarios are fixtures; local-flow tests use no JWT or upstream HTTP stub. */
class TenantLocalHttpEndToEndTests {
    static final String T=EntraTestTokens.TENANT;
    static final String OTHER="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static PostgreSQLContainer postgres;
    static Path root;
    static URLClassLoader pedidosLoader;
    static Class<?> pedidosApplication;
    static com.sun.net.httpserver.HttpServer usuarios;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static ClassLoader originalLoader;

    @BeforeAll static void prepare() throws Exception {
        root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isRegularFile(root.resolve("backend/services/pedidos-service/pom.xml"))) root=root.getParent();
        if(root==null) throw new IllegalStateException("Repository root unavailable");
        // Compile the actual sibling source into ignored test output, avoiding a
        // production service dependency or reliance on a previously built jar.
        Path output=root.resolve("backend/services/pagos-service/target/pedidos-e2e-classes");
        Files.createDirectories(output);
        var compiler=Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(),"Tests require JDK 21");
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8);
            var files=Files.walk(root.resolve("backend/services/pedidos-service/src/main/java"))) {
            var sources=manager.getJavaFileObjectsFromPaths(files.filter(p->p.toString().endsWith(".java")).toList());
            assertThat(compiler.getTask(null,manager,null,List.of("--release","21","-parameters","-classpath",System.getProperty("java.class.path"),"-d",output.toString()),null,sources).call()).isTrue();
        }
        originalLoader=Thread.currentThread().getContextClassLoader();
        pedidosLoader=new URLClassLoader(new URL[]{output.toUri().toURL()},originalLoader);
        pedidosApplication=pedidosLoader.loadClass("cl.duoc.pedidos360.pedidos.PedidosApplication");
        postgres=new PostgreSQLContainer("postgres:17-alpine");postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
            .schemas("pedidos").defaultSchema("pedidos").locations(migrations("pedidos")).target("1").load().migrate();
        try(var c=connection();var sql=c.createStatement()) {
            sql.execute("INSERT INTO pedidos.pedidos(id,usuario_id,restaurante_id,direccion_entrega,estado,total,moneda) VALUES(900,10,20,'historical','CREADO',13980,'CLP')");
        }
        usuarios=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        usuarios.createContext("/usuarios/me",exchange->{
            try(exchange) {
                var jwt=EntraTestTokens.decoder().decode(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                long id=EntraTestTokens.USER.equals(jwt.getClaimAsString("oid"))?10:20;
                byte[] body=("{\"id\":"+id+",\"activo\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }
        });usuarios.start();
    }
    @AfterAll static void stop() throws Exception {
        Thread.currentThread().setContextClassLoader(originalLoader);
        if(usuarios!=null)usuarios.stop(0);
        if(postgres!=null)postgres.close();
        if(pedidosLoader!=null)pedidosLoader.close();
    }
    static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
    }
    @BeforeEach void clearPayments() throws Exception {
        try(var c=connection();var sql=c.createStatement();var rows=sql.executeQuery("SELECT to_regclass('pagos.pagos')")) {
            rows.next();if(rows.getObject(1)!=null) {
                try(var clean=c.createStatement()) {clean.execute("TRUNCATE pagos.tenant_reconciliation_audit,pagos.confirmacion_outbox,pagos.pagos RESTART IDENTITY CASCADE");}
            }
        }
    }
    static String migrations(String service) {
        return "filesystem:"+root.resolve("backend/services/"+service+"-service/src/main/resources/db/migration").toString().replace('\\','/');
    }
    ConfigurableApplicationContext start(String service,String tenant,boolean local,boolean entra,String coordination,String upstream,Map<String,String> overrides) {
        var props=new LinkedHashMap<String,String>();
        props.put("server.port","0");props.put("server.address","127.0.0.1");
        props.put("spring.profiles.active",local?"local":"test-production");props.put("spring.config.import","");
        props.put("spring.datasource.url",postgres.getJdbcUrl());props.put("spring.datasource.username",postgres.getUsername());props.put("spring.datasource.password",postgres.getPassword());
        props.put("spring.flyway.locations",migrations(service));props.put("spring.flyway.default-schema",service);props.put("spring.flyway.schemas",service);
        props.put("spring.jpa.properties.hibernate.default_schema",service);
        props.put("entra.enabled",Boolean.toString(entra));props.put("entra.tenant-id",tenant);
        props.put("entra.api-client-id",EntraTestTokens.API);props.put("entra.frontend-client-id",EntraTestTokens.FRONTEND);
        props.put("entra.usuarios-url","http://127.0.0.1:"+usuarios.getAddress().getPort());
        props.put(service+".identidad-local.enabled",Boolean.toString(local));props.put(service+".identidad-local.tenant-id",tenant);
        props.put(service+".identidad-local.usuario-id","10");props.put(service+".identidad-local.roles","CLIENTE");
        props.put("pedidos.interno.enabled","false");props.put("pagos.worker.enabled","false");props.put("pagos.reconciliacion.enabled","false");
        props.put("pagos.pedidos.interno-habilitado","false");props.put("pagos.pedidos.base-url",upstream);
        props.put("pedidos360.messaging.coordination-mode",coordination);props.put("pedidos360.messaging.dispatch-interval-ms","3600000");
        props.put("spring.rabbitmq.host","127.0.0.1");props.put("spring.rabbitmq.port","1");props.put("spring.rabbitmq.dynamic","false");
        props.put("logging.level.root","ERROR");props.putAll(overrides);
        var builder=new SpringApplicationBuilder(service.equals("pedidos")?pedidosApplication:PagosApplication.class).web(WebApplicationType.SERVLET);
        if(entra)builder.sources(Keys.class);
        Thread.currentThread().setContextClassLoader(pedidosLoader);
        try {return builder.run(props.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new));}
        finally {Thread.currentThread().setContextClassLoader(originalLoader);}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Keys {
        @Bean @Primary @org.springframework.beans.factory.annotation.Qualifier("entraDelegadoDecoder")
        JwtDecoder fixtureDelegatedDecoder() {return EntraTestTokens.decoder();}
    }
    String url(ConfigurableApplicationContext ctx) {
        return "http://127.0.0.1:"+((WebServerApplicationContext)ctx).getWebServer().getPort();
    }
    long pedido(ConfigurableApplicationContext ctx,String tenant) {
        return ctx.getBean(JdbcTemplate.class).queryForObject("INSERT INTO pedidos.pedidos(usuario_id,restaurante_id,direccion_entrega,estado,total,moneda,tenant_id,tenant_origin) VALUES(10,20,'test','CREADO',13980,'CLP',?::uuid,'AUTHENTICATED_NEW') RETURNING id",Long.class,tenant);
    }
    HttpResponse<String> request(String base,String method,String path,String token,String body) throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            var b=HttpRequest.newBuilder(URI.create(base+path)).header("X-User-Id","1").header("X-Roles","ADMIN").header("X-Tenant",OTHER);
            if(token!=null)b.header("Authorization","Bearer "+token);
            if(body!=null)b.header("Content-Type","application/json");
            return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    HttpResponse<String> register(ConfigurableApplicationContext ctx,long id,String token) throws Exception {
        return request(url(ctx),"POST","/pagos",token,"{\"pedidoId\":"+id+",\"metodo\":\"EFECTIVO\"}");
    }
    void flow(String coordination) throws Exception {
        try(var p=start("pedidos",T,true,false,"HTTP","http://127.0.0.1:1",Map.of())) {
            long id=pedido(p,T);
            try(var q=start("pagos",T,true,false,coordination,url(p),Map.of())) {
                var summary=request(url(p),"GET","/internal/pedidos/"+id+"/resumen-pago",null,null);
                assertThat(summary.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(summary.body()).size()).isEqualTo(7);
                assertThat(summary.headers().firstValue("cache-control")).contains("no-store");
                assertThat(register(q,id,null).statusCode()).isEqualTo(201);
                var jdbc=q.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pagos.pagos WHERE tenant_id=?::uuid AND usuario_id=10",Long.class,T)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pagos.confirmacion_outbox",Long.class)).isEqualTo(coordination.equals("RABBITMQ")?1:0);
            }
        }
    }
    @Test void localHttpRegistersPaymentWithExactProjection() throws Exception {flow("HTTP");}
    @Test void localRabbitCoordinationPersistsAtomicIntentWithoutBrokerActivation() throws Exception {flow("RABBITMQ");}
    @Test void discordantLocalTenantRejectedBeforePersistence() throws Exception {
        try(var p=start("pedidos",OTHER,true,false,"HTTP","http://127.0.0.1:1",Map.of())) {
            long id=pedido(p,OTHER);
            try(var q=start("pagos",T,true,false,"HTTP",url(p),Map.of())) {
                assertThat(register(q,id,null).statusCode()).isEqualTo(404);
                assertThat(q.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM pagos.pagos",Long.class)).isZero();
            }
        }
    }
    @Test void unknownAndForeignResourcesNotExposedOrPaid() throws Exception {
        try(var p=start("pedidos",T,true,false,"HTTP","http://127.0.0.1:1",Map.of())) {
            long foreign=pedido(p,OTHER);
            try(var q=start("pagos",T,true,false,"HTTP",url(p),Map.of())) {
                for(long id:List.of(900L,foreign,987654L)) {
                    assertThat(request(url(p),"GET","/internal/pedidos/"+id+"/resumen-pago",null,null).statusCode()).isEqualTo(404);
                    assertThat(register(q,id,null).statusCode()).isEqualTo(404);
                }
                assertThat(q.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM pagos.pagos",Long.class)).isZero();
            }
        }
    }
    @Test void absentLocalConfigurationCannotAuthenticateSpoofedHeaders() throws Exception {
        try(var q=start("pagos",T,false,false,"HTTP","http://127.0.0.1:1",Map.of())) {
            assertThat(register(q,1,null).statusCode()).isEqualTo(401);
            assertThat(q.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM pagos.pagos",Long.class)).isZero();
        }
    }
    @Test void localOutsideLoopbackAndLocalWithEntraFailAtStartup() {
        assertThatThrownBy(()->start("pagos",T,true,false,"HTTP","http://127.0.0.1:1",Map.of("server.address","0.0.0.0")))
            .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->start("pagos",T,true,true,"HTTP","http://127.0.0.1:1",Map.of()))
            .hasRootCauseInstanceOf(IllegalStateException.class);
    }
    @Test void productionValidJwtWorksAndInvalidJwtHasNoLocalFallback() throws Exception {
        try(var p=start("pedidos",T,false,true,"HTTP","http://127.0.0.1:1",Map.of())) {
            long id=pedido(p,T);
            try(var q=start("pagos",T,false,true,"HTTP",url(p),Map.of())) {
                for(String token:List.of("invalid",EntraTestTokens.token(Map.of("tid",OTHER))))
                    assertThat(register(q,id,token).statusCode()).isEqualTo(401);
                assertThat(register(q,id,null).statusCode()).isEqualTo(401);
                assertThat(q.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM pagos.pagos",Long.class)).isZero();
                assertThat(register(q,id,EntraTestTokens.token(Map.of())).statusCode()).isEqualTo(201);
                assertThat(q.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM pagos.pagos WHERE usuario_id=10",Long.class)).isEqualTo(1);
            }
        }
    }
    @Test void realBrokerCommandConfirmsOnlyPersistedPaymentOrderViaManualProcessor() throws Exception {
        try(var broker=new org.testcontainers.rabbitmq.RabbitMQContainer("rabbitmq:4.1-management-alpine");
            var p=start("pedidos",T,true,false,"HTTP","http://127.0.0.1:1",Map.of())) {
            broker.start();long original=pedido(p,T),other=pedido(p,T);
            try(var q=start("pagos",T,true,false,"RABBITMQ",url(p),Map.of())) {
                assertThat(register(q,original,null).statusCode()).isEqualTo(201);
                var store=q.getBean(cl.duoc.pedidos360.pagos.messaging.OutboxStore.class);
                var properties=q.getBean(cl.duoc.pedidos360.pagos.messaging.RabbitProperties.class);
                var cf=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(broker.getHost(),broker.getAmqpPort());
                try {
                    cf.setUsername(broker.getAdminUsername());cf.setPassword(broker.getAdminPassword());
                    cf.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
                    cf.setPublisherReturns(true);
                    var rabbit=new org.springframework.amqp.rabbit.core.RabbitTemplate(cf);
                    var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(cf);
                    var queue=new org.springframework.amqp.core.Queue("identity.integrity.test");
                    admin.declareExchange(new org.springframework.amqp.core.DirectExchange(properties.exchanges().commands()));
                    admin.declareQueue(queue);
                    admin.declareBinding(new org.springframework.amqp.core.Binding(queue.getName(),org.springframework.amqp.core.Binding.DestinationType.QUEUE,properties.exchanges().commands(),properties.routingKeys().confirmar(),null));
                    var publisher=new cl.duoc.pedidos360.pagos.messaging.PagoConfirmacionPublisher(rabbit,properties);
                    new cl.duoc.pedidos360.pagos.messaging.OutboxDispatcher(store,publisher,q.getBean(JsonMapper.class),properties).dispatch();
                    var delivered=rabbit.receive(queue.getName(),3000);assertThat(delivered).isNotNull();
                    var payload=JSON.readTree(delivered.getBody());assertThat(payload.size()).isEqualTo(6);
                    assertThat(payload.path("pedidoId").longValue()).isEqualTo(original);
                    var jdbc=q.getBean(JdbcTemplate.class);
                    assertThat(payload.path("pagoId").longValue()).isEqualTo(jdbc.queryForObject("SELECT id FROM pagos.pagos",Long.class));
                    assertThat(payload.path("messageId").stringValue()).isEqualTo(delivered.getMessageProperties().getMessageId());
                    // Actual processor/service transaction, invoked manually after
                    // real broker reception: this is not a listener/ACK test.
                    var processor=p.getBean("pedidoConfirmacionProcessor");
                    processor.getClass().getMethod("process",org.springframework.amqp.core.Message.class).invoke(processor,delivered);
                    var orders=p.getBean(JdbcTemplate.class);
                    assertThat(orders.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id=?",String.class,original)).isEqualTo("CONFIRMADO");
                    assertThat(orders.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id=?",String.class,other)).isEqualTo("CREADO");
                    assertThat(jdbc.queryForObject("SELECT estado FROM pagos.confirmacion_outbox",String.class)).isEqualTo("PUBLISHED");
                    assertThat(rabbit.receive(queue.getName(),100)).isNull();
                    long foreign=pedido(p,OTHER);
                    for(long invalidTarget:List.of(other,foreign)) {
                        long unpaid=pedido(p,T);
                        assertThat(register(q,unpaid,null).statusCode()).isEqualTo(201);
                        Long paymentId=jdbc.queryForObject("SELECT id FROM pagos.pagos WHERE pedido_id=?",Long.class,unpaid);
                        UUID messageId=jdbc.queryForObject("SELECT message_id FROM pagos.confirmacion_outbox WHERE pago_id=?",UUID.class,paymentId);
                        var corrupt=new cl.duoc.pedidos360.pagos.messaging.ConfirmarPedidoPorPago(messageId,"ConfirmarPedidoPorPago",1,java.time.Instant.now(),invalidTarget,paymentId);
                        // Owner-only disposable fixture models pre-existing corruption;
                        // runtime INSERT is tested separately and cannot introduce it.
                        jdbc.execute("ALTER TABLE pagos.confirmacion_outbox DISABLE TRIGGER guard_outbox_identity");
                        try {jdbc.update("UPDATE pagos.confirmacion_outbox SET payload=? WHERE message_id=?",JSON.writeValueAsString(corrupt),messageId);}
                        finally {jdbc.execute("ALTER TABLE pagos.confirmacion_outbox ENABLE TRIGGER guard_outbox_identity");}
                        new cl.duoc.pedidos360.pagos.messaging.OutboxDispatcher(store,publisher,q.getBean(JsonMapper.class),properties).dispatch();
                        assertThat(rabbit.receive(queue.getName(),100)).isNull();
                        assertThat(jdbc.queryForObject("SELECT estado FROM pagos.confirmacion_outbox WHERE message_id=?",String.class,messageId)).isEqualTo("BLOCKED");
                        assertThat(orders.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id=?",String.class,unpaid)).isEqualTo("CREADO");
                        assertThat(orders.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id=?",String.class,invalidTarget)).isEqualTo("CREADO");
                    }
                } finally {cf.destroy();}
            }
        }
    }
}
