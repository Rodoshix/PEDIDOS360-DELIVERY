package cl.duoc.pedidos360.messaging.command;

import org.springframework.amqp.rabbit.connection.*;
import org.springframework.boot.amqp.autoconfigure.*;
import org.springframework.core.io.ResourceLoader;

/** Uses Boot's TLS, truststore and connection settings rather than bypassing them. */
public final class CommandConnections {
  private CommandConnections() {}

  public static CachingConnectionFactory create(RabbitProperties p, ResourceLoader resources)
      throws Exception {
    var bean = new RabbitConnectionFactoryBean();
    new RabbitConnectionFactoryBeanConfigurer(resources, p).configure(bean);
    bean.afterPropertiesSet();
    var cf = new CachingConnectionFactory(bean.getObject());
    new CachingConnectionFactoryConfigurer(p).configure(cf, p);
    return cf;
  }
}
