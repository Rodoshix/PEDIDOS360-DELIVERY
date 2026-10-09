package cl.duoc.pedidos360.messaging.relay;

import static cl.duoc.pedidos360.messaging.relay.HandoffFailureException.ResultadoPublicacion.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
 * <p>La transferencia <strong>clona las propiedades del mensaje original</strong>. Es obligatorio
 * conservar {@code correlationId} y {@code replyTo}: sin ellos el retry volveria a la cola funcional
 * sin destino de respuesta y un error de negocio posterior terminaria en DLQ en lugar de llegar al
 * BFF. Tambien se conservan {@code messageId}, content-type, content-encoding, delivery mode,
 * {@code appId}, headers y la expiracion original: el retry no reinicia el plazo ni el deadline.
 *
 * <p>El contador de intentos se actualiza en {@code retry-count}, que es un campo reservado de Spring
 * AMQP y por eso se escribe con su API y no como header libre.
 */
public final class HandoffPublisher {

    /** Marca de la transferencia, para diagnostico en la DLQ y en el retry. */
    private static final String HEADER_DESTINO = "destino";

    private static final String HEADER_DOMINIO = "dominio";

    private static final String HEADER_CLASE_DE_FALLO = "clase-de-fallo";

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

    /**
     * Reenvia al retry corto del dominio, incrementando el contador de intentos.
     *
     * @param original propiedades del mensaje recibido. Si es {@code null} se construye un sobre
     *     minimo, valido solo para diagnostico.
     */
    public void aRetry(RequestEnvelope envelope, int retryCountActual, String claseDeFallo,
            MessageProperties original) {
        aRetry(envelope, retryCountActual, claseDeFallo, original, null);
    }

    public void aRetry(RequestEnvelope envelope, int retryCountActual, String claseDeFallo,
            MessageProperties original, QueryDeadlineGuard guard) {
        if (retryCountActual >= 1 || envelope.vencido(java.time.Instant.now()))
            throw new HandoffFailureException("retry agotado o plazo vencido");
        MessageProperties metadatos = clonarConDiagnostico(original, envelope, retryCountActual + 1, claseDeFallo,
                "retry", topology.queue());
        confirmar(properties.exchanges().retry(), topology.retryRoutingKey(),
                new Message(serializar(envelope), metadatos), "retry corto", envelope, guard);
    }

    /** Envia a la DLQ del dominio sin replay automatico. */
    public void aDlq(RequestEnvelope envelope, int retryCount, String claseDeFallo, MessageProperties original) {
        MessageProperties metadatos = clonarConDiagnostico(original, envelope, retryCount, claseDeFallo, "dlq",
                topology.queue());
        metadatos.setHeader("plazo-vencido", envelope.vencido(java.time.Instant.now()));
        confirmar(properties.exchanges().dlx(), topology.failedRoutingKey(),
                new Message(serializar(envelope), metadatos), "DLQ", null, null);
    }

    /**
     * Copia las propiedades del mensaje original y anota el resultado de la transferencia.
     *
     * <p>Se trabaja sobre una copia para no alterar las propiedades de la entrega recibida: el ACK del
     * request original necesita su delivery tag intacto.
     *
     * <p>Se descartan los datos de la entrega anterior (delivery tag, consumer tag, exchange y routing
     * key de origen) porque no describen la nueva publicacion. {@code receivedExchange} y
     * {@code receivedRoutingKey} se limpian para que la traza del broker ({@code x-death}) no confunda
     * el origen anterior con el actual.
     */
    static MessageProperties clonarConDiagnostico(MessageProperties original, RequestEnvelope envelope,
            int retryCount, String claseDeFallo, String destino, String dominio) {
        MessageProperties metadatos = original == null ? new MessageProperties() : clonar(original);
        metadatos.setMessageId(envelope.messageId().toString());
        metadatos.setContentType(metadatos.getContentType() != null
                ? metadatos.getContentType()
                : MessageProperties.CONTENT_TYPE_JSON);
        metadatos.setContentEncoding(metadatos.getContentEncoding() != null
                ? metadatos.getContentEncoding()
                : StandardCharsets.UTF_8.name());
        if (metadatos.getDeliveryMode() == null) metadatos.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        metadatos.setRetryCount(retryCount);
        metadatos.setDeliveryTag(0);
        metadatos.setConsumerTag(null);
        metadatos.setReceivedExchange(null);
        metadatos.setReceivedRoutingKey(null);
        metadatos.setRedelivered(false);
        metadatos.setHeader(HEADER_DOMINIO, dominio);
        metadatos.setHeader(HEADER_DESTINO, destino);
        metadatos.setHeader(HEADER_CLASE_DE_FALLO, claseDeFallo == null ? "DESCONOCIDA" : claseDeFallo);
        return metadatos;
    }

    /** Copia no destructiva de las propiedades AMQP. */
    static MessageProperties clonar(MessageProperties original) {
        var copia = new MessageProperties();
        copia.setMessageId(original.getMessageId());
        copia.setCorrelationId(original.getCorrelationId());
        copia.setReplyTo(original.getReplyTo());
        copia.setContentType(original.getContentType());
        copia.setContentEncoding(original.getContentEncoding());
        copia.setContentLength(original.getContentLength());
        copia.setDeliveryMode(original.getDeliveryMode());
        copia.setAppId(original.getAppId());
        copia.setClusterId(original.getClusterId());
        copia.setType(original.getType());
        copia.setPriority(original.getPriority());
        copia.setTimestamp(original.getTimestamp());
        copia.setExpiration(original.getExpiration());
        copia.setRetryCount(original.getRetryCount());
        copia.getHeaders().putAll(original.getHeaders());
        return copia;
    }

    private void confirmar(String exchange, String routingKey, Message mensaje, String destino, RequestEnvelope request,
            QueryDeadlineGuard guard) {
        var correlacion = new CorrelationData(mensaje.getMessageProperties().getMessageId() + ":" + destino);
        try {
            confirmBudget(request);
            if (guard != null) {
                try { guard.remainingNanos(); }
                catch (QueryDeadlineGuard.Expired expired) {
                    throw new HandoffFailureException("plazo de retry agotado", NO_ENVIADO, expired);
                }
            }
            rabbit.send(exchange, routingKey, mensaje, correlacion);
            CorrelationData.Confirm confirmacion = correlacion.getFuture()
                    .get(guard == null ? confirmBudgetTrasEnvio(request)
                            : Math.min(confirmBudgetTrasEnvio(request), guard.remainingNanos()), java.util.concurrent.TimeUnit.NANOSECONDS);
            if (!confirmacion.ack())
                throw new HandoffFailureException("el broker rechazo la transferencia a " + destino, RECHAZADO_CONFIRMADO);
            if (correlacion.getReturned() != null)
                throw new HandoffFailureException("la transferencia a " + destino + " volvio sin ruta", RECHAZADO_CONFIRMADO);
        } catch (HandoffFailureException yaClasificado) {
            throw yaClasificado;
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new HandoffFailureException("transferencia a " + destino + " interrumpida", INCIERTO, interrumpido);
        } catch (Exception fallo) {
            throw new HandoffFailureException("no fue posible transferir a " + destino, INCIERTO, fallo);
        }
    }

    private byte[] serializar(RequestEnvelope envelope) {
        try { return contexto.escribir(envelope); }
        catch (RuntimeException invalid) {
            throw new HandoffFailureException("no fue posible serializar transferencia", NO_ENVIADO, invalid);
        }
    }

    private long confirmBudgetTrasEnvio(RequestEnvelope request) {
        try { return confirmBudget(request); }
        catch (HandoffFailureException exhausted) {
            throw new HandoffFailureException("plazo agotado tras enviar retry; confirm incierto", INCIERTO, exhausted);
        }
    }

    /** DLQ diagnóstica tiene ventana propia; nunca renueva ni ejecuta el request. */
    private long confirmBudget(RequestEnvelope request) {
        if (request == null) return properties.confirmTimeout().toNanos();
        long remaining = java.time.Duration.between(java.time.Instant.now(), request.expiresAt()).toNanos();
        if (remaining <= 0) throw new HandoffFailureException("plazo de retry agotado");
        return Math.min(remaining, properties.confirmTimeout().toNanos());
    }

    /** Bindings de la topologia del dominio, para diagnostico. */
    public Map<String, String> bindings() {
        return topology.bindings();
    }
}
