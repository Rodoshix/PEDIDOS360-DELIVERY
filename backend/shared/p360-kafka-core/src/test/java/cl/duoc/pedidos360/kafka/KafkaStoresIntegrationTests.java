package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class KafkaStoresIntegrationTests {
  @Test
  void snapshotRestoresAnEmptyProjectionAtTheExistingVersion() {
    var topic = EventCodec.RESTAURANTES;
    tx.executeWithoutResult(s -> stores.record(topic, 1, "INACTIVO", false));
    String body = db.queryForObject("SELECT payload FROM restaurantes.kafka_outbox ORDER BY seq DESC LIMIT 1", String.class);
    stores.apply(topic, "1", body);
    // This schema belongs to the disposable PostgreSQL fixture, not an existing service database.
    db.update("DELETE FROM restaurantes.kafka_projection WHERE entity_id=1");
    assertThat(stores.observation(1)).containsEntry("conocido", false);
    tx.executeWithoutResult(s -> stores.record(topic, 1, "INACTIVO", true));
    body = db.queryForObject("SELECT payload FROM restaurantes.kafka_outbox ORDER BY seq DESC LIMIT 1", String.class);
    assertThat(stores.apply(topic, "1", body)).isEqualTo("APPLIED");
    assertThat(stores.observation(1)).containsEntry("valor", "INACTIVO").containsEntry("version", "1");
  }

  @Test
  void concurrentTransitionsAllocateDistinctPersistentVersions() throws Exception {
    tx.executeWithoutResult(s -> stores.record(EventCodec.RESTAURANTES, 1, "ABIERTO", false));
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var closed = executor.submit(() -> tx.executeWithoutResult(s -> stores.record(EventCodec.RESTAURANTES, 1, "CERRADO", false)));
      var inactive = executor.submit(() -> tx.executeWithoutResult(s -> stores.record(EventCodec.RESTAURANTES, 1, "INACTIVO", false)));
      closed.get(10, java.util.concurrent.TimeUnit.SECONDS);
      inactive.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(db.queryForObject("SELECT version FROM restaurantes.kafka_state WHERE entity_id=1", Long.class)).isEqualTo(3);
    assertThat(db.queryForObject("SELECT count(*) FROM restaurantes.kafka_outbox WHERE entity_id=1", Long.class)).isEqualTo(3);
  }
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
  KafkaStores stores;
  JdbcTemplate db;
  TransactionTemplate tx;
  EventCodec codec = new EventCodec();

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    db = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    db.execute("DROP SCHEMA IF EXISTS restaurantes CASCADE");
    db.execute("CREATE SCHEMA restaurantes");
    String sql =
        Files.readString(
            Path.of(
                "../../services/restaurantes-service/src/main/resources/db/migration/V3__kafka_state_outbox_projection.sql"));
    for (String statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
    stores = new KafkaStores(db, tx, "restaurantes", codec);
  }

  @Test
  void atomicRollbackAndStableVersions() {
    tx.executeWithoutResult(
        s -> {
          stores.record(EventCodec.RESTAURANTES, 1, "ABIERTO", false);
          s.setRollbackOnly();
        });
    assertThat(db.queryForObject("SELECT count(*) FROM restaurantes.kafka_outbox", Long.class))
        .isZero();
    tx.executeWithoutResult(
        s -> {
          stores.record(EventCodec.RESTAURANTES, 1, "ABIERTO", false);
          stores.record(EventCodec.RESTAURANTES, 1, "ABIERTO", false);
          stores.record(EventCodec.RESTAURANTES, 1, "CERRADO", false);
          stores.record(EventCodec.RESTAURANTES, 1, "CERRADO", true);
        });
    assertThat(db.queryForObject("SELECT count(*) FROM restaurantes.kafka_outbox", Long.class))
        .isEqualTo(3);
    assertThat(
            db.queryForObject(
                "SELECT version FROM restaurantes.kafka_state WHERE entity_id=1", Long.class))
        .isEqualTo(2);
  }

  @Test
  void projectionIsIdempotentAndRejectsConflict() {
    var topic = EventCodec.RESTAURANTES;
    String latest = codec.encode(topic, 1, "CERRADO", 2, "CHANGED");
    assertThat(stores.apply(topic, "1", latest)).isEqualTo("APPLIED");
    assertThat(stores.apply(topic, "1", latest)).isEqualTo("DUPLICATE");
    assertThat(stores.apply(topic, "1", codec.encode(topic, 1, "ABIERTO", 1, "INITIALIZED")))
        .isEqualTo("STALE");
    assertThatThrownBy(
            () -> stores.apply(topic, "1", codec.encode(topic, 1, "ABIERTO", 2, "CHANGED")))
        .isInstanceOf(KafkaStores.VersionConflict.class);
    assertThat(stores.observation(1)).containsEntry("valor", "CERRADO");
    assertThat(stores.observation(2)).containsEntry("conocido", false);
  }
}
