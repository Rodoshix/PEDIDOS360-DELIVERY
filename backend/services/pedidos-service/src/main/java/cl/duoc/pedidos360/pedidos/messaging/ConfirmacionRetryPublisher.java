package cl.duoc.pedidos360.pedidos.messaging;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
public class ConfirmacionRetryPublisher {
 private final RabbitTemplate rabbit; private final RabbitProperties properties; private final ConfirmacionReliabilityProperties reliability;
 public ConfirmacionRetryPublisher(RabbitTemplate rabbit,RabbitProperties properties,ConfirmacionReliabilityProperties reliability) {
  this.rabbit=rabbit; this.properties=properties; this.reliability=reliability; rabbit.setMandatory(true);
 }
 public static int retryCount(Message message) {
  Object value=message.getMessageProperties().getHeaders().get("retry-count");
  if(value==null) return 0;
  if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new InvalidRetryMetadataException();
  long count=((Number)value).longValue(); if(count<0 || count>Integer.MAX_VALUE) throw new InvalidRetryMetadataException(); return (int)count;
 }
 public void publish(Message original,int count) throws RetryPublicationException {
  var copy=MessageBuilder.fromClonedMessage(original).setDeliveryMode(MessageDeliveryMode.PERSISTENT)
   .setHeader("retry-count",count+1).build();
  // Preserve payload/message_id; each attempt has a distinct confirm correlation.
  copy.getMessageProperties().setRetryCount(count+1);
  var correlation=new CorrelationData(UUID.randomUUID().toString());
  try {
   rabbit.send(reliability.retryExchange(),reliability.retryKey(count),copy,correlation);
   var confirm=correlation.getFuture().get(properties.confirmTimeout().toMillis(),TimeUnit.MILLISECONDS);
   if(!confirm.ack()) throw new RetryPublicationException(RetryPublicationException.Reason.BROKER_NACK);
   if(correlation.getReturned()!=null) throw new RetryPublicationException(RetryPublicationException.Reason.RETURNED);
  } catch(RetryPublicationException known) { throw known; }
  catch(Exception uncertain) {
   if(uncertain instanceof InterruptedException) Thread.currentThread().interrupt();
   throw new RetryPublicationException(RetryPublicationException.Reason.TIMEOUT_OR_CONNECTION,uncertain);
  }
 }
}
