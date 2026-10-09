package cl.duoc.pedidos360.pedidos.messaging.carrito;

import cl.duoc.pedidos360.messaging.command.CarritoCommandProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    org.springframework.boot.amqp.autoconfigure.RabbitProperties.class)
@ConditionalOnProperty(
    prefix = "pedidos360.messaging.carrito",
    name = "mode",
    havingValue = "RABBITMQ")
public class CarritoPublisherConfiguration {
  @Bean(name = "rabbitConnectionFactory")
  @Primary
  CachingConnectionFactory existingConnection(
      @org.springframework.beans.factory.annotation.Qualifier(
              "spring.rabbitmq-org.springframework.boot.amqp.autoconfigure.RabbitProperties")
          org.springframework.boot.amqp.autoconfigure.RabbitProperties p,
      org.springframework.core.io.ResourceLoader resources)
      throws Exception {
    return cl.duoc.pedidos360.messaging.command.CommandConnections.create(p, resources);
  }

  @Bean(name = "rabbitTemplate")
  @Primary
  RabbitTemplate existingTemplate(
      @org.springframework.beans.factory.annotation.Qualifier("rabbitConnectionFactory")
          org.springframework.amqp.rabbit.connection.ConnectionFactory f,
      @org.springframework.beans.factory.annotation.Qualifier(
              "spring.rabbitmq-org.springframework.boot.amqp.autoconfigure.RabbitProperties")
          org.springframework.boot.amqp.autoconfigure.RabbitProperties p) {
    var r = new RabbitTemplate();
    new org.springframework.boot.amqp.autoconfigure.RabbitTemplateConfigurer(p).configure(r, f);
    return r;
  }

  @Bean(name = "carritoCommandConnection", destroyMethod = "destroy")
  CachingConnectionFactory connection(
      CarritoCommandProperties c,
      org.springframework.core.env.Environment env,
      org.springframework.core.io.ResourceLoader resources)
      throws Exception {
    if (!c.username().equals(c.publisherUser()))
      throw new IllegalArgumentException("Dedicated Pedidos publisher required");
    var p =
        org.springframework.boot.context.properties.bind.Binder.get(env)
            .bind(
                "pedidos360.messaging.carrito",
                org.springframework.boot.amqp.autoconfigure.RabbitProperties.class)
            .orElseThrow(() -> new IllegalStateException("Dedicated connection required"));
    var f = cl.duoc.pedidos360.messaging.command.CommandConnections.create(p, resources);
    f.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    f.setPublisherReturns(true);
    return f;
  }

  @Bean("carritoCommandTemplate")
  RabbitTemplate template(
      @org.springframework.beans.factory.annotation.Qualifier("carritoCommandConnection")
          CachingConnectionFactory f) {
    var r = new RabbitTemplate(f);
    r.setMandatory(true);
    return r;
  }
}
