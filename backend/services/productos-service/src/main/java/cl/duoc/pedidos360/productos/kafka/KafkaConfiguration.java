package cl.duoc.pedidos360.productos.kafka;

import cl.duoc.pedidos360.kafka.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "p360.kafka.enabled", havingValue = "true")
public class KafkaConfiguration {
  @Bean
  EventCodec kafkaCodec() {
    return new EventCodec();
  }

  @Bean
  KafkaStores kafkaStores(JdbcTemplate jdbc, PlatformTransactionManager tm, EventCodec codec) {
    return new KafkaStores(jdbc, new TransactionTemplate(tm), "productos", codec);
  }

  @Bean
  KafkaRuntime kafkaRuntime(KafkaStores stores, Environment env) {
    return new KafkaRuntime(
        stores,
        KafkaRuntime.properties(env),
        EventCodec.PRODUCTOS,
        EventCodec.RESTAURANTES,
        "p360-productos-restaurantes-estado-v1");
  }
}
