package cl.duoc.pedidos360.carrito.messaging;

import cl.duoc.pedidos360.messaging.command.*;
import com.rabbitmq.client.Channel;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "pedidos360.messaging.carrito",
    name = "mode",
    havingValue = "RABBITMQ")
public class CarritoCommandListener {
  private final CarritoReceiptStore receipts;
  private final CarritoCommandProperties config;
  private final RabbitTemplate rabbit;
  private final CarritoConsumerRecovery recovery;
  private final UUID tenant;

  public CarritoCommandListener(
      CarritoReceiptStore s,
      CarritoCommandProperties c,
      @org.springframework.beans.factory.annotation.Qualifier("carritoCommandTemplate")
          RabbitTemplate r,
      CarritoConsumerRecovery recovery,
      org.springframework.core.env.Environment env) {
    receipts = s;
    config = c;
    rabbit = r;
    this.recovery = recovery;
    tenant = UUID.fromString(env.getRequiredProperty("entra.tenant-id"));
  }

  @RabbitListener(
      id = CarritoConsumerRecovery.LISTENER_ID,
      queues = CarritoCommandProperties.QUEUE,
      containerFactory = "carritoCommandListenerFactory")
  public void consume(Message m, Channel channel) {
    VaciarCarritoPorPedido command;
    boolean retry;
    try {
      var p = m.getMessageProperties();
      Object count = p.getHeaders().get("retry-count");
      if (!(count instanceof Byte
          || count instanceof Short
          || count instanceof Integer
          || count instanceof Long)) throw new InvalidCartCommand();
      long attempt = ((Number) count).longValue();
      if (attempt < 0 || attempt > 1) throw new InvalidCartCommand();
      retry = attempt == 1;
      if (!CarritoCommandProperties.ROUTE.equals(p.getReceivedRoutingKey())
          || !"application/json".equals(p.getContentType())
          || !CarritoCommandProperties.EXCHANGE.equals(p.getReceivedExchange())
          || !(retry ? config.consumerUser() : config.publisherUser())
              .equals(p.getReceivedUserId())) throw new InvalidCartCommand();
      try {
        command = VaciarCarritoPorPedido.leer(m.getBody());
      } catch (RuntimeException invalid) {
        throw new InvalidCartCommand();
      }
      if (!command.messageId().toString().equals(p.getMessageId())
          || !tenant.equals(command.propietario().tenantId())) throw new InvalidCartCommand();
    } catch (InvalidCartCommand invalid) {
      diagnostic(m, channel);
      return;
    }
    try {
      receipts.accept(command, retry);
    } catch (InvalidCartCommand invalid) {
      diagnostic(m, channel);
      return;
    } catch (RuntimeException uncertain) {
      recover();
      return;
    } // Never retry without a durable authenticated receipt.
    CarritoReceiptStore.Result result;
    try {
      result = receipts.process(command, retry);
    } catch (RuntimeException failure) {
      if (!(failure instanceof org.springframework.dao.TransientDataAccessException
          || failure instanceof org.springframework.orm.ObjectOptimisticLockingFailureException)) {
        recover();
        return;
      }
      try {
        if (retry) {
          receipts.reject(command);
          diagnostic(m, channel);
        } else {
          receipts.authorizeRetry(command);
          handoff(m, channel, true);
        }
      } catch (RuntimeException uncertain) {
        recover();
      }
      return;
    }
    if (result == CarritoReceiptStore.Result.REJECTED) {
      diagnostic(m, channel);
      return;
    }
    if (result == CarritoReceiptStore.Result.RETRY_READY) {
      handoff(m, channel, true);
      return;
    }
    ack(m, channel);
  }

  private void diagnostic(Message m, Channel channel) {
    handoff(m, channel, false);
  }

  private void handoff(Message m, Channel channel, boolean retry) {
    var p = new MessageProperties();
    p.setMessageId(m.getMessageProperties().getMessageId());
    p.setContentType("application/json");
    p.setContentEncoding("UTF-8");
    p.setUserId(config.consumerUser());
    p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    p.setRetryCount(retry ? 1 : 0);
    p.setHeader("retry-count", retry ? 1 : 0);
    var corr = new CorrelationData(UUID.randomUUID().toString());
    try {
      rabbit.send(
          retry ? CarritoCommandProperties.RETRY_EXCHANGE : CarritoCommandProperties.DLX,
          retry ? CarritoCommandProperties.RETRY_ROUTE : CarritoCommandProperties.FAILED_ROUTE,
          new Message(m.getBody(), p),
          corr);
      var confirm = corr.getFuture().get(config.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack() || corr.getReturned() != null) {
        recover();
        return;
      }
    } catch (Exception uncertain) {
      if (uncertain instanceof InterruptedException) Thread.currentThread().interrupt();
      recover();
      return;
    }
    ack(m, channel); // ACK failure does not initiate another publication here.
  }

  private void ack(Message m, Channel c) {
    try {
      c.basicAck(m.getMessageProperties().getDeliveryTag(), false);
    } catch (Exception uncertain) {
      recover();
    }
  }

  private void recover() {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .warn("Cart delivery unsettled; recovering with backoff");
    recovery.recover();
  }
}
