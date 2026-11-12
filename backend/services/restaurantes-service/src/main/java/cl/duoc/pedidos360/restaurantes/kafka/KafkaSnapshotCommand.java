package cl.duoc.pedidos360.restaurantes.kafka;

import cl.duoc.pedidos360.kafka.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Explicit operator job; keyset batches, same source locks as live writers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"p360.kafka.enabled", "p360.kafka.snapshot-run"},
    havingValue = "true")
public class KafkaSnapshotCommand {
  @Bean
  ApplicationRunner kafkaSnapshot(KafkaStores stores) {
    return args -> {
      long cursor = 0;
      while (true) {
        var ids =
            stores
                .db()
                .queryForList(
                    "SELECT id FROM restaurantes.restaurantes WHERE id>? ORDER BY id LIMIT 100",
                    Long.class,
                    cursor);
        if (ids.isEmpty()) break;
        for (long id : ids) {
          stores
              .tx()
              .executeWithoutResult(
                  status -> {
                    var rows =
                        stores
                            .db()
                            .queryForList(
                                "SELECT estado::text AS state FROM restaurantes.restaurantes WHERE"
                                    + " id=? FOR UPDATE",
                                id);
                    if (!rows.isEmpty())
                      stores.record(
                          EventCodec.RESTAURANTES,
                          id,
                          rows.getFirst().get("state").toString(),
                          true);
                  });
          cursor = id;
        }
      }
    };
  }
}
