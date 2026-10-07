package cl.duoc.pedidos360.messaging.relay;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.QueryTopology;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;

/**
 * Transferencia confirmada del mensaje fallido hacia retry corto o DLQ.
 *
 * <p>Un transferencia solo cuenta como exitosa si el broker confirma la publicacion y el mensaje no
 * volvio por falta de ruta. En cualquier otro caso se lanza {@link HandoffFailureException} y el
 * consumidor no confirma el request original.
 *
 * <p>El {@code messageId} y el plazo original se conservan: el retry no reinicia el deadline.
 * La ruta de retorno de la cola de retry vuelve a la cola funcional del mismo dominio.
 */
public final class HandoffPublisher {

    private final RabbitTemplate rabbit;
    private final RequestEnvelopeContext contexto;
    private final MessagingProperties properties;
    private final QueryTopology topology;

    public HandoffPublisher(RabbitTemplate rabbit, RequestEnvelopeContext contexto, MessagingProperties properties,
            QueryTopology topology) {
        this.rabbit = rabbit;
        this.contexto = contexto;
        this.properties = properties;
        this.topology = topology;
        rabbit.setMandatory(true);
    }

    public QueryTopology topology() {
        return topology;
    }

    /** Reenvia al retry corto del dominio, incrementando el contador de intentos. */
    public void aRetry(RequestEnvelope envelope, int retryCountActual, String claseDeFallo) {
        int siguiente = retryCountActual + 1;
        var metadatos = metadatosBase(envelope, siguiente, claseDeFallo, "retry");
        confirmar(properties.exchanges().retry(), topology.retryRoutingKey(),
                new Message(contexto.escribir(envelope), metadatos), "retry corto");
    }

    /** Envia a la DLQ del dominio sin replay automatico. */
    public void aDlq(RequestEnvelope envelope, int retryCount, String claseDeFallo) {
        var metadatos = metadatosBase(envelope, retryCount, claseDeFallo, "dlq");
        confirmar(properties.exchanges().dlx(), topology.failedRoutingKey(),
                new Message(contexto.escribir(envelope), metadatos), "DLQ");
    }

    /**
     * Metadatos de la transferencia.
     *
     * <p>{@code retry-count} es un header reservado por Spring AMQP y se accede con
     * {@code MessageProperties.getRetryCount()}. Escribirlo como header libre lo descartaria en la
     * trama, por lo que se usa el contador propio del cliente.
     */
    private MessageProperties metadatosBase(RequestEnvelope envelope, int retryCount, String claseDeFallo,
            String destino) {
        var metadatos = new MessageProperties();
        metadatos.setMessageId(envelope.messageId().toString());
        metadatos.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadatos.setContentEncoding("UTF-8");
        metadatos.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        metadatos.setRetryCount(retryCount);
        metadatos.setHeader("dominio", topology.queue());
        metadatos.setHeader("destino", destino);
        metadatos.setHeader("clase-de-fallo", claseDeFallo == null ? "DESCONOCIDA" : claseDeFallo);
        return metadatos;
    }

    private void confirmar(String exchange, String routingKey, Message mensaje, String destino) {
        var correlacion = new CorrelationData(mensaje.getMessageProperties().getMessageId() + ":" + destino);
        try {
            rabbit.send(exchange, routingKey, mensaje, correlacion);
            CorrelationData.Confirm confirmacion = correlacion.getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirmacion.ack())
                throw new HandoffFailureException("el broker rechazo la transferencia a " + destino);
            if (correlacion.getReturned() != null)
                throw new HandoffFailureException("la transferencia a " + destino + " volvio sin ruta");
        } catch (HandoffFailureException yaClasificado) {
            throw yaClasificado;
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new HandoffFailureException("transferencia a " + destino + " interrumpida", interrumpido);
        } catch (Exception fallo) {
            throw new HandoffFailureException("no fue posible transferir a " + destino, fallo);
        }
    }

    /** Bindings de la topologia del dominio, para diagnostico. */
    public Map<String, String> bindings() {
        return topology.bindings();
    }
}
