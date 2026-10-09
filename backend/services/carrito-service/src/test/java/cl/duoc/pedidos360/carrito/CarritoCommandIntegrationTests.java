package cl.duoc.pedidos360.carrito;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import cl.duoc.pedidos360.carrito.entity.Carrito;
import cl.duoc.pedidos360.carrito.messaging.*;
import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import cl.duoc.pedidos360.carrito.service.CarritoService;
import cl.duoc.pedidos360.messaging.command.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "entra.tenant-id=11111111-1111-1111-1111-111111111111",
      "pedidos360.messaging.carrito.mode=RABBITMQ",
      "pedidos360.messaging.carrito.platform-ready=true",
      "pedidos360.messaging.carrito.confirm-timeout=500ms",
      "pedidos360.messaging.carrito.recovery-backoff=500ms"
    })
@Import(PostgresTestConfiguration.class)
class CarritoCommandIntegrationTests {
  static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4.1.8-management-alpine");
  static final String PUB = "p360-pedidos-carrito-publisher", CON = "p360-carrito-consumer";
  static CachingConnectionFactory adminConnection, publisherConnection;
  static RabbitTemplate admin, publisher;

  static {
    BROKER.start();
    adminConnection = connection(BROKER.getAdminUsername(), BROKER.getAdminPassword());
    admin = new RabbitTemplate(adminConnection);
    var a = new RabbitAdmin(adminConnection);
    for (String x : List.of("p360.commands", "p360.retry", "p360.dlx"))
      a.declareExchange(new DirectExchange(x));
    for (String q :
        List.of(
            CarritoCommandProperties.QUEUE,
            "p360.carrito.vaciado.retry.1s.q",
            "p360.carrito.vaciado.dlq"))
      a.declareQueue(
          new org.springframework.amqp.core.Queue(
              q, true, false, false, Map.of("x-queue-type", "quorum")));
    a.declareBinding(
        new Binding(
            CarritoCommandProperties.QUEUE,
            Binding.DestinationType.QUEUE,
            "p360.commands",
            CarritoCommandProperties.ROUTE,
            Map.of()));
    a.declareBinding(
        new Binding(
            "p360.carrito.vaciado.retry.1s.q",
            Binding.DestinationType.QUEUE,
            "p360.retry",
            CarritoCommandProperties.RETRY_ROUTE,
            Map.of()));
    a.declareBinding(
        new Binding(
            "p360.carrito.vaciado.dlq",
            Binding.DestinationType.QUEUE,
            "p360.dlx",
            CarritoCommandProperties.FAILED_ROUTE,
            Map.of()));
    try {
      for (String user : List.of(PUB, CON, "untrusted"))
        assertThat(
                BROKER.execInContainer("rabbitmqctl", "add_user", user, "test-only").getExitCode())
            .isZero();
      assertThat(
              BROKER
                  .execInContainer(
                      "rabbitmqctl",
                      "set_permissions",
                      "-p",
                      "/",
                      PUB,
                      "^$",
                      "^p360\\.commands$",
                      "^$")
                  .getExitCode())
          .isZero();
      assertThat(
              BROKER
                  .execInContainer(
                      "rabbitmqctl",
                      "set_permissions",
                      "-p",
                      "/",
                      CON,
                      "^$",
                      "^(p360\\.retry|p360\\.dlx)$",
                      "^p360\\.carrito\\.vaciado\\.q$")
                  .getExitCode())
          .isZero();
      assertThat(
              BROKER
                  .execInContainer(
                      "rabbitmqctl",
                      "set_permissions",
                      "-p",
                      "/",
                      "untrusted",
                      "^$",
                      "^p360\\.commands$",
                      "^$")
                  .getExitCode())
          .isZero();
      assertThat(
              BROKER
                  .execInContainer(
                      "rabbitmqctl",
                      "set_policy",
                      "-p",
                      "/",
                      "cart-retry",
                      "^p360\\.carrito\\.vaciado\\.retry\\.1s\\.q$",
                      "{\"message-ttl\":1000,\"dead-letter-exchange\":\"p360.commands\",\"dead-letter-routing-key\":\"carrito.vaciar-por-pedido.v1\"}",
                      "--apply-to",
                      "quorum_queues")
                  .getExitCode())
          .isZero();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    publisherConnection = connection(PUB, "test-only");
    publisher = new RabbitTemplate(publisherConnection);
    publisher.setMandatory(true);
  }

  static CachingConnectionFactory connection(String u, String pw) {
    var cf = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
    cf.setUsername(u);
    cf.setPassword(pw);
    cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    cf.setPublisherReturns(true);
    return cf;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("pedidos360.messaging.carrito.host", BROKER::getHost);
    r.add("pedidos360.messaging.carrito.port", BROKER::getAmqpPort);
    r.add("pedidos360.messaging.carrito.virtual-host", () -> "/");
    r.add("pedidos360.messaging.carrito.username", () -> CON);
    r.add("pedidos360.messaging.carrito.password", () -> "test-only");
  }

  @Autowired jakarta.persistence.EntityManager entityManager;
  @Autowired CarritoCommandListener listener;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager manager;
  @Autowired CarritoService service;
  @Autowired RabbitListenerEndpointRegistry registry;
  @MockitoSpyBean CarritoReceiptStore receipts;
  @MockitoSpyBean CarritoRepository carritos;

  @MockitoSpyBean(name = "carritoCommandTemplate")
  RabbitTemplate consumerPublisher;

  @AfterEach
  void resetFixtures() {
    reset(receipts, carritos, consumerPublisher);
    SecurityContextHolder.clearContext();
  }

  @AfterAll
  static void stop() {
    publisherConnection.destroy();
    adminConnection.destroy();
    BROKER.close();
  }

  Carrito cart() {
    var c = new Carrito(T, UUID.randomUUID());
    c.agregarProducto(101L, 20L, "Original", 100, 2);
    return carritos.saveAndFlush(c);
  }

  VaciarCarritoPorPedido command(Carrito c) {
    return new VaciarCarritoPorPedido(
        UUID.randomUUID(),
        "VaciarCarritoPorPedido",
        1,
        Instant.now(),
        c.getId(),
        c.getId(),
        c.getVersion(),
        new VaciarCarritoPorPedido.Propietario(T, c.getEntraObjectId()));
  }

  void send(RabbitTemplate r, VaciarCarritoPorPedido c, String uid, int retry) throws Exception {
    var p = new MessageProperties();
    p.setMessageId(c.messageId().toString());
    p.setContentType("application/json");
    p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    p.setUserId(uid);
    p.setHeader("retry-count", retry);
    var corr = new CorrelationData(UUID.randomUUID().toString());
    r.send(
        retry == 1 ? "p360.retry" : "p360.commands",
        retry == 1 ? CarritoCommandProperties.RETRY_ROUTE : CarritoCommandProperties.ROUTE,
        new Message(c.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8), p),
        corr);
    assertThat(corr.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
    assertThat(corr.getReturned()).isNull();
  }

  String state(VaciarCarritoPorPedido c) {
    var rows =
        db.queryForList(
            "SELECT estado FROM carrito.vaciado_por_pedido WHERE message_id=?", c.messageId());
    return rows.isEmpty() ? "ABSENT" : (String) rows.getFirst().get("estado");
  }

  void terminal(VaciarCarritoPorPedido c, String expected) {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(state(c)).isEqualTo(expected));
  }

  Message dlq(UUID id) {
    var ref = new java.util.concurrent.atomic.AtomicReference<Message>();
    await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () -> {
              var m = admin.receive("p360.carrito.vaciado.dlq", 100);
              if (m != null && id.toString().equals(m.getMessageProperties().getMessageId()))
                ref.set(m);
              return ref.get() != null;
            });
    return ref.get();
  }

  void modify(Carrito c) {
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              var row =
                  carritos.findByTenantIdAndEntraObjectId(T, c.getEntraObjectId()).orElseThrow();
              row.agregarProducto(102L, 20L, "Nuevo", 200, 1);
              carritos.flush();
            });
  }

  @Test
  void realListenerCommitsBeforeAckAndDuplicateHasOneEffect() throws Exception {
    var c = cart();
    var cmd = command(c);
    send(publisher, cmd, PUB, 0);
    terminal(cmd, "EMPTIED");
    send(publisher, cmd, PUB, 0);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        carritos
                            .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                            .orElseThrow()
                            .getVersion())
                    .isEqualTo(c.getVersion() + 1));
    assertThat(
            carritos
                .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                .orElseThrow()
                .getLineas())
        .isEmpty();
  }

  @Test
  void changedCartIsDefinitivelyOmitted() throws Exception {
    var c = cart();
    var cmd = command(c);
    modify(c);
    send(publisher, cmd, PUB, 0);
    terminal(cmd, "OMITTED_VERSION_CHANGED");
    assertThat(
            carritos
                .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                .orElseThrow()
                .getLineas())
        .hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"owner", "future", "tenant"})
  void invalidAssociationDoesNotEmpty(String variant) throws Exception {
    var c = cart();
    var original = command(c);
    var cmd =
        new VaciarCarritoPorPedido(
            original.messageId(),
            original.type(),
            1,
            original.occurredAt(),
            original.pedidoId(),
            original.carritoId(),
            variant.equals("future")
                ? original.expectedCarritoVersion() + 1
                : original.expectedCarritoVersion(),
            new VaciarCarritoPorPedido.Propietario(
                variant.equals("tenant") ? UUID.randomUUID() : T,
                variant.equals("owner") ? UUID.randomUUID() : c.getEntraObjectId()));
    send(publisher, cmd, PUB, 0);
    dlq(cmd.messageId());
    assertThat(
            carritos
                .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                .orElseThrow()
                .getLineas())
        .hasSize(1);
  }

  @Test
  void retryCannotCreateTrustedReceipt() throws Exception {
    var cmd = command(cart());
    send(consumerPublisher, cmd, CON, 1);
    dlq(cmd.messageId());
    assertThat(state(cmd)).isEqualTo("ABSENT");
  }

  @Test
  void missingOriginIsRejectedByActualListener() throws Exception {
    var cmd = command(cart());
    send(publisher, cmd, null, 0);
    dlq(cmd.messageId());
    assertThat(state(cmd)).isEqualTo("ABSENT");
  }

  @Test
  void brokerRejectsSpoofedUserId() throws Exception {
    var cf = connection("untrusted", "test-only");
    try {
      var cmd = command(cart());
      var props = new MessageProperties();
      props.setUserId(PUB);
      props.setMessageId(cmd.messageId().toString());
      props.setContentType("application/json");
      var correlation = new CorrelationData(UUID.randomUUID().toString());
      new RabbitTemplate(cf)
          .send(
              "p360.commands",
              CarritoCommandProperties.ROUTE,
              new Message(cmd.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8), props),
              correlation);
      assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).ack()).isFalse();
      assertThat(state(cmd)).isEqualTo("ABSENT");
    } finally {
      cf.destroy();
    }
  }

  @Test
  void reusedUuidDifferentContentRejectedAndOriginalReceiptPreserved() throws Exception {
    var c = cart();
    var cmd = command(c);
    send(publisher, cmd, PUB, 0);
    terminal(cmd, "EMPTIED");
    var other =
        new VaciarCarritoPorPedido(
            cmd.messageId(),
            cmd.type(),
            1,
            cmd.occurredAt(),
            cmd.pedidoId() + 1,
            cmd.carritoId(),
            cmd.expectedCarritoVersion(),
            cmd.propietario());
    send(publisher, other, PUB, 0);
    dlq(cmd.messageId());
    assertThat(state(cmd)).isEqualTo("EMPTIED");
  }

  @Test
  void realRetryUsesPreviouslyCommittedReceipt() throws Exception {
    var cmd = command(cart());
    doThrow(
            new org.springframework.dao.TransientDataAccessResourceException(
                "injected SQL failure"))
        .doCallRealMethod()
        .when(receipts)
        .process(eq(cmd), anyBoolean());
    send(publisher, cmd, PUB, 0);
    terminal(cmd, "EMPTIED");
    assertThat(
            db.queryForObject(
                "SELECT retry_authorized FROM carrito.vaciado_por_pedido WHERE message_id=?",
                Boolean.class,
                cmd.messageId()))
        .isTrue();
  }

  @Test
  void secondTransientFailureHasNoSecondRetry() throws Exception {
    var cmd = command(cart());
    doThrow(
            new org.springframework.dao.TransientDataAccessResourceException(
                "injected SQL failure"))
        .when(receipts)
        .process(eq(cmd), anyBoolean());
    send(publisher, cmd, PUB, 0);
    dlq(cmd.messageId());
    assertThat(state(cmd)).isEqualTo("REJECTED");
  }

  @Test
  void handoffReturnRetainsOriginalUntilRouteRestored() throws Exception {
    admin.execute(
        ch -> {
          ch.queueUnbind(
              "p360.carrito.vaciado.dlq", "p360.dlx", CarritoCommandProperties.FAILED_ROUTE);
          return null;
        });
    var cmd = command(cart());
    try {
      send(publisher, cmd, null, 0);
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  verify(consumerPublisher, atLeastOnce())
                      .send(
                          eq("p360.dlx"),
                          anyString(),
                          any(Message.class),
                          any(CorrelationData.class)));
      assertThat(state(cmd)).isEqualTo("ABSENT");
    } finally {
      new RabbitAdmin(adminConnection)
          .declareBinding(
              new Binding(
                  "p360.carrito.vaciado.dlq",
                  Binding.DestinationType.QUEUE,
                  "p360.dlx",
                  CarritoCommandProperties.FAILED_ROUTE,
                  Map.of()));
    }
    dlq(cmd.messageId());
  }

  @Test
  void restartUsesPersistedReceiptAndDoesNotRepeatEffect() throws Exception {
    var c = cart();
    var cmd = command(c);
    receipts.accept(cmd, false);
    assertThat(receipts.process(cmd, false)).isEqualTo(CarritoReceiptStore.Result.EMPTIED);
    var listener = registry.getListenerContainer(CarritoConsumerRecovery.LISTENER_ID);
    listener.stop();
    listener.start();
    send(publisher, cmd, PUB, 0);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> verify(receipts, atLeast(2)).process(eq(cmd), eq(false)));
    assertThat(
            carritos
                .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                .orElseThrow()
                .getVersion())
        .isEqualTo(c.getVersion() + 1);
  }

  @Test
  void postgresSnapshotRemainsCoherentAcrossConcurrentCommit() throws Exception {
    var c = cart();
    var read = new CountDownLatch(1);
    var changed = new CountDownLatch(1);
    var actualRepository =
        new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entityManager)
            .getRepository(CarritoRepository.class);
    doAnswer(
            call -> {
              var value = actualRepository.findByTenantIdAndEntraObjectId(T, c.getEntraObjectId());
              if (Thread.currentThread().getName().equals("snapshot-reader")) {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .getCurrentTransactionIsolationLevel())
                    .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                read.countDown();
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(
                        db.queryForObject(
                            "SELECT version FROM carrito.carritos WHERE id=?",
                            Long.class,
                            c.getId()))
                    .isEqualTo(c.getVersion());
              }
              return value;
            })
        .when(carritos)
        .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId());
    try (var executor =
        Executors.newSingleThreadExecutor(
            r -> {
              var t = new Thread(r);
              t.setName("snapshot-reader");
              return t;
            })) {
      var future =
          executor.submit(
              () -> {
                var identity =
                    new cl.duoc.pedidos360.carrito.security.IdentidadUsuario(
                        T,
                        c.getEntraObjectId(),
                        Set.of(cl.duoc.pedidos360.carrito.security.IdentidadUsuario.Rol.CLIENTE));
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(identity, null, List.of()));
                try {
                  return service.obtener();
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
      modify(c);
      changed.countDown();
      var snapshot = future.get(10, TimeUnit.SECONDS);
      assertThat(snapshot.version()).isEqualTo(c.getVersion());
      assertThat(snapshot.items()).hasSize(1);
    } finally {
      changed.countDown();
    }
  }

  @Test
  void optimisticEmptyingLosesRaceWithoutDeletingNewProducts() throws Exception {
    var c = cart();
    var cmd = command(c);
    receipts.accept(cmd, false);
    var loaded = new CountDownLatch(1);
    var changed = new CountDownLatch(1);
    var actualRepository =
        new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entityManager)
            .getRepository(CarritoRepository.class);
    doAnswer(
            call -> {
              var value = actualRepository.findByTenantIdAndEntraObjectId(T, c.getEntraObjectId());
              if (Thread.currentThread().getName().equals("emptying-race")) {
                loaded.countDown();
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
              }
              return value;
            })
        .when(carritos)
        .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId());
    try (var executor =
        Executors.newSingleThreadExecutor(
            r -> {
              var t = new Thread(r);
              t.setName("emptying-race");
              return t;
            })) {
      var future = executor.submit(() -> receipts.process(cmd, false));
      assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
      modify(c);
      changed.countDown();
      assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    } finally {
      changed.countDown();
      reset(carritos);
    }
    assertThat(state(cmd)).isEqualTo("RECEIVED");
    assertThat(receipts.process(cmd, false))
        .isEqualTo(CarritoReceiptStore.Result.OMITTED_VERSION_CHANGED);
    assertThat(
            carritos
                .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                .orElseThrow()
                .getLineas())
        .hasSize(2);
  }

  @Test
  void modificationLosesRaceAfterEmptyingCommits() throws Exception {
    var c = cart();
    var cmd = command(c);
    receipts.accept(cmd, false);
    var loaded = new CountDownLatch(1);
    var emptied = new CountDownLatch(1);
    var actualRepository =
        new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entityManager)
            .getRepository(CarritoRepository.class);
    doAnswer(
            call -> {
              var value = actualRepository.findByTenantIdAndEntraObjectId(T, c.getEntraObjectId());
              if (Thread.currentThread().getName().equals("modification-race")) {
                loaded.countDown();
                assertThat(emptied.await(10, TimeUnit.SECONDS)).isTrue();
              }
              return value;
            })
        .when(carritos)
        .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId());
    try (var executor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "modification-race"))) {
      var writer = executor.submit(() -> modify(c));
      assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(receipts.process(cmd, false)).isEqualTo(CarritoReceiptStore.Result.EMPTIED);
      emptied.countDown();
      assertThatThrownBy(() -> writer.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    } finally {
      emptied.countDown();
      reset(carritos);
    }
    assertThat(
            carritos
                .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                .orElseThrow()
                .getLineas())
        .isEmpty();
    assertThat(state(cmd)).isEqualTo("EMPTIED");
  }

  @Test
  void concurrentDedupeHasOneCommit() throws Exception {
    var c = cart();
    var cmd = command(c);
    receipts.accept(cmd, false);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a = executor.submit(() -> receipts.process(cmd, false));
      var b = executor.submit(() -> receipts.process(cmd, false));
      assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(CarritoReceiptStore.Result.EMPTIED);
      assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(CarritoReceiptStore.Result.EMPTIED);
    }
    assertThat(
            carritos
                .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                .orElseThrow()
                .getVersion())
        .isEqualTo(c.getVersion() + 1);
  }

  @Test
  void postgresRejectsReceiptTamperingAndTerminalReactivation() {
    var c = cart();
    var cmd = command(c);
    receipts.accept(cmd, false);
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE carrito.vaciado_por_pedido SET pedido_id=pedido_id+1 WHERE"
                        + " message_id=?",
                    cmd.messageId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    receipts.process(cmd, false);
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE carrito.vaciado_por_pedido SET estado='RECEIVED',finished_at=NULL WHERE"
                        + " message_id=?",
                    cmd.messageId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "DELETE FROM carrito.vaciado_por_pedido WHERE message_id=?", cmd.messageId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void inconsistentInitialReceiptCannotBeInserted() {
    var c = cart();
    var cmd = command(c);
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO"
                        + " carrito.vaciado_por_pedido(message_id,tenant_id,entra_object_id,pedido_id,carrito_id,expected_version,canonical_payload)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    cmd.messageId(),
                    T,
                    c.getEntraObjectId(),
                    cmd.pedidoId() + 1,
                    c.getId(),
                    c.getVersion(),
                    cmd.canonical()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void manualChannelAckObservesCommitAndAckFailureDoesNotPublishAlternative() throws Exception {
    var c = cart();
    var cmd = command(c);
    var channel = mock(com.rabbitmq.client.Channel.class);
    doAnswer(
            call -> {
              assertThat(state(cmd)).isEqualTo("EMPTIED");
              throw new java.io.IOException("injected ACK loss");
            })
        .when(channel)
        .basicAck(1L, false);
    var p = new MessageProperties();
    p.setMessageId(cmd.messageId().toString());
    p.setContentType("application/json");
    p.setReceivedUserId(PUB);
    p.setReceivedRoutingKey(CarritoCommandProperties.ROUTE);
    p.setReceivedExchange(CarritoCommandProperties.EXCHANGE);
    p.setHeader("retry-count", 0);
    p.setDeliveryTag(1L);
    listener.consume(
        new Message(cmd.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8), p), channel);
    verify(channel).basicAck(1L, false);
    verify(consumerPublisher, never())
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
  }

  @Test
  void uncertainHandoffDoesNotAckOrPublishAlternative() throws Exception {
    var cmd = command(cart());
    var p = new MessageProperties();
    p.setMessageId(cmd.messageId().toString());
    p.setContentType("application/json");
    p.setReceivedRoutingKey(CarritoCommandProperties.ROUTE);
    p.setReceivedExchange(CarritoCommandProperties.EXCHANGE);
    p.setHeader("retry-count", 0);
    p.setDeliveryTag(2L);
    doAnswer(call -> null)
        .when(consumerPublisher)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    var channel = mock(com.rabbitmq.client.Channel.class);
    listener.consume(
        new Message(cmd.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8), p), channel);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(consumerPublisher, times(1))
        .send(
            eq(CarritoCommandProperties.DLX),
            eq(CarritoCommandProperties.FAILED_ROUTE),
            any(Message.class),
            any(CorrelationData.class));
    assertThat(state(cmd)).isEqualTo("ABSENT");
  }

  @Test
  void actualJvmCrashAndRestartUseDurableDedupe() throws Exception {
    var c = cart();
    var cmd = command(c);
    var first = child(cmd, true);
    try {
      await()
          .atMost(Duration.ofSeconds(60))
          .untilAsserted(
              () ->
                  assertThat(java.nio.file.Files.readString(first.log()))
                      .contains("PROBE_COMMITTED=EMPTIED"));
    } finally {
      first.process().destroyForcibly();
      assertThat(first.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
    }
    var restarted = child(cmd, false);
    assertThat(restarted.process().waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(restarted.process().exitValue())
        .withFailMessage(java.nio.file.Files.readString(restarted.log()))
        .isZero();
    assertThat(java.nio.file.Files.readString(restarted.log())).contains("PROBE_COMMITTED=EMPTIED");
    send(publisher, cmd, PUB, 0);
    terminal(cmd, "EMPTIED");
    assertThat(
            carritos
                .findByTenantIdAndEntraObjectId(T, c.getEntraObjectId())
                .orElseThrow()
                .getVersion())
        .isEqualTo(c.getVersion() + 1);
  }

  record Child(Process process, java.nio.file.Path log) {}

  Child child(VaciarCarritoPorPedido cmd, boolean crash) throws Exception {
    var pg = db.getDataSource();
    String url, user;
    try (var connection = pg.getConnection()) {
      url = connection.getMetaData().getURL();
      user = connection.getMetaData().getUserName();
    }
    var log =
        java.nio.file.Files.createTempFile(
            java.nio.file.Path.of("target"), "issue82-child-", ".log");
    var binary =
        java.nio.file.Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "java.exe"
                    : "java")
            .toString();
    var process =
        new ProcessBuilder(
                binary,
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                CarritoRestartProbe.class.getName(),
                Boolean.toString(crash),
                "--spring.config.import=",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + user,
                "--spring.datasource.password=test",
                "--entra.tenant-id=" + T,
                "--pedidos360.messaging.carrito.mode=HTTP")
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    process
        .getOutputStream()
        .write((cmd.canonical() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    process.getOutputStream().close();
    return new Child(process, log);
  }
}
