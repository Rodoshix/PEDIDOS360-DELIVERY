package cl.duoc.pedidos360.pedidos.messaging;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
/** Explicit application retry counter must not be inferred from broker x-death. */
public class ConfirmacionMessagePropertiesConverter extends DefaultMessagePropertiesConverter {
 @Override public MessageProperties toMessageProperties(AMQP.BasicProperties source,Envelope envelope,String charset) {
  var target=super.toMessageProperties(source,envelope,charset);
  Object count=source.getHeaders()==null?null:source.getHeaders().get("retry-count");
  target.setHeader("retry-count",count==null?0:count);
  target.setRetryCount(0); // Only the explicit header controls our 0/1/2/3 application policy.
  return target;
 }
}
