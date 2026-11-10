package cl.duoc.pedidos360.kafka;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tables belong to the service database. All names are fixed by trusted configuration. */
public final class KafkaStores {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final String schema;
  private final EventCodec codec;

  public KafkaStores(JdbcTemplate db, TransactionTemplate tx, String schema, EventCodec codec) {
    if (!Set.of("restaurantes", "productos", "carrito").contains(schema))
      throw new IllegalArgumentException("Schema");
    this.db = db;
    this.tx = tx;
    this.schema = schema;
    this.codec = codec;
  }

  public JdbcTemplate db() {
    return db;
  }

  public TransactionTemplate tx() {
    return tx;
  }

  public String table(String name) {
    if (!Set.of("kafka_state", "kafka_outbox", "kafka_projection").contains(name))
      throw new IllegalArgumentException();
    return schema + "." + name;
  }

  public void record(String topic, long id, String state, boolean snapshot) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Outbox requires transaction");
    db.update(
        "INSERT INTO "
            + table("kafka_state")
            + "(entity_id,version,state) VALUES (?,0,?) ON CONFLICT DO NOTHING",
        id,
        state);
    var old =
        db.queryForMap(
            "SELECT version,state FROM " + table("kafka_state") + " WHERE entity_id=? FOR UPDATE",
            id);
    long version = ((Number) old.get("version")).longValue();
    if (!snapshot && version > 0 && Objects.equals(old.get("state"), state)) return;
    if (version == 0 || !Objects.equals(old.get("state"), state))
      version = Math.addExact(version, 1);
    String body =
        codec.encode(
            topic,
            id,
            state,
            version,
            snapshot ? "SNAPSHOT" : version == 1 ? "INITIALIZED" : "CHANGED");
    var event = codec.decode(topic, Long.toString(id), body);
    db.update(
        "UPDATE " + table("kafka_state") + " SET version=?,state=? WHERE entity_id=?",
        version,
        state,
        id);
    db.update(
        "INSERT INTO "
            + table("kafka_outbox")
            + "(event_id,entity_id,topic,payload) VALUES (?,?,?,?)",
        event.eventId(),
        id,
        topic,
        body);
  }

  public String apply(String topic, String key, String body) {
    var e = codec.decode(topic, key, body);
    String state = EventCodec.state(e);
    return tx.execute(
        status -> {
          db.update(
              "INSERT INTO "
                  + table("kafka_projection")
                  + "(entity_id,version,state,event_id,occurred_at,received_at) VALUES (?,0,?,"
                  + " ?,?,now()) ON CONFLICT DO NOTHING",
              Long.parseLong(key),
              state,
              e.eventId(),
              Timestamp.from(e.occurredAt()));
          var old =
              db.queryForMap(
                  "SELECT version,state FROM "
                      + table("kafka_projection")
                      + " WHERE entity_id=? FOR UPDATE",
                  Long.parseLong(key));
          long v = ((Number) old.get("version")).longValue();
          if (v > e.aggregateVersion()) return "STALE";
          if (v == e.aggregateVersion()) {
            if (!Objects.equals(old.get("state"), state)) throw new VersionConflict();
            return "DUPLICATE";
          }
          db.update(
              "UPDATE "
                  + table("kafka_projection")
                  + " SET version=?,state=?,event_id=?,occurred_at=?,received_at=now() WHERE"
                  + " entity_id=?",
              e.aggregateVersion(),
              state,
              e.eventId(),
              Timestamp.from(e.occurredAt()),
              Long.parseLong(key));
          return "APPLIED";
        });
  }

  public Map<String, Object> observation(long id) {
    if (id < 1) throw new IllegalArgumentException("ID");
    var rows =
        db.queryForList(
            "SELECT state,version,occurred_at,received_at FROM "
                + table("kafka_projection")
                + " WHERE entity_id=?",
            id);
    var result = new LinkedHashMap<String, Object>();
    result.put("id", Long.toString(id));
    result.put("conocido", !rows.isEmpty());
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      result.put("valor", row.get("state"));
      result.put("version", row.get("version").toString());
      result.put("ocurridoEn", ((Timestamp) row.get("occurred_at")).toInstant().toString());
      result.put("observadoEn", ((Timestamp) row.get("received_at")).toInstant().toString());
    }
    return result;
  }

  public static final class VersionConflict extends RuntimeException {
    public VersionConflict() {
      super("VERSION_CONFLICT");
    }
  }
}
