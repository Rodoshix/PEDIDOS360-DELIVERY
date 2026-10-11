package cl.duoc.pedidos360.pedidos.messaging;

import cl.duoc.pedidos360.pedidos.PedidosApplication;
import java.nio.file.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Infrastructure probe against real application beans, not a replacement consumer. */
public class PlatformApplicationCheck {
    public static void main(String[] args) {
        int result = 0;
        try { run(args); }
        catch (Throwable failure) { failure.printStackTrace(); result = 1; }
        // Standalone probe owns its process; terminate after contexts/containers are closed.
        // Manually created AMQP publisher callback executors are outside Spring lifecycle.
        System.exit(result);
    }
    static void run(String[] args) throws Exception {
        var env = new HashMap<String,String>();
        for (String line : Files.readAllLines(Path.of(args[0], ".env"))) {
            if (!line.isBlank() && !line.startsWith("#")) {
                var pair = line.split("=", 2); env.put(pair[0], pair[1]);
            }
        }
        try (var db = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("pedidos360_pedidos").withUsername("platform_validation")
                .withPassword(UUID.randomUUID().toString())) {
            db.start();
            var properties = new LinkedHashMap<String,String>();
            properties.put("spring.datasource.url", db.getJdbcUrl());
            properties.put("spring.datasource.username", db.getUsername());
            properties.put("spring.datasource.password", db.getPassword());
            properties.put("spring.rabbitmq.host", "127.0.0.1");
            properties.put("spring.rabbitmq.port", env.getOrDefault("RABBITMQ_AMQP_PORT", "5679"));
            properties.put("spring.rabbitmq.virtual-host", "pedidos360");
            properties.put("spring.rabbitmq.username", "p360-pedidos-consumer");
            properties.put("spring.rabbitmq.password", env.get("PEDIDOS_CONSUMER_PASSWORD"));
            properties.put("spring.rabbitmq.dynamic", "false");
            properties.put("pedidos360.messaging.coordination-mode", "RABBITMQ");
            properties.put("pedidos360.messaging.reliability.platform-ready", "true");
            properties.put("server.port", "0");
            properties.put("entra.enabled", "false");
            properties.put("pedidos.identidad-local.enabled", "false");
            properties.put("management.health.rabbit.enabled", "true");
            properties.forEach(System::setProperty);
            try (var context = SpringApplication.run(PedidosApplication.class)) {
                if (!context.getBeansOfType(RabbitAdmin.class).isEmpty()) throw new AssertionError("Application must not administer topology");
                var registry = context.getBean(RabbitListenerEndpointRegistry.class);
                var listener = registry.getListenerContainer(ConfirmacionConsumerRecovery.LISTENER_ID);
                await(() -> listener.isRunning(), "listener started with platform-ready=true");
                // Operator probe explicitly reuses actual app beans, including original args and omitted type.
                var operator = factory(env, "p360-bootstrap", "BOOTSTRAP_PASSWORD");
                try {
                    var admin = new RabbitAdmin(operator);
                    for (var exchange : context.getBeansOfType(Exchange.class).values()) admin.declareExchange(exchange);
                    for (var queue : context.getBeansOfType(Queue.class).values()) admin.declareQueue(queue);
                    for (var binding : context.getBeansOfType(Binding.class).values()) admin.declareBinding(binding);
                    for (var topology : context.getBeansOfType(Declarables.class).values()) {
                        for (var d : topology.getDeclarablesByType(Exchange.class)) admin.declareExchange(d);
                        for (var d : topology.getDeclarablesByType(Queue.class)) admin.declareQueue(d);
                        for (var d : topology.getDeclarablesByType(Binding.class)) admin.declareBinding(d);
                    }
                    System.out.println("PASS: actual application declarations, no PRECONDITION_FAILED");
                } finally { operator.destroy(); }
                var publisher = factory(env, "p360-pagos-publisher", "PAGOS_PUBLISHER_PASSWORD");
                publisher.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
                publisher.setPublisherReturns(true);
                try {
                    var template = new RabbitTemplate(publisher); template.setMandatory(true);
                    var jdbc = context.getBean(JdbcTemplate.class);
                    Long id = jdbc.queryForObject("INSERT INTO pedidos.pedidos(usuario_id,restaurante_id,direccion_entrega,estado,total,moneda) VALUES(1,1,'Platform test','CREADO',100,'CLP') RETURNING id", Long.class);
                    var json = context.getBean(tools.jackson.databind.json.JsonMapper.class);
                    var command = ConfirmarPedidoPorPago.crear(id, 1);
                    var pagoProperties = new cl.duoc.pedidos360.pagos.messaging.RabbitProperties(
                        cl.duoc.pedidos360.pagos.messaging.RabbitProperties.Mode.RABBITMQ,
                        new cl.duoc.pedidos360.pagos.messaging.RabbitProperties.Exchanges("p360.pedidos.commands"),
                        new cl.duoc.pedidos360.pagos.messaging.RabbitProperties.RoutingKeys("pedido.confirmar.v1"),
                        new cl.duoc.pedidos360.pagos.messaging.RabbitProperties.Queues("p360.pedidos.confirmacion.q"),
                        Duration.ofSeconds(3), Duration.ofSeconds(30), 10, 5000, Duration.ofSeconds(30));
                    var actualPublisher = new cl.duoc.pedidos360.pagos.messaging.PagoConfirmacionPublisher(template, pagoProperties);
                    var claim = new cl.duoc.pedidos360.pagos.messaging.OutboxStore.Claim(
                        command.messageId(), UUID.randomUUID(), json.writeValueAsString(command));
                    for (int i = 0; i < 2; i++) {
                        actualPublisher.publish(claim);
                    }
                    await(() -> "CONFIRMADO".equals(jdbc.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id=?", String.class, id)), "local commit");
                    var operatorCheck = factory(env, "p360-bootstrap", "BOOTSTRAP_PASSWORD");
                    try {
                        var admin = new RabbitAdmin(operatorCheck);
                        var retryProps = new MessageProperties();
                        retryProps.setMessageId(command.messageId().toString()); retryProps.setContentType("application/json");
                        retryProps.setDeliveryMode(MessageDeliveryMode.PERSISTENT); retryProps.setAppId("pagos-service");
                        retryProps.setHeader("retry-count", 0);
                        context.getBean(ConfirmacionRetryPublisher.class).publish(new Message(json.writeValueAsBytes(command), retryProps), 0);
                        await(() -> ((Number) admin.getQueueProperties("p360.pedidos.confirmacion.retry.5s.q").get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue() == 0, "retry returned after real TTL");
                        await(() -> ((Number) admin.getQueueProperties("p360.pedidos.confirmacion.q").get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue() == 0, "queue drained");
                        if (jdbc.queryForObject("SELECT version FROM pedidos.pedidos WHERE id=?", Long.class, id) != 1L) throw new AssertionError("Duplicate changed domain twice");
                        System.out.println("PASS: original ConfirmacionRetryPublisher with consumer permissions, 5s TTL and idempotent return");
                    } finally { operatorCheck.destroy(); }
                    System.out.println("PASS: original Pagos publisher + Pedidos listener + restricted credentials + PostgreSQL commit + duplicate idempotence");
                } finally { publisher.destroy(); }
            }
        }
        System.out.println("PLATFORM APPLICATION CHECK PASSED; only isolated test mode used");
    }
    static CachingConnectionFactory factory(Map<String,String> env, String user, String passwordKey) {
        var factory = new CachingConnectionFactory("127.0.0.1", Integer.parseInt(env.getOrDefault("RABBITMQ_AMQP_PORT", "5679")));
        factory.setVirtualHost("pedidos360"); factory.setUsername(user); factory.setPassword(env.get(passwordKey));
        return factory;
    }
    interface Check { boolean test() throws Exception; }
    static void await(Check check, String label) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) { if (check.test()) return; TimeUnit.MILLISECONDS.sleep(100); }
        throw new AssertionError("Timed out: " + label);
    }
}
