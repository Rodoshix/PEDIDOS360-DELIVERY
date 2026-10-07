package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.AmqpException;
import java.io.IOException;
public class ConfirmacionErrorClassifier {
 public enum Classification { DEFINITIVO, TRANSITORIO, INFRAESTRUCTURA }
 public Classification classify(Exception failure) {
  if(failure instanceof ConfirmacionDefinitivaException || failure instanceof InvalidRetryMetadataException) return Classification.DEFINITIVO;
  if(failure instanceof RetryPublicationException || failure instanceof AmqpException || failure instanceof IOException
   || failure instanceof com.rabbitmq.client.ShutdownSignalException) return Classification.INFRAESTRUCTURA;
  return Classification.TRANSITORIO;
 }
}
