package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
import static cl.duoc.pedidos360.pedidos.messaging.ConfirmacionErrorClassifier.Classification.*;
public class DefaultPedidoConfirmacionFailureHandler implements PedidoConfirmacionFailureHandler {
 private final ConfirmacionErrorClassifier classifier;
 private final ConfirmacionRetryPublisher publisher;
 private final ConfirmacionConsumerRecovery recovery;
 private final ConfirmacionFailureReporter reporter;
 private final ConfirmacionReliabilityProperties properties;
 public DefaultPedidoConfirmacionFailureHandler(ConfirmacionErrorClassifier classifier,ConfirmacionRetryPublisher publisher,ConfirmacionConsumerRecovery recovery,ConfirmacionFailureReporter reporter,ConfirmacionReliabilityProperties properties) {
  this.classifier=classifier; this.publisher=publisher; this.recovery=recovery; this.reporter=reporter; this.properties=properties;
 }
 @Override public void handle(Message message,Channel channel,Exception failure) {
  var classification=classifier.classify(failure);
  if(classification==INFRAESTRUCTURA) { retain(message,failure); return; }
  int count;
  try {count=ConfirmacionRetryPublisher.retryCount(message);} catch(InvalidRetryMetadataException invalid) {failure=invalid; classification=DEFINITIVO; count=3;}
  if(classification==DEFINITIVO || count>=3) {
   try {channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,false);
    reporter.report(message,failure,classification,properties.dlq(),"NACK_SENT_DLQ_REQUESTED");
   } catch(Exception uncertain) {retain(message,uncertain);}
   return;
  }
  try {publisher.publish(message,count);}
  catch(RetryPublicationException uncertain) {retain(message,uncertain); return;}
  // Publication succeeded. An uncertain ACK must recover original, not publish another retry here.
  try {channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
   reporter.report(message,failure,classification,properties.retryKey(count),"CONFIRMED_NO_RETURN_ORIGINAL_ACK_SENT");
  } catch(Exception uncertain) {retain(message,uncertain);}
 }
 private void retain(Message message,Exception uncertain) {
  reporter.report(message,uncertain,INFRAESTRUCTURA,"ORIGINAL","UNSETTLED_RECOVERY_BACKOFF"); recovery.recover();
 }
}
