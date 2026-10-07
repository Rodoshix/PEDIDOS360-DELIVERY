package cl.duoc.pedidos360.messaging.relay;

import java.time.Instant;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.envelope.QueryResponse;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;

/**
 * Publica la respuesta correlacionada hacia {@code replyTo}.
 *
 * <p>La respuesta va al exchange predeterminado usando la cola declarada en {@code replyTo}: es la
 * unica forma de que llegue a una cola tecnica compartida que no tiene binding propio.
 *
 * <p>La publicacion es {@code mandatory}, persistente y espera confirm correlacionado. Un return
 * significa que la respuesta no llego a ninguna cola: el consumidor no debe confirmar el request.
 */
public final class QueryReplyPublisher {

    private final RabbitTemplate rabbit;
    private final RequestEnvelopeContext contexto;
    private final MessagingProperties properties;

    public QueryReplyPublisher(RabbitTemplate rabbit, RequestEnvelopeContext contexto,
            MessagingProperties properties) {
        this.rabbit = rabbit;
        this.contexto = contexto;
        this.properties = properties;
        rabbit.setMandatory(true);
    }

    /**
     * Envia la respuesta al destino indicado por el request.
     *
     * @return el cuerpo publicado, para diagnostico y evidencia.
     * @throws HandoffFailureException si el broker no confirma, devuelve el mensaje o el replyTo es
     *     inaceptable.
     */
    public byte[] publicar(RequestEnvelope request, String replyTo, String correlationId, QueryResponse respuesta) {
        if (correlationId == null || correlationId.isBlank())
            throw new HandoffFailureException("el request no trae correlationId");
        if (replyTo == null || replyTo.isBlank()) throw new HandoffFailureException("el request no trae replyTo");
        if (!properties.queues().responses().equals(replyTo))
            throw new HandoffFailureException("el replyTo no corresponde a la cola tecnica autorizada");
        if (respuesta == null) throw new HandoffFailureException("no hay respuesta que publicar");
        byte[] cuerpo = contexto.escribirRespuesta(respuesta);
        MessageProperties metadatos = new MessageProperties();
        metadatos.setCorrelationId(correlationId);
        metadatos.setMessageId(respuesta.messageId().toString());
        metadatos.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadatos.setContentEncoding("UTF-8");
        metadatos.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlacion = new CorrelationData(correlationId);
        try {
            rabbit.send("", replyTo, new Message(cuerpo, metadatos), correlacion);
            CorrelationData.Confirm confirmacion = correlacion.getFuture()
                    .get(properties.confirmTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!confirmacion.ack()) throw new HandoffFailureException("el broker rechazo la respuesta");
            if (correlacion.getReturned() != null)
                throw new HandoffFailureException("la respuesta volvio sin destino disponible");
        } catch (HandoffFailureException yaClasificado) {
            throw yaClasificado;
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new HandoffFailureException("publicacion de la respuesta interrumpida", interrumpido);
        } catch (Exception fallo) {
            throw new HandoffFailureException("no fue posible confirmar la respuesta", fallo);
        }
        return cuerpo;
    }

    /** Marca temporal UTC de la respuesta. */
    public Instant ahora() {
        return Instant.now();
    }
}
