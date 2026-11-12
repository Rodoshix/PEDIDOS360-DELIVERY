package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real disposable PostgreSQL/outbox and runtime; producer acknowledgements are controlled mocks. */
@Testcontainers
class KafkaOutboxPublisherIntegrationTests {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @ParameterizedTest
  @ValueSource(strings = {"restaurantes", "productos"})
  void unconfirmedPublicationRetainsOutboxAndRecoversWithSameEvent(String schema) throws Exception {
    var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    var db = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    db.execute("CREATE SCHEMA " + schema);
    String version = schema.equals("restaurantes") ? "V3" : "V4";
    String migration = Files.readString(Path.of("../../services/" + schema
        + "-service/src/main/resources/db/migration/" + version + "__kafka_state_outbox_projection.sql"));
    for (String statement : migration.split(";")) if (!statement.isBlank()) db.execute(statement);
    var stores = new KafkaStores(db, tx, schema, new EventCodec());
    String topic = schema.equals("restaurantes") ? EventCodec.RESTAURANTES : EventCodec.PRODUCTOS;
    tx.executeWithoutResult(s -> stores.record(topic, 77, schema.equals("restaurantes") ? "CERRADO" : "false", false));
    String table = schema + ".kafka_outbox";
    var original = db.queryForMap("SELECT event_id::text AS identity,payload FROM " + table);
    var sent = new CopyOnWriteArrayList<ProducerRecord<String, String>>();
    var first = new CompletableFuture<RecordMetadata>();
    var calls = new AtomicInteger();
    try (var construction = mockConstruction(KafkaProducer.class, (producer, context) -> {
      when(producer.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
        sent.add(invocation.getArgument(0));
        return calls.incrementAndGet() == 1 ? first
            : CompletableFuture.completedFuture(mock(RecordMetadata.class));
      });
    })) {
      var runtime = new KafkaRuntime(stores, KafkaRuntime.properties("127.0.0.1:1"), topic, null, null);
      try {
        runtime.start();
        assertThat(construction.constructed()).hasSize(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> calls.get() == 1);
        // An unresolved send must never mark the durable outbox as delivered.
        assertThat(db.queryForObject("SELECT sent_at IS NULL FROM " + table, Boolean.class)).isTrue();
        assertThat(db.queryForObject("SELECT attempts FROM " + table, Integer.class)).isZero();
        first.completeExceptionally(new org.apache.kafka.common.errors.TimeoutException("synthetic unconfirmed send"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
          assertThat(db.queryForObject("SELECT attempts FROM " + table, Integer.class)).isEqualTo(1);
          assertThat(db.queryForObject("SELECT sent_at IS NULL AND next_attempt>now() FROM " + table, Boolean.class)).isTrue();
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT event_id::text FROM " + table, String.class)).isEqualTo(original.get("identity"));
        // Advance only the disposable fixture's retry eligibility, not production policy or runtime.
        db.update("UPDATE " + table + " SET next_attempt=now()-interval '1 second'");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(db.queryForObject("SELECT sent_at IS NOT NULL FROM " + table, Boolean.class)).isTrue());
        assertThat(sent).hasSize(2).allSatisfy(record -> {
          assertThat(record.topic()).isEqualTo(topic);
          assertThat(record.key()).isEqualTo("77");
          assertThat(record.value()).isEqualTo(original.get("payload"));
        });
        assertThat(db.queryForObject("SELECT count(*) FROM " + table, Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT event_id::text FROM " + table, String.class)).isEqualTo(original.get("identity"));
        assertThat(db.queryForObject("SELECT attempts FROM " + table, Integer.class)).isEqualTo(1);
      } finally {
        first.completeExceptionally(new CancellationException("test cleanup"));
        runtime.stop();
        assertThat(runtime.isShutdownComplete()).isTrue();
      }
    }
  }
}
