package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class KafkaConsumerLoopTests {
  final TopicPartition tp = new TopicPartition(EventCodec.PRODUCTOS, 0);
  final ConsumerRecord<String,String> record = new ConsumerRecord<>(tp.topic(), 0, 10, "42", "body");
  @SuppressWarnings("unchecked")
  Consumer<String,String> consumer() {
    Consumer<String,String> c = mock(Consumer.class);
    when(c.assignment()).thenReturn(Set.of(tp));
    when(c.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(tp,List.of(record))), ConsumerRecords.empty());
    return c;
  }
  void spin(KafkaConsumerLoop loop, AtomicLong clock) {
    loop.tick(); clock.addAndGet(Duration.ofSeconds(5).toNanos());
  }

  @Test void prolongedDatabaseFailureKeepsPollingAndOnlyCommitsAfterRecovery() {
    var c = consumer(); var clock = new AtomicLong(); var available = new AtomicBoolean();
    var calls = new AtomicInteger(); var persisted = new AtomicBoolean();
    try(var loop = new KafkaConsumerLoop(c,tp.topic(), r -> {
      calls.incrementAndGet();
      if(!available.get()) throw new DataAccessResourceFailureException("offline");
      persisted.set(true);
    },r -> {throw new AssertionError("valid data must not go to DLT");},clock::get)) {
      await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(5)).until(() -> {spin(loop,clock);return calls.get()>=70;});
      assertThat(clock.get()).isGreaterThan(Duration.ofMinutes(5).toNanos());
      verify(c,atLeast(70)).poll(any(Duration.class)); verify(c,never()).commitSync(anyMap());
      available.set(true);
      await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
        spin(loop,clock); assertThat(persisted).isTrue(); verify(c).commitSync(Map.of(tp,new OffsetAndMetadata(11)));
      });
    }
  }

  @Test void blockedPersistenceNeverBlocksPollingOrAcknowledgesEarly() throws Exception {
    var c=consumer();var started=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var loop=new KafkaConsumerLoop(c,tp.topic(),r->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}},r->true)) {
      loop.tick();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
      for(int i=0;i<100;i++)loop.tick();
      verify(c,atLeast(101)).poll(any(Duration.class));verify(c,never()).commitSync(anyMap());
      release.countDown();
      await().untilAsserted(()->{loop.tick();verify(c).commitSync(anyMap());});
    } finally {release.countDown();}
  }

  @Test void invalidContractAndTerminalFailureDoesNotCommitOrReapply() {
    var c=consumer();var clock=new AtomicLong();var applied=new AtomicInteger();var terminalCalls=new AtomicInteger();var terminalAvailable=new AtomicBoolean();
    try(var loop=new KafkaConsumerLoop(c,tp.topic(),r->{applied.incrementAndGet();throw new EventCodec.InvalidEvent();},r->{terminalCalls.incrementAndGet();return terminalAvailable.get();},clock::get)) {
      await().until(()->{spin(loop,clock);return terminalCalls.get()>=5;});
      assertThat(applied).hasValue(1);verify(c,never()).commitSync(anyMap());
      terminalAvailable.set(true);
      await().untilAsserted(()->{spin(loop,clock);verify(c).commitSync(Map.of(tp,new OffsetAndMetadata(11)));});
      assertThat(applied).hasValue(1);
    }
  }

  @Test void revokedRecordCannotCommitIntoNewAssignment() {
    var c=consumer();var clock=new AtomicLong();var applied=new AtomicInteger();
    try(var loop=new KafkaConsumerLoop(c,tp.topic(),r->applied.incrementAndGet(),r->true,clock::get)) {
      var listener=org.mockito.ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
      verify(c).subscribe(eq(List.of(tp.topic())),listener.capture());
      loop.tick();listener.getValue().onPartitionsRevoked(Set.of(tp));listener.getValue().onPartitionsAssigned(Set.of(tp));
      await().until(()->{spin(loop,clock);return applied.get()==1;});
      await().untilAsserted(()->{loop.tick();verify(c).seek(tp,10L);});
      verify(c,never()).commitSync(anyMap());
    }
  }
  @Test void failedOffsetCommitReplaysPersistenceBeforeRetryingAcknowledgement() {
    var c=consumer();var applied=new AtomicInteger();
    doThrow(new org.apache.kafka.clients.consumer.CommitFailedException("retry"))
        .doNothing().when(c).commitSync(anyMap());
    try(var loop=new KafkaConsumerLoop(c,tp.topic(),r->applied.incrementAndGet(),r->true)) {
      await().untilAsserted(()->{
        try {loop.tick();} catch(org.apache.kafka.clients.consumer.CommitFailedException expected) {}
        verify(c,times(2)).commitSync(Map.of(tp,new OffsetAndMetadata(11)));
      });
      assertThat(applied).hasValue(2);
    }
  }

  @Test void invalidContractGoesDirectlyToTerminalWithoutRetryingApplication() {
    var c=consumer();var applied=new AtomicInteger();var terminalCalls=new AtomicInteger();
    try(var loop=new KafkaConsumerLoop(c,tp.topic(),r->{applied.incrementAndGet();throw new EventCodec.InvalidEvent();},r->{terminalCalls.incrementAndGet();return true;})) {
      await().untilAsserted(()->{loop.tick();verify(c).commitSync(Map.of(tp,new OffsetAndMetadata(11)));});
      assertThat(applied).hasValue(1);assertThat(terminalCalls).hasValue(1);
    }
  }

  @Test void unknownFailureSuspendsAfterThreeAttemptsWithoutDiscardingData() {
    var c=consumer();var clock=new AtomicLong();var applied=new AtomicInteger();var terminalCalls=new AtomicInteger();
    try(var loop=new KafkaConsumerLoop(c,tp.topic(),r->{applied.incrementAndGet();throw new IllegalArgumentException("unknown");},r->{terminalCalls.incrementAndGet();return true;},clock::get)) {
      await().until(()->{spin(loop,clock);return applied.get()==3;});
      for(int i=0;i<100;i++)spin(loop,clock);
      assertThat(applied).hasValue(3);assertThat(terminalCalls).hasValue(0);verify(c,never()).commitSync(anyMap());
    }
  }

  @Test void infrastructureClassificationIsExplicit() {
    for(Throwable failure:List.of(new org.springframework.transaction.CannotCreateTransactionException("open"),
        new org.springframework.transaction.TransactionSystemException("commit"),
        new org.springframework.transaction.TransactionTimedOutException("timeout"),
        new org.springframework.transaction.UnexpectedRollbackException("rollback")))
      assertThat(KafkaConsumerLoop.classify(failure)).isEqualTo(KafkaConsumerLoop.FailureKind.INFRASTRUCTURE);
    assertThat(KafkaConsumerLoop.classify(new EventCodec.InvalidEvent())).isEqualTo(KafkaConsumerLoop.FailureKind.TERMINAL);
    assertThat(KafkaConsumerLoop.classify(new IllegalArgumentException())).isEqualTo(KafkaConsumerLoop.FailureKind.UNKNOWN);
  }

  @Test void closeWithoutWorkTerminatesAndRejectsNewWork() {
    var c=consumer();var loop=new KafkaConsumerLoop(c,tp.topic(),r->{throw new AssertionError();},r->true);
    loop.close();assertThat(loop.isTerminated()).isTrue();loop.tick();
    verify(c,never()).poll(any(Duration.class));verify(c,never()).commitSync(anyMap());
  }

  @Test void closeWaitsForActiveWorkButDoesNotAcknowledgeAfterStop() throws Exception {
    var c=consumer();var started=new CountDownLatch(1);var release=new CountDownLatch(1);
    var loop=new KafkaConsumerLoop(c,tp.topic(),r->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}},r->true);
    try {
      loop.tick();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
      try(var closer=Executors.newSingleThreadExecutor()) {
        var closed=closer.submit(loop::close);
        Thread.sleep(30);assertThat(closed.isDone()).isFalse();
        release.countDown();closed.get(2,TimeUnit.SECONDS);
      }
      assertThat(loop.isTerminated()).isTrue();loop.tick();verify(c,never()).commitSync(anyMap());
    } finally {release.countDown();loop.close();}
  }

  @Test void closeTimeoutRecognizesTaskIgnoringInterruptsAndAllowsRedelivery() throws Exception {
    var c=consumer();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var persisted=new AtomicBoolean();
    var loop=new KafkaConsumerLoop(c,tp.topic(),r->{started.countDown();
      while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){}
      persisted.set(true);
    },r->true,System::nanoTime,Duration.ofMillis(50));
    try {
      loop.tick();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
      loop.close();assertThat(loop.isTerminated()).isFalse();loop.tick();verify(c,never()).commitSync(anyMap());
      release.countDown();await().until(loop::isTerminated);
      assertThat(persisted).isTrue();
      var replay=consumer();
      try(var recovered=new KafkaConsumerLoop(replay,tp.topic(),r->assertThat(persisted).isTrue(),r->true)) {
        await().untilAsserted(()->{recovered.tick();verify(replay).commitSync(Map.of(tp,new OffsetAndMetadata(11)));});
      }
    } finally {release.countDown();loop.close();}
  }

  @Test void closePreservesCallerInterruptionAfterReleasingResources() {
    var c=consumer();var loop=new KafkaConsumerLoop(c,tp.topic(),r->{throw new AssertionError();},r->true);
    try {
      Thread.currentThread().interrupt();loop.close();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(loop.isTerminated()).isTrue();verify(c,never()).commitSync(anyMap());
    } finally {Thread.interrupted();loop.close();}
  }

}
