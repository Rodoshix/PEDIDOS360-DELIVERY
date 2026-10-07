package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.core.Message;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
public class ConfirmacionFailureReporter {
 private final JsonMapper json;
 public ConfirmacionFailureReporter(JsonMapper json) {this.json=json;}
 public void report(Message message,Exception failure,ConfirmacionErrorClassifier.Classification classification,String destination,String result) {
  Object pedido="UNKNOWN",pago="UNKNOWN"; String id="INVALID";
  try {id=java.util.UUID.fromString(message.getMessageProperties().getMessageId()).toString();} catch(RuntimeException ignored) {}
  try {var body=json.readTree(message.getBody()); if(body.path("pedidoId").isIntegralNumber()) pedido=body.path("pedidoId").longValue(); if(body.path("pagoId").isIntegralNumber()) pago=body.path("pagoId").longValue();} catch(RuntimeException ignored) {}
  Object count; try {count=ConfirmacionRetryPublisher.retryCount(message);} catch(RuntimeException invalid) {count="INVALID";}
  LoggerFactory.getLogger(getClass()).warn("Confirmation messageId={} pedidoId={} pagoId={} retry-count={} classification={} exception={} reason={} destination={} result={} redelivered={}",id,pedido,pago,count,classification,failure.getClass().getSimpleName(),failure instanceof RetryPublicationException p?p.reason():failure instanceof ConfirmacionDefinitivaException d?d.reason():"UNEXPECTED",destination,result,message.getMessageProperties().isRedelivered());
 }
}
