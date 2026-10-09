package cl.duoc.pedidos360.pedidos;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import cl.duoc.pedidos360.messaging.command.*;
import cl.duoc.pedidos360.pedidos.dto.*;
import cl.duoc.pedidos360.pedidos.messaging.carrito.*;
import cl.duoc.pedidos360.pedidos.security.*;
import cl.duoc.pedidos360.pedidos.service.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "entra.tenant-id=11111111-1111-1111-1111-111111111111",
      "pedidos360.messaging.carrito.mode=RABBITMQ",
      "pedidos360.messaging.carrito.platform-ready=true",
      "pedidos360.messaging.carrito.confirm-timeout=300ms",
      "pedidos360.messaging.carrito.publisher-retry-delay=1ms"
    })
@Import(PostgresTestConfiguration.class)
class CarritoPublisherIntegrationTests {
  static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4.1.8-management-alpine");
  static CachingConnectionFactory adminConnection;
  static RabbitTemplate admin;
  static Binding binding;

  static {
    BROKER.start();
    adminConnection = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
    adminConnection.setUsername(BROKER.getAdminUsername());
    adminConnection.setPassword(BROKER.getAdminPassword());
    admin = new RabbitTemplate(adminConnection);
    var a = new RabbitAdmin(adminConnection);
    a.declareExchange(new DirectExchange(CarritoCommandProperties.EXCHANGE));
    a.declareQueue(
        new org.springframework.amqp.core.Queue(
            CarritoCommandProperties.QUEUE, true, false, false, Map.of("x-queue-type", "quorum")));
    binding =
        new Binding(
            CarritoCommandProperties.QUEUE,
            Binding.DestinationType.QUEUE,
            CarritoCommandProperties.EXCHANGE,
            CarritoCommandProperties.ROUTE,
            Map.of());
    a.declareBinding(binding);
    try {
      assertThat(
              BROKER
                  .execInContainer(
                      "rabbitmqctl", "add_user", "p360-pedidos-carrito-publisher", "test-only")
                  .getExitCode())
          .isZero();
      assertThat(
              BROKER
                  .execInContainer(
                      "rabbitmqctl",
                      "set_permissions",
                      "-p",
                      "/",
                      "p360-pedidos-carrito-publisher",
                      "^$",
                      "^p360\\.commands$",
                      "^$")
                  .getExitCode())
          .isZero();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("pedidos360.messaging.carrito.host", BROKER::getHost);
    r.add("pedidos360.messaging.carrito.port", BROKER::getAmqpPort);
    r.add("pedidos360.messaging.carrito.virtual-host", () -> "/");
    r.add("pedidos360.messaging.carrito.username", () -> "p360-pedidos-carrito-publisher");
    r.add("pedidos360.messaging.carrito.password", () -> "test-only");
  }

  @MockitoBean
  CarritoOutboxDispatcher
      scheduledDispatcher; // Only scheduling suppressed: dispatcher exercised explicitly below.

  @Autowired PedidoService service;
  @Autowired CarritoOutboxStore store;
  @Autowired CarritoOutboxPublisher publisher;
  @Autowired JdbcTemplate db;

  @Autowired
  @Qualifier("rabbitConnectionFactory")
  CachingConnectionFactory existing;

  @Autowired
  @Qualifier("carritoCommandConnection")
  CachingConnectionFactory dedicated;

  @AfterAll
  static void cleanup() {
    adminConnection.destroy();
    BROKER.close();
  }

  CarritoOutboxStore.Claim claim() {
    service.crearConCarrito(
        new IdentidadUsuario(T, 10L, Set.of(IdentidadUsuario.Rol.CLIENTE)),
        new CrearPedidoRequest(20L, "Dirección prueba", List.of(new LineaPedidoRequest(101L, 2))),
        new CarritoSnapshotClient.Snapshot(T, UUID.randomUUID(), 42, 3));
    return store.claim().getFirst();
  }

  Message receive(UUID id) {
    var result = new java.util.concurrent.atomic.AtomicReference<Message>();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              var m = admin.receive(CarritoCommandProperties.QUEUE, 100);
              if (m != null && id.toString().equals(m.getMessageProperties().getMessageId()))
                result.set(m);
              return result.get() != null;
            });
    return result.get();
  }

  @Test
  void dedicatedConnectionPreservesExistingCredentials() {
    assertThat(dedicated).isNotSameAs(existing);
    assertThat(dedicated.getUsername()).isEqualTo("p360-pedidos-carrito-publisher");
    assertThat(existing.getUsername()).isNotEqualTo(dedicated.getUsername());
  }

  @Test
  void positiveConfirmWithRouteSettlesAndPreservesExactBody() throws Exception {
    var c = claim();
    publisher.publish(c);
    assertThat(store.finish(c, null)).isTrue();
    var wire = new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.GetResponse>();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              var incoming = admin.execute(ch -> ch.basicGet(CarritoCommandProperties.QUEUE, true));
              if (incoming != null && c.id().toString().equals(incoming.getProps().getMessageId()))
                wire.set(incoming);
              return wire.get() != null;
            });
    assertThat(((Number) wire.get().getProps().getHeaders().get("retry-count")).longValue())
        .isZero();
    var m =
        new Message(
            wire.get().getBody(),
            new org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter()
                .toMessageProperties(wire.get().getProps(), wire.get().getEnvelope(), "UTF-8"));
    assertThat(m.getBody())
        .isEqualTo(c.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(m.getMessageProperties().getReceivedUserId())
        .isEqualTo("p360-pedidos-carrito-publisher");
    assertThat(m.getMessageProperties().getRetryCount()).isZero();
  }

  @Test
  void mandatoryReturnDoesNotReportPublicationAndRecoversSameUuid() throws Exception {
    var c = claim();
    new RabbitAdmin(adminConnection).removeBinding(binding);
    try {
      assertThatThrownBy(() -> publisher.publish(c)).isInstanceOf(java.io.IOException.class);
      assertThat(store.finish(c, "IOException")).isTrue();
    } finally {
      new RabbitAdmin(adminConnection).declareBinding(binding);
    }
    Thread.sleep(5);
    var recovered = store.claim().getFirst();
    assertThat(recovered.id()).isEqualTo(c.id());
    assertThat(recovered.payload()).isEqualTo(c.payload());
    publisher.publish(recovered);
    assertThat(store.finish(recovered, null)).isTrue();
    receive(c.id());
  }

  @Test
  void injectedNackRetainsWorkWithoutAlternativePublication() throws Exception {
    var c = claim();
    var template = mock(RabbitTemplate.class);
    doAnswer(
            call -> {
              ((CorrelationData) call.getArgument(3))
                  .getFuture()
                  .complete(new CorrelationData.Confirm(false, "injected nack"));
              return null;
            })
        .when(template)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    var settings =
        new CarritoCommandProperties(
            CarritoCommandProperties.Mode.HTTP,
            false,
            "localhost",
            5672,
            "/",
            "",
            "",
            "p360-pedidos-carrito-publisher",
            "p360-carrito-consumer",
            Duration.ofMillis(50),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(1));
    assertThatThrownBy(() -> new CarritoOutboxPublisher(template, settings).publish(c))
        .isInstanceOf(java.io.IOException.class);
    assertThat(store.finish(c, "IOException")).isTrue();
    verify(template, times(1))
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    db.update(
        "UPDATE pedidos.carrito_vaciado_outbox SET next_attempt_at=clock_timestamp() WHERE"
            + " message_id=?",
        c.id());
    var recovered = store.claim().getFirst();
    publisher.publish(recovered);
    assertThat(store.finish(recovered, null)).isTrue();
    receive(c.id());
  }

  @Test
  void realPublishThenInjectedConfirmLossRecoversDuplicateWithStableIdentity() throws Exception {
    var c = claim();
    var template = mock(RabbitTemplate.class);
    doAnswer(
            call -> {
              publisher.publish(c);
              return null;
            })
        .when(template)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    var settings =
        new CarritoCommandProperties(
            CarritoCommandProperties.Mode.HTTP,
            false,
            "localhost",
            5672,
            "/",
            "",
            "",
            "p360-pedidos-carrito-publisher",
            "p360-carrito-consumer",
            Duration.ofMillis(50),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(1));
    assertThatThrownBy(() -> new CarritoOutboxPublisher(template, settings).publish(c))
        .isInstanceOf(java.util.concurrent.TimeoutException.class);
    receive(c.id());
    assertThat(store.finish(c, "TimeoutException")).isTrue();
    db.update(
        "UPDATE pedidos.carrito_vaciado_outbox SET next_attempt_at=clock_timestamp() WHERE"
            + " message_id=?",
        c.id());
    var next = store.claim().getFirst();
    publisher.publish(next);
    assertThat(store.finish(next, null)).isTrue();
    assertThat(receive(c.id()).getBody())
        .isEqualTo(c.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void actualBrokerOutageRetainsIntentUntilBrokerRestart() throws Exception {
    var c = claim();
    assertThat(BROKER.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
    try {
      assertThatThrownBy(() -> publisher.publish(c)).isInstanceOf(Exception.class);
      assertThat(store.finish(c, "TransportFailure")).isTrue();
    } finally {
      assertThat(BROKER.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
    }
    dedicated.resetConnection();
    db.update(
        "UPDATE pedidos.carrito_vaciado_outbox SET next_attempt_at=clock_timestamp() WHERE"
            + " message_id=?",
        c.id());
    var next = store.claim().getFirst();
    assertThat(next.id()).isEqualTo(c.id());
    publisher.publish(next);
    assertThat(store.finish(next, null)).isTrue();
    receive(c.id());
  }
}
