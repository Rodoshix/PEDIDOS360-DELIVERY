package cl.duoc.pedidos360.pedidos.messaging.carrito;

import cl.duoc.pedidos360.messaging.command.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "pedidos360.messaging.carrito",
    name = "mode",
    havingValue = "RABBITMQ")
public class CarritoOutboxPublisher {
  private final RabbitTemplate rabbit;
  private final CarritoCommandProperties config;

  public CarritoOutboxPublisher(
      @org.springframework.beans.factory.annotation.Qualifier("carritoCommandTemplate")
          RabbitTemplate r,
      CarritoCommandProperties c) {
    rabbit = r;
    config = c;
  }

  public void publish(CarritoOutboxStore.Claim claim) throws Exception {
    var command =
        VaciarCarritoPorPedido.leer(
            claim.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (!command.messageId().equals(claim.id()))
      throw new IllegalArgumentException("Invalid command association");
    var p = new MessageProperties();
    p.setMessageId(claim.id().toString());
    p.setContentType("application/json");
    p.setContentEncoding("UTF-8");
    p.setUserId(config.publisherUser());
    p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    p.setRetryCount(0);
    p.setHeader("retry-count", 0);
    var corr = new CorrelationData(claim.id() + ":" + UUID.randomUUID());
    rabbit.send(
        CarritoCommandProperties.EXCHANGE,
        CarritoCommandProperties.ROUTE,
        new Message(claim.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8), p),
        corr);
    var confirm = corr.getFuture().get(config.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
    if (!confirm.ack() || corr.getReturned() != null)
      throw new java.io.IOException("Cart publication not accepted");
  }
}
