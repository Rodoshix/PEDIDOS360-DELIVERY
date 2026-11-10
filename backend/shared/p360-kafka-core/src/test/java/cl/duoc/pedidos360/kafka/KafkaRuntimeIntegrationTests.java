package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Opt-in: the reproducible local Compose broker must be healthy; never use an operational cluster.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "P360_KAFKA_IT", matches = "true")
class KafkaRuntimeIntegrationTests {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
  static JdbcTemplate db;
  static TransactionTemplate tx;
  static EventCodec codec = new EventCodec();
  static String servers = "localhost:19092";
  static List<KafkaRuntime> workers = new ArrayList<>();
  static KafkaStores restaurants, products, cart;

  @BeforeAll
  static void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    db = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    for (String schema : List.of("restaurantes", "productos", "carrito")) {
      db.execute("CREATE SCHEMA " + schema);
      String migration =
          Files.readString(
                  Path.of(
                      "../../services/restaurantes-service/src/main/resources/db/migration/V3__kafka_state_outbox_projection.sql"))
              .replace("restaurantes.", schema + ".");
      for (String sql : migration.split(";")) if (!sql.isBlank()) db.execute(sql);
    }
    restaurants = new KafkaStores(db, tx, "restaurantes", codec);
    products = new KafkaStores(db, tx, "productos", codec);
    cart = new KafkaStores(db, tx, "carrito", codec);
    workers.add(
        new KafkaRuntime(
            restaurants, KafkaRuntime.properties(servers), EventCodec.RESTAURANTES, null, null));
    workers.add(
        new KafkaRuntime(
            products,
            KafkaRuntime.properties(servers),
            EventCodec.PRODUCTOS,
            EventCodec.RESTAURANTES,
            "p360-it-productos-" + UUID.randomUUID()));
    workers.add(
        new KafkaRuntime(
            cart,
            KafkaRuntime.properties(servers),
            null,
            EventCodec.PRODUCTOS,
            "p360-it-carrito-" + UUID.randomUUID()));
    workers.forEach(KafkaRuntime::start);
  }

  @AfterAll
  static void stop() {
    workers.forEach(runtime -> { runtime.stop(); assertThat(runtime.isShutdownComplete()).isTrue(); });
  }

  @Test
  void bothRealTopicsOutboxProjectionDuplicateOldInvalidAndRestart() throws Exception {
    long id = System.currentTimeMillis();
    tx.executeWithoutResult(
        s -> {
          restaurants.record(EventCodec.RESTAURANTES, id, "ABIERTO", false);
          restaurants.record(EventCodec.RESTAURANTES, id, "CERRADO", false);
          products.record(EventCodec.PRODUCTOS, id, "true", false);
          products.record(EventCodec.PRODUCTOS, id, "false", false);
        });
    await()
        .atMost(Duration.ofSeconds(40))
        .untilAsserted(
            () -> {
              assertThat(products.observation(id))
                  .containsEntry("valor", "CERRADO")
                  .containsEntry("version", "2");
              assertThat(cart.observation(id))
                  .containsEntry("valor", "false")
                  .containsEntry("version", "2");
              assertThat(
                      db.queryForObject(
                          "SELECT count(*) FROM restaurantes.kafka_outbox WHERE sent_at IS NULL",
                          Long.class))
                  .isZero();
            });
    var p = new Properties();
    p.put("bootstrap.servers", servers);
    p.put("key.serializer", StringSerializer.class.getName());
    p.put("value.serializer", StringSerializer.class.getName());
    var c = new Properties();
    c.put("bootstrap.servers", servers);
    c.put("group.id", "p360-it-terminal-" + id);
    c.put("group.protocol", "classic");
    c.put("auto.offset.reset", "earliest");
    c.put("key.deserializer", StringDeserializer.class.getName());
    c.put("value.deserializer", StringDeserializer.class.getName());
    try (var producer = new KafkaProducer<String, String>(p);
        var terminal = new KafkaConsumer<String, String>(c)) {
      terminal.subscribe(List.of(EventCodec.RESTAURANTES + ".dlt"));
      String existing =
          db.queryForObject(
              "SELECT payload FROM restaurantes.kafka_outbox WHERE entity_id=? ORDER BY seq DESC"
                  + " LIMIT 1",
              String.class,
              id);
      producer
          .send(new ProducerRecord<>(EventCodec.RESTAURANTES, Long.toString(id), existing))
          .get(10, TimeUnit.SECONDS);
      producer
          .send(
              new ProducerRecord<>(
                  EventCodec.RESTAURANTES,
                  Long.toString(id),
                  codec.encode(EventCodec.RESTAURANTES, id, "ABIERTO", 1, "INITIALIZED")))
          .get(10, TimeUnit.SECONDS);
      var invalid =
          producer
              .send(
                  new ProducerRecord<>(
                      EventCodec.RESTAURANTES,
                      Long.toString(id),
                      "{\"token\":\"DO_NOT_LOG_THIS\"}"))
              .get(10, TimeUnit.SECONDS);
      var found = new ArrayList<String>();
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                for (var event : terminal.poll(Duration.ofMillis(300))) found.add(event.value());
                return found.stream()
                    .anyMatch(
                        value -> {
                          var node =
                              tools.jackson.databind.json.JsonMapper.builder()
                                  .build()
                                  .readTree(value);
                          return node.path("offset").longValue() == invalid.offset()
                              && node.path("partition").intValue() == invalid.partition();
                        });
              });
      assertThat(found)
          .allSatisfy(
              value ->
                  assertThat(value)
                      .doesNotContain("DO_NOT_LOG_THIS")
                      .contains("partition", "offset"));
    }
    assertThat(products.observation(id)).containsEntry("valor", "CERRADO");
    workers.get(1).stop();
    workers.get(1).start();
    tx.executeWithoutResult(
        s -> restaurants.record(EventCodec.RESTAURANTES, id, "INACTIVO", false));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(products.observation(id))
                    .containsEntry("valor", "INACTIVO")
                    .containsEntry("version", "3"));
  }

  @Test
  void shortPollDeadlineSurvivesBlockedDatabaseAndTerminalOutage() throws Exception {
    long id = System.currentTimeMillis();
    String group = "p360-it-recovery-" + UUID.randomUUID();
    var p = KafkaRuntime.properties(servers);
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    p.put("group.id", group);
    p.put("group.protocol", "classic");
    p.put("enable.auto.commit", "false");
    p.put("auto.offset.reset", "latest");
    p.put("max.poll.records", "1");
    p.put("max.poll.interval.ms", "1000");
    var available = new java.util.concurrent.atomic.AtomicBoolean(false);
    var release = new java.util.concurrent.CountDownLatch(1);
    var started = new java.util.concurrent.CountDownLatch(1);
    var applications = new java.util.concurrent.atomic.AtomicInteger();
    var terminalAttempts = new java.util.concurrent.atomic.AtomicInteger();
    var terminalAvailable = new java.util.concurrent.atomic.AtomicBoolean(false);
    var pp = KafkaRuntime.properties(servers);
    pp.put("key.serializer", StringSerializer.class.getName());
    pp.put("value.serializer", StringSerializer.class.getName());
    try (var c = new KafkaConsumer<String,String>(p);
         var sender = new KafkaProducer<String,String>(pp);
         var admin = org.apache.kafka.clients.admin.Admin.create(KafkaRuntime.properties(servers));
         var loop = new KafkaConsumerLoop(c, EventCodec.PRODUCTOS, r -> {
           applications.incrementAndGet();
           started.countDown();
           try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
           if (!available.get()) throw new org.springframework.dao.DataAccessResourceFailureException("simulated outage");
           cart.apply(r.topic(),r.key(),r.value());
         }, r -> {terminalAttempts.incrementAndGet();return terminalAvailable.get();})) {
      await().atMost(Duration.ofSeconds(30)).until(() -> {loop.tick();return !c.assignment().isEmpty();});
      var metadata = sender.send(new ProducerRecord<>(EventCodec.PRODUCTOS,Long.toString(id),codec.encode(EventCodec.PRODUCTOS,id,"false",1,"INITIALIZED"))).get(10,TimeUnit.SECONDS);
      var tp = new org.apache.kafka.common.TopicPartition(metadata.topic(),metadata.partition());
      await().atMost(Duration.ofSeconds(10)).until(() -> {loop.tick();return started.getCount()==0;});
      String member = c.groupMetadata().memberId();
      int generation = c.groupMetadata().generationId();
      long end = System.nanoTime()+Duration.ofSeconds(4).toNanos();
      while(System.nanoTime()<end)loop.tick();
      assertThat(c.groupMetadata().memberId()).isEqualTo(member);
      assertThat(c.groupMetadata().generationId()).isEqualTo(generation);
      assertThat(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(tp)).isNull();
      release.countDown(); // Exercise a completed connectivity failure as well as blocked I/O.
      await().atMost(Duration.ofSeconds(10)).until(() -> {loop.tick();return applications.get()>=2;});
      available.set(true);
      await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {loop.tick();assertThat(cart.observation(id)).containsEntry("valor","false");});
      await().atMost(Duration.ofSeconds(10)).until(() -> {loop.tick();var v=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(tp);return v!=null && v.offset()==metadata.offset()+1;});
      var bad = sender.send(new ProducerRecord<>(EventCodec.PRODUCTOS,Long.toString(id),"invalid")).get(10,TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(10)).until(() -> {loop.tick();return terminalAttempts.get()>0;});
      end=System.nanoTime()+Duration.ofSeconds(4).toNanos();
      while(System.nanoTime()<end)loop.tick();
      assertThat(c.groupMetadata().memberId()).isEqualTo(member);
      assertThat(c.groupMetadata().generationId()).isEqualTo(generation);
      assertThat(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(tp).offset()).isEqualTo(metadata.offset()+1);
      terminalAvailable.set(true);
      await().atMost(Duration.ofSeconds(15)).until(() -> {loop.tick();var v=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(tp);return v!=null && v.offset()==bad.offset()+1;});
    } finally {release.countDown();}
  }

  @Test
  void incompleteShutdownLeavesOffsetForRealGroupRedelivery() throws Exception {
    var kafkaLogger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.apache.kafka.clients.consumer.internals.ClassicKafkaConsumer");
    var closingLogs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    closingLogs.start();kafkaLogger.addAppender(closingLogs);
    long id = System.currentTimeMillis();
    String key = Long.toString(id), group="p360-it-shutdown-"+UUID.randomUUID();
    var entered=new java.util.concurrent.CountDownLatch(1);
    var release=new java.util.concurrent.CountDownLatch(1);
    KafkaStores blocked=org.mockito.Mockito.mock(KafkaStores.class);
    org.mockito.Mockito.when(blocked.apply(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          if(key.equals(invocation.getArgument(1))) {
            entered.countDown();
            while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){}
          }
          return "APPLIED";
        });
    var options=KafkaRuntime.properties(servers);
    options.setProperty("p360.shutdown.ms","4000");
    options.setProperty("p360.task.shutdown.ms","100");
    options.setProperty("p360.consumer.close.ms","1000");
    options.setProperty("p360.producer.close.ms","500");
    var runtime=new KafkaRuntime(blocked,options,null,EventCodec.PRODUCTOS,group);
    var pp=KafkaRuntime.properties(servers);
    pp.put("key.serializer",StringSerializer.class.getName());pp.put("value.serializer",StringSerializer.class.getName());
    try(var sender=new KafkaProducer<String,String>(pp);
        var admin=org.apache.kafka.clients.admin.Admin.create(KafkaRuntime.properties(servers))) {
      runtime.start();
      var metadata=sender.send(new ProducerRecord<>(EventCodec.PRODUCTOS,key,codec.encode(EventCodec.PRODUCTOS,id,"false",1,"INITIALIZED"))).get(10,TimeUnit.SECONDS);
      assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue();
      runtime.stop();
      assertThat(runtime.isShutdownComplete()).isFalse();
      assertThatThrownBy(runtime::start).isInstanceOf(IllegalStateException.class);
      var tp=new org.apache.kafka.common.TopicPartition(metadata.topic(),metadata.partition());
      var committed=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(tp);
      assertThat(committed==null || committed.offset()<=metadata.offset()).isTrue();
      release.countDown();
      await().atMost(Duration.ofSeconds(5)).until(runtime::isShutdownComplete);
      var recovered=new KafkaRuntime(cart,options,null,EventCodec.PRODUCTOS,group);
      try {
        recovered.start();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(cart.observation(id)).containsEntry("valor","false").containsEntry("version","1"));
        await().atMost(Duration.ofSeconds(10)).until(()->{
          var v=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(tp);
          return v!=null && v.offset()>metadata.offset();
        });
      } finally {recovered.stop();assertThat(recovered.isShutdownComplete()).isTrue();}
    } finally {
      release.countDown();runtime.stop();
      kafkaLogger.detachAppender(closingLogs);closingLogs.stop();
    }
    assertThat(closingLogs.list).noneMatch(e -> e.getFormattedMessage().contains("Failed to close coordinator"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "P360_KAFKA_OUTAGE_IT", matches = "true")
  void brokerOutageRetainsDurableOutboxAndRecovers() throws Exception {
    long id = System.currentTimeMillis();
    try {
      assertThat(
              new ProcessBuilder("docker", "stop", "p360-kafka-dev-kafka-1")
                  .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                  .start()
                  .waitFor())
          .isZero();
      tx.executeWithoutResult(
          s -> restaurants.record(EventCodec.RESTAURANTES, id, "CERRADO", false));
      Thread.sleep(18000);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM restaurantes.kafka_outbox WHERE entity_id=? AND sent_at IS"
                      + " NULL",
                  Long.class,
                  id))
          .isEqualTo(1);
    } finally {
      assertThat(
              new ProcessBuilder("docker", "start", "p360-kafka-dev-kafka-1")
                  .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                  .start()
                  .waitFor())
          .isZero();
    }
    await()
        .atMost(Duration.ofSeconds(90))
        .untilAsserted(
            () -> {
              assertThat(products.observation(id)).containsEntry("valor", "CERRADO");
              assertThat(
                      db.queryForObject(
                          "SELECT count(*) FROM restaurantes.kafka_outbox WHERE entity_id=? AND"
                              + " sent_at IS NULL",
                          Long.class,
                          id))
                  .isZero();
            });
  }
}
