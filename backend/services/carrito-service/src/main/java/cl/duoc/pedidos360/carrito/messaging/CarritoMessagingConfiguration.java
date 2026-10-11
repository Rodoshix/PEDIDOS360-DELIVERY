package cl.duoc.pedidos360.carrito.messaging;

import cl.duoc.pedidos360.messaging.command.CarritoCommandProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CarritoCommandProperties.class)
public class CarritoMessagingConfiguration {
  @Configuration(proxyBeanMethods = false)
  @org.springframework.amqp.rabbit.annotation.EnableRabbit
  @ConditionalOnProperty(
      prefix = "pedidos360.messaging.carrito",
      name = "mode",
      havingValue = "RABBITMQ")
  static class Active {
    @Bean(name = "carritoCommandConnection", destroyMethod = "destroy")
    CachingConnectionFactory connection(
        CarritoCommandProperties c,
        org.springframework.core.env.Environment env,
        org.springframework.core.io.ResourceLoader resources)
        throws Exception {
      if (!c.username().equals(c.consumerUser()))
        throw new IllegalArgumentException("Dedicated Carrito consumer required");
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

    @Bean
    CarritoConsumerRecovery recovery(RabbitListenerEndpointRegistry r, CarritoCommandProperties c) {
      return new CarritoConsumerRecovery(r, c);
    }

    @Bean("carritoCommandListenerFactory")
    SimpleRabbitListenerContainerFactory listener(
        @org.springframework.beans.factory.annotation.Qualifier("carritoCommandConnection")
            CachingConnectionFactory cf) {
      var f = new SimpleRabbitListenerContainerFactory();
      f.setConnectionFactory(cf);
      f.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
      f.setPrefetchCount(1);
      f.setConcurrentConsumers(1);
      f.setMaxConcurrentConsumers(1);
      f.setContainerCustomizer(
          c -> {
            c.setAutoDeclare(false);
            c.setForceStop(true);
            c.setMessagePropertiesConverter(
                new org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter() {
                  @Override
                  public org.springframework.amqp.core.MessageProperties toMessageProperties(
                      com.rabbitmq.client.AMQP.BasicProperties source,
                      com.rabbitmq.client.Envelope envelope,
                      String charset) {
                    var p = super.toMessageProperties(source, envelope, charset);
                    Object retry =
                        source.getHeaders() == null ? null : source.getHeaders().get("retry-count");
                    p.setHeader("retry-count", retry == null ? 0 : retry);
                    p.setRetryCount(0);
                    return p;
                  }
                });
          });
      return f;
    }
  }
}
