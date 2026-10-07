package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.AmqpException;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
public class ConfirmacionErrorClassifier {
 public enum Classification { DEFINITIVO, TRANSITORIO, INFRAESTRUCTURA }
 public Classification classify(Exception failure) {
  var visited=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
  for(Throwable cause=failure; cause!=null && visited.add(cause); cause=cause.getCause()) {
   if(cause instanceof ConfirmacionDefinitivaException || cause instanceof InvalidRetryMetadataException) return Classification.DEFINITIVO;
  }
  if(failure instanceof RetryPublicationException || failure instanceof AmqpException || failure instanceof IOException
   || failure instanceof com.rabbitmq.client.ShutdownSignalException) return Classification.INFRAESTRUCTURA;
  return Classification.TRANSITORIO;
 }
}
