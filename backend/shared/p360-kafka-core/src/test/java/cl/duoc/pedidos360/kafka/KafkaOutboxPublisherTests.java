package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Pure publisher tests: no DataSource, database, broker, Docker or Spring context. */
class KafkaOutboxPublisherTests {
  enum Failure { SEND_THROWS, ACK_FAILS, ACK_TIMES_OUT }

  static Stream<Arguments> contractsAndFailures() {
    return Stream.of("restaurantes", "productos")
        .flatMap(schema -> Arrays.stream(Failure.values()).map(f -> Arguments.of(schema, f)));
  }

  @ParameterizedTest(name = "{0}: {1} stays pending and retries the same event")
  @MethodSource("contractsAndFailures")
  @Timeout(15)
  @SuppressWarnings({"unchecked", "rawtypes"})
  void failureIsNotConfirmationAndRecoveryKeepsEvent(String schema, Failure failure)
      throws Exception {
    var db = mock(JdbcTemplate.class);
    var tx = mock(TransactionTemplate.class);
    doAnswer(invocation -> {
      Consumer<TransactionStatus> callback = invocation.getArgument(0);
      callback.accept(mock(TransactionStatus.class));
      return null;
    }).when(tx).executeWithoutResult(any());
    var stores = new KafkaStores(db, tx, schema, new EventCodec());
    String table = stores.table("kafka_outbox");
    String topic = schema.equals("restaurantes") ? EventCodec.RESTAURANTES : EventCodec.PRODUCTOS;
    String payload = new EventCodec().encode(topic, 77,
        schema.equals("restaurantes") ? "CERRADO" : "false", 1, "INITIALIZED");
    UUID eventId = new EventCodec().decode(topic, "77", payload).eventId();
    String deliveredSql = "UPDATE " + table + " SET sent_at=now() WHERE seq=?";
    String retrySql = "UPDATE " + table
        + " SET attempts=attempts+1,next_attempt=now()+interval '30 seconds' WHERE seq=?";
    var ready = new AtomicBoolean(true);
    var delivered = new AtomicBoolean();
    var attempts = new AtomicInteger();
    var ackEntered = new CountDownLatch(1);
    var releaseAck = new CountDownLatch(1);
    var retryRecorded = new CountDownLatch(1);
    var deliveryRecorded = new CountDownLatch(1);
    when(db.queryForObject(anyString(), eq(Boolean.class), any(Object.class))).thenReturn(true);
    when(db.queryForList(anyString())).thenAnswer(invocation -> delivered.get() ? List.of()
        : List.of(Map.of("seq", 11L, "entity_id", 77L, "topic", topic,
            "payload", payload, "ready", ready.get())));
    when(db.update(eq(retrySql), eq(11L))).thenAnswer(invocation -> {
      ready.set(false);
      attempts.incrementAndGet();
      retryRecorded.countDown();
      return 1;
    });
    when(db.update(eq(deliveredSql), eq(11L))).thenAnswer(invocation -> {
      delivered.set(true);
      deliveryRecorded.countDown();
      return 1;
    });
    Future<RecordMetadata> failedAck = mock(Future.class);
    when(failedAck.get(20, TimeUnit.SECONDS)).thenAnswer(invocation -> {
      ackEntered.countDown();
      if (!releaseAck.await(5, TimeUnit.SECONDS)) throw new AssertionError("Ack gate deadline");
      if (failure == Failure.ACK_TIMES_OUT) throw new TimeoutException("synthetic missing ack");
      throw new ExecutionException(new IllegalStateException("synthetic rejected publication"));
    });
    Future<RecordMetadata> successfulAck = mock(Future.class);
    when(successfulAck.get(20, TimeUnit.SECONDS)).thenReturn(mock(RecordMetadata.class));
    try (var construction = mockConstruction(KafkaProducer.class, (producer, context) -> {
      var sends = new AtomicInteger();
      when(producer.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
        if (sends.incrementAndGet() == 1) {
          if (failure == Failure.SEND_THROWS) throw new IllegalStateException("synthetic send failure");
          return failedAck;
        }
        return successfulAck;
      });
    })) {
      var runtime = new KafkaRuntime(stores, KafkaRuntime.properties("unused.invalid:1"),
          topic, null, null);
      try {
        runtime.start(); // KafkaProducer construction is intercepted; no network client is started.
        var producer = construction.constructed().getFirst();
        if (failure != Failure.SEND_THROWS) {
          assertThat(ackEntered.await(5, TimeUnit.SECONDS)).isTrue();
          verify(db, never()).update(eq(deliveredSql), eq(11L));
          assertThat(attempts.get()).isZero();
          releaseAck.countDown();
        }
        assertThat(retryRecorded.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered.get()).isFalse();
        assertThat(attempts.get()).isEqualTo(1);
        verify(db, never()).update(eq(deliveredSql), eq(11L));
        verify(producer, times(1)).send(any(ProducerRecord.class));
        // Only the mocked row's eligibility advances; no wall-clock wait or policy change.
        ready.set(true);
        assertThat(deliveryRecorded.await(5, TimeUnit.SECONDS)).isTrue();
        var records = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer, times(2)).send(records.capture());
        assertThat(records.getAllValues()).allSatisfy(record -> {
          assertThat(record.topic()).isEqualTo(topic);
          assertThat(record.key()).isEqualTo("77");
          assertThat(record.value()).isEqualTo(payload);
          assertThat(new EventCodec().decode(topic, "77", (String) record.value()).eventId())
              .isEqualTo(eventId);
        });
        var order = inOrder(producer, successfulAck, db);
        order.verify(db).update(eq(retrySql), eq(11L));
        order.verify(producer).send(any(ProducerRecord.class));
        order.verify(successfulAck).get(20, TimeUnit.SECONDS);
        order.verify(db).update(eq(deliveredSql), eq(11L));
        assertThat(attempts.get()).isEqualTo(1);
      } finally {
        releaseAck.countDown();
        runtime.stop();
        assertThat(runtime.isShutdownComplete()).isTrue();
      }
      // Only the pending retry and confirmed-delivery updates are permitted by this unit contract.
      verify(db).update(eq(retrySql), eq(11L));
      verify(db).update(eq(deliveredSql), eq(11L));
      verify(db, atLeastOnce()).queryForList(anyString());
      verify(db, atLeastOnce()).queryForObject(anyString(), eq(Boolean.class), any(Object.class));
      verifyNoMoreInteractions(db);
    }
  }
}
