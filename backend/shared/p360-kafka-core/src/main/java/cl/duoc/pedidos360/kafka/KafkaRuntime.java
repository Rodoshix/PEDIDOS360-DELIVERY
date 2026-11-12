package cl.duoc.pedidos360.kafka;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** One ordered publisher, one classic consumer. No background thread until explicitly enabled. */
public final class KafkaRuntime implements SmartLifecycle {
  private final KafkaStores stores;
  private final String output, input, group;
  private final Properties properties;
  private volatile boolean running;
  private ExecutorService workers;
  private KafkaProducer<String, String> producer;
  private volatile KafkaConsumer<String, String> activeConsumer;
  private volatile KafkaConsumerLoop processingLoop;
  private volatile boolean consumerClosed = true, producerClosed = true;
  private final long shutdownMs, taskShutdownMs, consumerCloseMs, producerCloseMs;

  private static long timeout(Properties p, String key, long fallback) {
    long value = Long.parseLong(p.getProperty(key, Long.toString(fallback)));
    if (value < 1 || value > 120000) throw new IllegalArgumentException("Kafka shutdown timeout out of range");
    return value;
  }

  private Properties clientProperties() {
    var p = new Properties();
    properties.forEach((k, v) -> { if (!k.toString().startsWith("p360.")) p.put(k, v); });
    return p;
  }

  public KafkaRuntime(
      KafkaStores stores, Properties properties, String output, String input, String group) {
    this.stores = stores;
    this.properties = properties;
    this.output = output;
    this.input = input;
    this.group = group;
    shutdownMs = timeout(properties, "p360.shutdown.ms", 30000);
    taskShutdownMs = timeout(properties, "p360.task.shutdown.ms", 5000);
    consumerCloseMs = timeout(properties, "p360.consumer.close.ms", 5000);
    producerCloseMs = timeout(properties, "p360.producer.close.ms", 3000);
    if (shutdownMs < taskShutdownMs + consumerCloseMs)
      throw new IllegalArgumentException("Runtime shutdown must cover task and consumer deadlines");
  }

  public static Properties properties(String servers) {
    var p = new Properties();
    p.put("bootstrap.servers", servers);
    p.put("client.id", "p360-kafka");
    p.put("request.timeout.ms", "5000");
    p.put("default.api.timeout.ms", "10000");
    return p;
  }

  public static Properties properties(org.springframework.core.env.Environment env) {
    var p = properties(env.getProperty("p360.kafka.bootstrap-servers", "localhost:19092"));
    KafkaTlsProperties.apply(env, p);
    p.setProperty("p360.shutdown.ms", env.getProperty("p360.kafka.shutdown-timeout-ms", "30000"));
    p.setProperty("p360.task.shutdown.ms", env.getProperty("p360.kafka.task-shutdown-timeout-ms", "5000"));
    p.setProperty("p360.consumer.close.ms", env.getProperty("p360.kafka.consumer-close-timeout-ms", "5000"));
    p.setProperty("p360.producer.close.ms", env.getProperty("p360.kafka.producer-close-timeout-ms", "3000"));
    return p;
  }

  public synchronized void start() {
    if (running) return;
    if (!isShutdownComplete()) throw new IllegalStateException("Previous Kafka shutdown incomplete");
    var p = new Properties();
    p.putAll(clientProperties());
    p.put("key.serializer", StringSerializer.class.getName());
    p.put("value.serializer", StringSerializer.class.getName());
    p.put("acks", "all");
    p.put("enable.idempotence", "true");
    p.put("max.in.flight.requests.per.connection", "1");
    p.put("max.block.ms", "10000");
    p.put("delivery.timeout.ms", "15000");
    producer = new KafkaProducer<>(p);
    producerClosed = false;
    consumerClosed = input == null;
    processingLoop = null;
    running = true;
    workers = Executors.newFixedThreadPool(input == null ? 1 : output == null ? 1 : 2);
    if (output != null) workers.submit(this::publish);
    if (input != null) workers.submit(this::consume);
  }

  private void publish() {
    while (running) {
      try {
        stores
            .tx()
            .executeWithoutResult(
                status -> {
                  // Advisory transaction lock elects a publisher across replicas; never skip older
                  // records.
                  if (!Boolean.TRUE.equals(
                      stores
                          .db()
                          .queryForObject(
                              "SELECT pg_try_advisory_xact_lock(?)",
                              Boolean.class,
                              (long) stores.table("kafka_outbox").hashCode()))) return;
                  var rows =
                      stores
                          .db()
                          .queryForList(
                              "SELECT seq,entity_id,topic,payload,next_attempt<=now() AS ready FROM"
                                  + " "
                                  + stores.table("kafka_outbox")
                                  + " WHERE sent_at IS NULL ORDER BY seq LIMIT 1 FOR UPDATE");
                  if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("ready"))) return;
                  var row = rows.getFirst();
                  try {
                    producer
                        .send(
                            new ProducerRecord<>(
                                row.get("topic").toString(),
                                row.get("entity_id").toString(),
                                row.get("payload").toString()))
                        .get(20, TimeUnit.SECONDS);
                    stores
                        .db()
                        .update(
                            "UPDATE "
                                + stores.table("kafka_outbox")
                                + " SET sent_at=now() WHERE seq=?",
                            row.get("seq"));
                  } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    stores
                        .db()
                        .update(
                            "UPDATE "
                                + stores.table("kafka_outbox")
                                + " SET attempts=attempts+1,next_attempt=now()+interval '30"
                                + " seconds' WHERE seq=?",
                            row.get("seq"));
                    LoggerFactory.getLogger(getClass())
                        .warn("Kafka outbox publication pending; no payload logged");
                  }
                });
      } catch (RuntimeException e) {
        LoggerFactory.getLogger(getClass()).warn("Kafka outbox database unavailable");
      }
      sleep(500);
    }
  }

  private void consume() {
    var p = new Properties();
    p.putAll(clientProperties());
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    p.put("group.id", group);
    p.put("group.protocol", "classic");
    p.put("enable.auto.commit", "false");
    p.put("auto.offset.reset", "earliest");
    p.put("max.poll.records", "1");
    p.put("max.partition.fetch.bytes", "65536");
    p.put("fetch.max.bytes", "65536");
    p.put("max.poll.interval.ms", "300000");
    KafkaConsumer<String, String> consumer = null;
    boolean restoreInterrupt = false;
    try {
      consumer = new KafkaConsumer<>(p);
      activeConsumer = consumer;
      var loop = new KafkaConsumerLoop(consumer, input,
          r -> stores.apply(r.topic(), r.key(), r.value()), this::terminal,
          System::nanoTime, Duration.ofMillis(taskShutdownMs));
      processingLoop = loop;
      while (running) {
        try {
          loop.tick();
        } catch (org.apache.kafka.common.errors.WakeupException wakeup) {
          if (running) LoggerFactory.getLogger(getClass()).warn("Kafka consumer wakeup while active");
        } catch (org.apache.kafka.common.errors.InterruptException interrupted) {
          restoreInterrupt = true;
          break;
        } catch (RuntimeException failure) {
          LoggerFactory.getLogger(getClass()).warn("Kafka consumer retry; offset not acknowledged");
          sleep(1000);
        }
      }
    } catch (RuntimeException failure) {
      LoggerFactory.getLogger(getClass()).error("Kafka consumer worker failed; offsets not acknowledged");
    } finally {
      // All KafkaConsumer operations except wakeup stay on its owner thread.
      restoreInterrupt |= Thread.interrupted();
      if (processingLoop != null) {
        processingLoop.close();
        restoreInterrupt |= Thread.interrupted();
      }
      if (consumer != null) {
        try {
          consumer.close(Duration.ofMillis(consumerCloseMs));
          consumerClosed = true;
        } catch (RuntimeException failure) {
          LoggerFactory.getLogger(getClass()).error("Kafka consumer shutdown incomplete: close failed");
        }
      } else consumerClosed = true;
      activeConsumer = null;
      if (restoreInterrupt) Thread.currentThread().interrupt();
    }
  }

  private boolean terminal(ConsumerRecord<String, String> r) {
    // Metadata only: even malformed input may contain secrets. Never echo raw input.
    String value =
        "{\"code\":\"INVALID_OR_CONFLICTING_EVENT\",\"topic\":\""
            + r.topic()
            + "\",\"partition\":"
            + r.partition()
            + ",\"offset\":"
            + r.offset()
            + "}";
    try {
      producer
          .send(new ProducerRecord<>(input + ".dlt", Integer.toString(r.partition()), value))
          .get(20, TimeUnit.SECONDS);
      return true;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      LoggerFactory.getLogger(getClass())
          .warn("Kafka terminal publication failed; offset not committed");
      return false;
    }
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public synchronized void stop() {
    running = false;
    var loop = processingLoop;
    if (loop != null) loop.requestStop();
    var consumer = activeConsumer;
    if (consumer != null) {
      try { consumer.wakeup(); }
      catch (RuntimeException failure) {
        LoggerFactory.getLogger(getClass()).warn("Kafka stop wakeup failed; waiting for owner thread");
      }
    }
    boolean interrupted = Thread.interrupted();
    if (workers != null) {
      workers.shutdown();
      try {
        if (!workers.awaitTermination(shutdownMs, TimeUnit.MILLISECONDS)) {
          workers.shutdownNow();
          LoggerFactory.getLogger(getClass()).error("Kafka runtime shutdown incomplete: worker deadline exceeded");
        }
      } catch (InterruptedException failure) {
        interrupted = true;
        workers.shutdownNow();
        LoggerFactory.getLogger(getClass()).error("Kafka runtime shutdown incomplete: wait interrupted");
      }
    }
    if (producer != null && !producerClosed) {
      try {
        producer.close(Duration.ofMillis(producerCloseMs));
        producerClosed = true;
      } catch (RuntimeException failure) {
        LoggerFactory.getLogger(getClass()).error("Kafka producer shutdown incomplete: close failed");
      }
    }
    if (!isShutdownComplete())
      LoggerFactory.getLogger(getClass()).error("Kafka shutdown INCOMPLETE; active work may remain, uncommitted records require redelivery");
    else LoggerFactory.getLogger(getClass()).info("Kafka shutdown complete");
    if (interrupted) Thread.currentThread().interrupt();
  }

  public boolean isShutdownComplete() {
    return (workers == null || workers.isTerminated())
        && (processingLoop == null || processingLoop.isTerminated())
        && consumerClosed && producerClosed;
  }

  public boolean isRunning() {
    return running;
  }

  public int getPhase() {
    return Integer.MAX_VALUE - 10;
  }
}
