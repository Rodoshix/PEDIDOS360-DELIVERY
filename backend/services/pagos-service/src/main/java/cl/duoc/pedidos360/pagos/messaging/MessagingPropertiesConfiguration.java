package cl.duoc.pedidos360.pagos.messaging;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(RabbitProperties.class)
public class MessagingPropertiesConfiguration {}
