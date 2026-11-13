package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class KafkaTransactionRecoveryTests {
  @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine");

  @Test @SuppressWarnings("unchecked")
  void connectionAcquisitionFailureInsideRealTransactionIsNeverTerminal() throws Exception {
    var real=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
    var db=new JdbcTemplate(real);
    db.execute("CREATE SCHEMA restaurantes");
    for(String sql:Files.readString(Path.of("../../services/restaurantes-service/src/main/resources/db/migration/V3__kafka_state_outbox_projection.sql")).split(";"))
      if(!sql.isBlank())db.execute(sql);
    var offline=new AtomicBoolean(true);var failedConnections=new AtomicInteger();
    var gate=new AbstractDataSource() {
      public Connection getConnection() throws SQLException {
        if(offline.get()){failedConnections.incrementAndGet();throw new SQLTransientConnectionException("connection unavailable");}
        return real.getConnection();
      }
      public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
    };
    var tx=new TransactionTemplate(new DataSourceTransactionManager(gate));
    assertThatThrownBy(()->tx.execute(status->1)).isInstanceOf(CannotCreateTransactionException.class);
    var codec=new EventCodec();var stores=new KafkaStores(new JdbcTemplate(gate),tx,"restaurantes",codec);
    String topic=EventCodec.RESTAURANTES;
    var tp=new TopicPartition(topic,0);
    var record=new ConsumerRecord<>(topic,0,10,"42",codec.encode(topic,42,"CERRADO",1,"INITIALIZED"));
    Consumer<String,String> c=mock(Consumer.class);
    when(c.assignment()).thenReturn(Set.of(tp));
    when(c.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(tp,List.of(record))),ConsumerRecords.empty());
    var clock=new AtomicLong();var terminal=new AtomicInteger();
    try(var loop=new KafkaConsumerLoop(c,topic,r->stores.apply(r.topic(),r.key(),r.value()),r->{terminal.incrementAndGet();return true;},clock::get)) {
      await().pollInterval(Duration.ofMillis(5)).until(()->{loop.tick();clock.addAndGet(Duration.ofSeconds(5).toNanos());return failedConnections.get()>=7;});
      assertThat(terminal).hasValue(0);verify(c,never()).commitSync(anyMap());
      assertThat(db.queryForObject("SELECT count(*) FROM restaurantes.kafka_projection",Long.class)).isZero();
      offline.set(false);
      await().untilAsserted(()->{
        loop.tick();clock.addAndGet(Duration.ofSeconds(5).toNanos());
        verify(c).commitSync(Map.of(tp,new OffsetAndMetadata(11)));
        assertThat(stores.observation(42)).containsEntry("valor","CERRADO").containsEntry("version","1");
      });
      assertThat(terminal).hasValue(0);
    }
  }
  @Test @SuppressWarnings("unchecked")
  void realJdbcQueryOutlivesCloseDeadlineWithoutUnsafeAcknowledgement() throws Exception {
    var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
    var db=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    db.execute("CREATE SCHEMA productos");
    for(String sql:Files.readString(Path.of("../../services/restaurantes-service/src/main/resources/db/migration/V3__kafka_state_outbox_projection.sql")).replace("restaurantes.","productos.").split(";"))
      if(!sql.isBlank())db.execute(sql);
    var codec=new EventCodec();var stores=new KafkaStores(db,tx,"productos",codec);
    var tp=new TopicPartition(EventCodec.PRODUCTOS,0);
    var record=new ConsumerRecord<>(tp.topic(),0,20,"81",codec.encode(tp.topic(),81,"false",1,"INITIALIZED"));
    Consumer<String,String> c=mock(Consumer.class);
    when(c.assignment()).thenReturn(Set.of(tp));
    when(c.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(tp,List.of(record))),ConsumerRecords.empty());
    var loop=new KafkaConsumerLoop(c,tp.topic(),r->tx.executeWithoutResult(status->{
      db.execute("SELECT pg_sleep(0.8)");
      stores.apply(r.topic(),r.key(),r.value());
    }),r->{throw new AssertionError("valid data");},System::nanoTime,Duration.ofMillis(50));
    try {
      loop.tick();
      await().pollInterval(Duration.ofMillis(5)).until(()->db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE state='active' AND query='SELECT pg_sleep(0.8)'",Long.class)>0);
      loop.close();assertThat(loop.isTerminated()).isFalse();verify(c,never()).commitSync(anyMap());
      await().atMost(Duration.ofSeconds(5)).until(loop::isTerminated);
      assertThat(stores.observation(81)).containsEntry("valor","false").containsEntry("version","1");
      loop.tick();verify(c,never()).commitSync(anyMap());
    } finally {loop.close();}
  }

}
