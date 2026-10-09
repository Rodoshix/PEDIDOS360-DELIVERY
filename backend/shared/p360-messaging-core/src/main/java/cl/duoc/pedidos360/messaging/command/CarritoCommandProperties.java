package cl.duoc.pedidos360.messaging.command;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Dedicated connection; independent from query and Pago→Pedido credentials. */
@ConfigurationProperties("pedidos360.messaging.carrito")
public record CarritoCommandProperties(
    @DefaultValue("HTTP") Mode mode,
    @DefaultValue("false") boolean platformReady,
    @DefaultValue("localhost") String host,
    @DefaultValue("5672") int port,
    @DefaultValue("pedidos360") String virtualHost,
    @DefaultValue("") String username,
    @DefaultValue("") String password,
    @DefaultValue("p360-pedidos-carrito-publisher") String publisherUser,
    @DefaultValue("p360-carrito-consumer") String consumerUser,
    @DefaultValue("3s") Duration confirmTimeout,
    @DefaultValue("30s") Duration lease,
    @DefaultValue("5s") Duration recoveryBackoff,
    @DefaultValue("5s") Duration publisherRetryDelay) {
  public enum Mode {
    HTTP,
    RABBITMQ
  }

  public CarritoCommandProperties {
    if (mode == null
        || port < 1
        || port > 65535
        || publisherUser == null
        || consumerUser == null
        || publisherUser.equals(consumerUser))
      throw new IllegalArgumentException("Invalid cart messaging configuration");
    for (var d : java.util.List.of(confirmTimeout, lease, recoveryBackoff, publisherRetryDelay))
      if (d.isNegative() || d.isZero())
        throw new IllegalArgumentException("Positive timeout required");
    if (lease.compareTo(confirmTimeout) <= 0)
      throw new IllegalArgumentException("Lease must exceed confirm timeout");
    if (mode == Mode.RABBITMQ && (!platformReady || username.isBlank() || password.isBlank()))
      throw new IllegalArgumentException("Dedicated credentials and platform readiness required");
  }

  public static final String EXCHANGE = "p360.commands",
      ROUTE = "carrito.vaciar-por-pedido.v1",
      QUEUE = "p360.carrito.vaciado.q",
      RETRY_EXCHANGE = "p360.retry",
      RETRY_ROUTE = "carrito.vaciar-por-pedido.retry.1s",
      DLX = "p360.dlx",
      FAILED_ROUTE = "carrito.vaciar-por-pedido.failed";

  @Override
  public String toString() {
    return "CarritoCommandProperties[redacted]";
  }
}
