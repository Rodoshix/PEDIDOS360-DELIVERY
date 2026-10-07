package cl.duoc.pedidos360.messaging.relay;

import java.util.concurrent.TimeUnit;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;

/**
 * Publica el request de una consulta con correlacion, respuesta dirigida y confirmacion.
 *
 * <p>Reglas cubiertas:
 * <ul>
 *   <li>{@code replyTo} queda restringido a la cola tecnica configurada. El emisor no acepta un
 *       destino de respuesta aportado desde fuera.</li>
 *   <li>{@code mandatory=true}, publisher confirms correlacionados y deteccion de returned message.</li>
 *   <li>Mensaje persistente, {@code message_id} coincidente con el cuerpo y {@code app_id} del emisor.</li>
 *   <li>{@code correlationId} identifica esta espera; {@code messageId} identifica el mensaje logico.</li>
 * </ul>
 */
public final class RequestPublisher {

    private final RabbitTemplate rabbit;
    private final RequestEnvelopeContext contexto;
    private final MessagingProperties properties;
    private final String appId;

    public RequestPublisher(RabbitTemplate rabbit, RequestEnvelopeContext contexto, MessagingProperties properties,
            String appId) {
        if (appId == null || appId.isBlank()) throw new IllegalArgumentException("appId requerido");
        this.rabbit = rabbit;
        this.contexto = contexto;
        this.properties = properties;
        this.appId = appId;
        rabbit.setMandatory(true);
    }

    /**
     * Publica el envelope y espera la confirmacion del broker.
     *
     * @throws QueryUnavailableException si el broker no esta disponible, niega el mensaje o lo
     *     devuelve sin ruta. Un return deja de ser aceptable: sin destino no hay respuesta posible.
     */
    public void publicar(RequestEnvelope envelope, String correlationId) {
        publicarConMedicion(envelope, correlationId);
    }

    /**
     * Publica el envelope, espera la confirmacion y devuelve el tiempo consumido.
     *
     * <p>El solicitante necesita esta medida para descontar la publicacion del presupuesto total: el
     * deadline es absoluto y no se reinicia despues del confirm.
     *
     * @return nanosegundos consumidos por la publicacion confirmada.
     */
    public long publicarConMedicion(RequestEnvelope envelope, String correlationId) {
        if (correlationId == null || correlationId.isBlank())
            throw new IllegalArgumentException("correlationId requerido");
        if (!properties.queues().responses().equals(replyToPermitido()))
            throw new QueryUnavailableException("el destino de respuesta no esta autorizado", null);
        MessageProperties metadatos = new MessageProperties();
        metadatos.setMessageId(envelope.messageId().toString());
        metadatos.setCorrelationId(correlationId);
        metadatos.setReplyTo(replyToPermitido());
        metadatos.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadatos.setContentEncoding("UTF-8");
        metadatos.setAppId(appId);
        metadatos.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        // retry-count es reservado por Spring AMQP: se usa su contador, no un header libre.
        metadatos.setRetryCount(0);
        var correlacion = new CorrelationData(correlationId);
        long inicio = System.nanoTime();
        try {
            rabbit.send(properties.exchanges().queries(), envelope.operacion(),
                    new Message(contexto.escribir(envelope), metadatos), correlacion);
            CorrelationData.Confirm confirmacion = correlacion.getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirmacion.ack()) throw new QueryUnavailableException("el broker rechazo la publicacion", null);
            if (correlacion.getReturned() != null)
                throw new QueryUnavailableException("la publicacion volvio sin ruta disponible", null);
            return System.nanoTime() - inicio;
        } catch (QueryUnavailableException yaClasificado) {
            throw yaClasificado;
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new QueryUnavailableException("publicacion interrumpida", interrumpido);
        } catch (Exception fallo) {
            throw new QueryUnavailableException("no fue posible publicar la consulta", fallo);
        }
    }

    /** Unico destino de respuesta autorizado: la cola tecnica desde configuracion. */
    public String replyToPermitido() {
        return properties.queues().responses();
    }
}
