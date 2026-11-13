package cl.duoc.pedidos360.kafka;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.slf4j.LoggerFactory;

/** Consumer-thread ownership, one bounded in-flight task, polling even while persistence is blocked. */
final class KafkaConsumerLoop implements AutoCloseable {
  private final Consumer<String, String> consumer;
  private final java.util.function.Consumer<ConsumerRecord<String, String>> apply;
  private final Predicate<ConsumerRecord<String, String>> terminal;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final LongSupplier clock;
  private final Duration shutdownTimeout;
  private final Object lifecycle = new Object();
  private volatile boolean stopping;
  private boolean suspended;
  private ConsumerRecord<String, String> pending;
  private Future<Boolean> attempt;
  private boolean terminalOnly;
  private int failures;
  private long nextAttempt, generation, pendingGeneration;

  KafkaConsumerLoop(Consumer<String, String> consumer, String topic,
      java.util.function.Consumer<ConsumerRecord<String, String>> apply,
      Predicate<ConsumerRecord<String, String>> terminal) {
    this(consumer, topic, apply, terminal, System::nanoTime, Duration.ofSeconds(5));
  }

  KafkaConsumerLoop(Consumer<String, String> consumer, String topic,
      java.util.function.Consumer<ConsumerRecord<String, String>> apply,
      Predicate<ConsumerRecord<String, String>> terminal, LongSupplier clock) {
    this(consumer, topic, apply, terminal, clock, Duration.ofSeconds(5));
  }

  KafkaConsumerLoop(Consumer<String, String> consumer, String topic,
      java.util.function.Consumer<ConsumerRecord<String, String>> apply,
      Predicate<ConsumerRecord<String, String>> terminal, LongSupplier clock, Duration shutdownTimeout) {
    if (shutdownTimeout.isNegative()) throw new IllegalArgumentException("shutdown timeout");
    this.shutdownTimeout = shutdownTimeout;
    this.consumer = consumer;
    this.apply = apply;
    this.terminal = terminal;
    this.clock = clock;
    consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
      public void onPartitionsRevoked(Collection<TopicPartition> partitions) { generation++; }
      public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        generation++;
        if (pending != null) consumer.pause(partitions);
      }
      public void onPartitionsLost(Collection<TopicPartition> partitions) { generation++; }
    });
  }

  void tick() {
    if (stopping) return;
    if (pending != null) consumer.pause(consumer.assignment());
    var records = consumer.poll(Duration.ofMillis(100));
    if (stopping) return;
    if (pending == null && !records.isEmpty()) {
      // Runtime enforces max.poll.records=1. Reject a misconfigured caller, never skip records.
      if (records.count() != 1) throw new IllegalStateException("max.poll.records must be 1");
      pending = records.iterator().next();
      pendingGeneration = generation;
      consumer.pause(consumer.assignment());
    }
    if (pending == null) return;
    if (attempt != null && attempt.isDone()) {
      boolean done = false;
      try {
        done = attempt.get();
        if (!done) schedule(); // Failed DLT: keep terminal-only mode and original offset.
      } catch (ExecutionException failure) {
        Throwable cause = failure.getCause();
        switch (classify(cause)) {
          case TERMINAL -> { terminalOnly = true; nextAttempt = 0; }
          case INFRASTRUCTURE -> {
            LoggerFactory.getLogger(getClass()).warn("Kafka infrastructure unavailable; polling continues, offset pending");
            schedule();
          }
          case UNKNOWN -> {
            if (++failures >= 3) {
              suspended = true;
              LoggerFactory.getLogger(getClass()).error("Kafka processing suspended after unknown failures; operator repair required, no DLT or offset commit");
            } else schedule();
          }
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } finally {
        attempt = null;
      }
      if (pendingGeneration != generation) {
        // A revoked record may have persisted; the new owner replays it idempotently.
        rewindAndClear();
        return;
      }
      if (done && !stopping) {
        var tp = new TopicPartition(pending.topic(), pending.partition());
        // Keep pending on commit failure; reapply safely on the next attempt.
        consumer.commitSync(Map.of(tp, new OffsetAndMetadata(pending.offset() + 1)));
        clear();
        return;
      }
    }
    if (pendingGeneration != generation && attempt == null) {
      rewindAndClear();
      return;
    }
    synchronized (lifecycle) {
      if (!stopping && !suspended && attempt == null
          && (nextAttempt == 0 || clock.getAsLong() - nextAttempt >= 0)) {
        var record = pending;
        boolean toTerminal = terminalOnly;
        attempt = executor.submit(() -> {
          if (toTerminal) return terminal.test(record);
          apply.accept(record);
          return true;
        });
      }
    }
  }

  enum FailureKind { TERMINAL, INFRASTRUCTURE, UNKNOWN }

  static FailureKind classify(Throwable cause) {
    if (cause instanceof EventCodec.InvalidEvent || cause instanceof KafkaStores.VersionConflict
        || cause instanceof DataIntegrityViolationException) return FailureKind.TERMINAL;
    if (cause instanceof DataAccessException || cause instanceof CannotCreateTransactionException
        || cause instanceof TransactionSystemException || cause instanceof TransactionTimedOutException
        || cause instanceof UnexpectedRollbackException) return FailureKind.INFRASTRUCTURE;
    return FailureKind.UNKNOWN;
  }

  private void schedule() {
    nextAttempt = clock.getAsLong() + Duration.ofSeconds(5).toNanos();
  }

  private void rewindAndClear() {
    var tp = new TopicPartition(pending.topic(), pending.partition());
    // A partition retained/reassigned to this instance can still hold an advanced local position.
    // Rewind before resuming, even if persistence already succeeded: replay is idempotent.
    if (consumer.assignment().contains(tp)) consumer.seek(tp, pending.offset());
    clear();
  }

  private void clear() {
    pending = null;
    terminalOnly = false;
    failures = 0;
    suspended = false;
    nextAttempt = 0;
    consumer.resume(consumer.assignment());
  }

  void requestStop() {
    synchronized (lifecycle) { stopping = true; }
  }

  boolean isTerminated() { return executor.isTerminated(); }

  public void close() {
    requestStop();
    boolean interrupted = Thread.interrupted();
    executor.shutdown();
    try {
      if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
        LoggerFactory.getLogger(getClass()).error("Kafka processing shutdown incomplete: task deadline exceeded; offsets remain uncommitted");
      }
    } catch (InterruptedException failure) {
      interrupted = true;
      executor.shutdownNow();
      LoggerFactory.getLogger(getClass()).error("Kafka processing shutdown incomplete: wait interrupted; offsets remain uncommitted");
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
