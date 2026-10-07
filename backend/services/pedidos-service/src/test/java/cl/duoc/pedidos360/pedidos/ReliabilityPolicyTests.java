package cl.duoc.pedidos360.pedidos;
import cl.duoc.pedidos360.pedidos.messaging.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.dao.*;
import com.rabbitmq.client.Channel;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReliabilityPolicyTests {
 static ConfirmacionReliabilityProperties settings() {return new ConfirmacionReliabilityProperties("retry","dlx","dlq","failed","q5","q30","q120","k5","k30","k120",Duration.ofSeconds(5),true);}
 static Message message() {var p=new MessageProperties();p.setMessageId(UUID.randomUUID().toString());p.setDeliveryTag(7);return new Message("{}".getBytes(),p);}
 ConfirmacionRetryPublisher publisher=mock(ConfirmacionRetryPublisher.class);
 ConfirmacionConsumerRecovery recovery=mock(ConfirmacionConsumerRecovery.class);
 DefaultPedidoConfirmacionFailureHandler handler=new DefaultPedidoConfirmacionFailureHandler(new ConfirmacionErrorClassifier(),publisher,recovery,new ConfirmacionFailureReporter(JsonMapper.builder().build()),settings());
 @ParameterizedTest @ValueSource(ints={0,1,2}) void retrySoloAckTrasPublicacionConfirmada(int count) throws Exception {
  var msg=message();msg.getMessageProperties().setHeader("retry-count",count);var channel=mock(Channel.class);
  handler.handle(msg,channel,new DataAccessResourceFailureException("DB down"));
  var order=inOrder(publisher,channel);order.verify(publisher).publish(msg,count);order.verify(channel).basicAck(7,false);verifyNoInteractions(recovery);
 }
 @ParameterizedTest @ValueSource(ints={3,4,100}) void agotamientoNackSinRequeue(int count) throws Exception {
  var msg=message();msg.getMessageProperties().setHeader("retry-count",count);var channel=mock(Channel.class);
  handler.handle(msg,channel,new RuntimeException());verify(channel).basicNack(7,false,false);verifyNoInteractions(publisher,recovery);
 }
 @Test void definitivoNoRetry() throws Exception {
  var channel=mock(Channel.class);handler.handle(message(),channel,new ConfirmacionDefinitivaException(ConfirmacionDefinitivaException.Reason.INVALID_MESSAGE,null));
  verify(channel).basicNack(7,false,false);verifyNoInteractions(publisher);
 }
 @ParameterizedTest @ValueSource(strings={"BROKER_NACK","RETURNED","TIMEOUT_OR_CONNECTION"}) void handoffInciertoSinAckNiNack(String reason) throws Exception {
  var msg=message();var channel=mock(Channel.class);doThrow(new RetryPublicationException(RetryPublicationException.Reason.valueOf(reason))).when(publisher).publish(msg,0);
  handler.handle(msg,channel,new RuntimeException());verifyNoInteractions(channel);verify(recovery).recover();
 }
 @Test void ackInciertoNoSegundaPublicacion() throws Exception {
  var msg=message();var channel=mock(Channel.class);doThrow(new java.io.IOException()).when(channel).basicAck(7,false);
  handler.handle(msg,channel,new RuntimeException());verify(publisher,times(1)).publish(msg,0);verify(recovery).recover();verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
 }
 @Test void nackInciertoRecupera() throws Exception {
  var channel=mock(Channel.class);doThrow(new java.io.IOException()).when(channel).basicNack(7,false,false);
  handler.handle(message(),channel,new InvalidRetryMetadataException());verify(recovery).recover();
 }
 @Test void metadataRetryInvalidaDefinitiva() throws Exception {
  for(Object invalid:List.of(-1,1.5,"1",Long.MAX_VALUE)) {var msg=message();msg.getMessageProperties().setHeader("retry-count",invalid);var channel=mock(Channel.class);
   handler.handle(msg,channel,new RuntimeException());verify(channel).basicNack(7,false,false);
  } verifyNoInteractions(publisher);
 }
 @Test void clasificacionExplicita() {
  var c=new ConfirmacionErrorClassifier();
  for(var failure:List.of(new DataAccessResourceFailureException("db"),new CannotAcquireLockException("deadlock"),new OptimisticLockingFailureException("lock"),new IllegalStateException())) assertThat(c.classify(failure)).isEqualTo(ConfirmacionErrorClassifier.Classification.TRANSITORIO);
  assertThat(c.classify(new java.io.IOException())).isEqualTo(ConfirmacionErrorClassifier.Classification.INFRAESTRUCTURA);
 }
 @Test void definitivosEnvueltosNoSeReintentan() throws Exception {
  for(var definitive:List.of(new ConfirmacionDefinitivaException(ConfirmacionDefinitivaException.Reason.PEDIDO_CANCELADO,null),new InvalidRetryMetadataException())) {
   var wrapped=new IllegalStateException(new java.io.IOException(definitive));
   var channel=mock(Channel.class);handler.handle(message(),channel,wrapped);
   assertThat(new ConfirmacionErrorClassifier().classify(wrapped)).isEqualTo(ConfirmacionErrorClassifier.Classification.DEFINITIVO);
   verify(channel).basicNack(7,false,false);
  }
  verifyNoInteractions(publisher,recovery);
 }
 @Test void causasCiclicasNoBloqueanClassifierNiConviertenInesperadosEnDefinitivos() {
  var first=new IllegalStateException();var second=new RuntimeException(first);first.initCause(second);
  assertThat(new ConfirmacionErrorClassifier().classify(first)).isEqualTo(ConfirmacionErrorClassifier.Classification.TRANSITORIO);
 }
 @ParameterizedTest @ValueSource(strings={"ACK","NACK","RETURN","TIMEOUT","CONNECTION"}) void publisherConfirmsReturnsTimeout(String result) throws Exception {
  var template=mock(RabbitTemplate.class);var props=new RabbitProperties(RabbitProperties.Mode.RABBITMQ,new RabbitProperties.Exchanges("cmd"),new RabbitProperties.RoutingKeys("key"),new RabbitProperties.Queues("main"),Duration.ofMillis(30),Duration.ofSeconds(30),1,1000,Duration.ofSeconds(30));
  var msg=message();
  doAnswer(inv->{Message copy=inv.getArgument(2);CorrelationData cd=inv.getArgument(3);
   assertThat(copy.getMessageProperties().getMessageId()).isEqualTo(msg.getMessageProperties().getMessageId());
   assertThat(copy.getMessageProperties().getHeaders().get("retry-count")).isEqualTo(1);
   assertThat(copy.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
   if(result.equals("CONNECTION")) throw new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException());
   if(result.equals("RETURN")) cd.setReturned(new ReturnedMessage(copy,312,"NO_ROUTE","retry","k5"));
   if(!result.equals("TIMEOUT")) cd.getFuture().complete(new CorrelationData.Confirm(!result.equals("NACK"),null));return null;
  }).when(template).send(eq("retry"),eq("k5"),any(Message.class),any(CorrelationData.class));
  var real=new ConfirmacionRetryPublisher(template,props,settings());
  if(result.equals("ACK")) assertThatCode(()->real.publish(msg,0)).doesNotThrowAnyException();
  else assertThatThrownBy(()->real.publish(msg,0)).isInstanceOf(RetryPublicationException.class);
  verify(template).setMandatory(true);assertThat(msg.getMessageProperties().getHeaders()).doesNotContainKey("retry-count");
 }

 @Test void converterMantieneContadorExplicitoSinInferirXDeath() {
  var converter=new ConfirmacionMessagePropertiesConverter();
  var envelope=new com.rabbitmq.client.Envelope(7,false,"exchange","key");
  for(Object count:List.of(0,1,2,3,"invalid",1.5,-1)) {
   var props=new com.rabbitmq.client.AMQP.BasicProperties.Builder().headers(Map.of("retry-count",count,"x-death",List.of(Map.of("count",10L,"reason","expired")))).build();
   var converted=converter.toMessageProperties(props,envelope,"UTF-8");
   assertThat(converted.getHeaders().get("retry-count")).isEqualTo(count);
  }
  var missing=converter.toMessageProperties(new com.rabbitmq.client.AMQP.BasicProperties.Builder().build(),envelope,"UTF-8");
  assertThat(missing.getHeaders().get("retry-count")).isEqualTo(0);
 }
}
