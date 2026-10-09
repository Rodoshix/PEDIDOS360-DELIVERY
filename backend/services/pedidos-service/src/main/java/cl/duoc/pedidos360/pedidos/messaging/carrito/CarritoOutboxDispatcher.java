package cl.duoc.pedidos360.pedidos.messaging.carrito;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "pedidos360.messaging.carrito",
    name = "mode",
    havingValue = "RABBITMQ")
public class CarritoOutboxDispatcher {
  private final CarritoOutboxStore store;
  private final CarritoOutboxPublisher publisher;

  public CarritoOutboxDispatcher(CarritoOutboxStore s, CarritoOutboxPublisher p) {
    store = s;
    publisher = p;
  }

  @Scheduled(fixedDelayString = "${pedidos360.messaging.carrito.dispatch-interval-ms:5000}")
  public void dispatch() {
    for (var claim : store.claim()) {
      String error = null;
      try {
        publisher.publish(claim);
      } catch (Exception failure) {
        error = failure.getClass().getSimpleName();
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      }
      boolean settled = store.finish(claim, error);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info(
              "Cart outbox messageId={} result={} settled={}",
              claim.id(),
              error == null ? "BROKER_CONFIRMED" : "RECOVERABLE_FAILURE",
              settled);
    }
  }
}
