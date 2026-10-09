package cl.duoc.pedidos360.messaging.relay;

import static cl.duoc.pedidos360.messaging.relay.HandoffFailureException.ResultadoPublicacion.*;

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
    private final java.time.Clock reloj;

    public QueryReplyPublisher(RabbitTemplate rabbit, RequestEnvelopeContext contexto,
            MessagingProperties properties) {
        this(rabbit, contexto, properties, java.time.Clock.systemUTC());
    }

    public QueryReplyPublisher(RabbitTemplate rabbit, RequestEnvelopeContext contexto,
            MessagingProperties properties, java.time.Clock reloj) {
        this.rabbit = rabbit;
        this.contexto = contexto;
        this.properties = properties;
        this.reloj = java.util.Objects.requireNonNull(reloj);
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
        return publicar(request, replyTo, correlationId, respuesta, null);
    }

    public boolean replyToPermitido(String replyTo) { return properties.queues().responses().equals(replyTo); }

    public byte[] publicar(RequestEnvelope request, String replyTo, String correlationId, QueryResponse respuesta,
            QueryDeadlineGuard guard) {
        if (correlationId == null || correlationId.isBlank())
            throw new HandoffFailureException("el request no trae correlationId");
        if (replyTo == null || replyTo.isBlank()) throw new HandoffFailureException("el request no trae replyTo");
        if (!properties.queues().responses().equals(replyTo))
            throw new HandoffFailureException("el replyTo no corresponde a la cola tecnica autorizada");
        if (respuesta == null) throw new HandoffFailureException("no hay respuesta que publicar");
        remainingNanos(request);
        guardBeforeSend(guard);
        byte[] cuerpo;
        try { cuerpo = contexto.escribirRespuesta(respuesta); }
        catch (RuntimeException invalid) {
            throw new HandoffFailureException("no fue posible serializar respuesta", NO_ENVIADO, invalid);
        }
        MessageProperties metadatos = new MessageProperties();
        metadatos.setCorrelationId(correlationId);
        metadatos.setMessageId(respuesta.messageId().toString());
        metadatos.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadatos.setContentEncoding("UTF-8");
        metadatos.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlacion = new CorrelationData(correlationId);
        try {
            long ttlMillis = java.time.Duration.between(ahora(), request.expiresAt()).toMillis();
            if (guard != null) ttlMillis = Math.min(ttlMillis, guardBeforeSend(guard) / 1_000_000);
            if (ttlMillis <= 0) throw new HandoffFailureException("plazo de respuesta agotado");
            metadatos.setExpiration(Long.toString(ttlMillis));
            rabbit.send("", replyTo, new Message(cuerpo, metadatos), correlacion);
            CorrelationData.Confirm confirmacion = correlacion.getFuture()
                    .get(confirmWaitNanos(request, guard), java.util.concurrent.TimeUnit.NANOSECONDS);
            if (!confirmacion.ack()) throw new HandoffFailureException("el broker rechazo la respuesta", RECHAZADO_CONFIRMADO);
            if (correlacion.getReturned() != null)
                throw new HandoffFailureException("la respuesta volvio sin destino disponible", RECHAZADO_CONFIRMADO);
        } catch (HandoffFailureException yaClasificado) {
            throw yaClasificado;
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new HandoffFailureException("publicacion de la respuesta interrumpida", INCIERTO, interrumpido);
        } catch (Exception fallo) {
            throw new HandoffFailureException("no fue posible confirmar la respuesta", INCIERTO, fallo);
        }
        return cuerpo;
    }

    private long confirmWaitNanos(RequestEnvelope request, QueryDeadlineGuard guard) {
        try { return guard == null ? remainingNanos(request) : Math.min(remainingNanos(request), guard.remainingNanos()); }
        catch (HandoffFailureException exhausted) {
            throw new HandoffFailureException("plazo agotado tras enviar respuesta; confirm incierto", INCIERTO, exhausted);
        }
        catch (QueryDeadlineGuard.Expired exhausted) {
            throw new HandoffFailureException("plazo agotado tras enviar respuesta; confirm incierto", INCIERTO, exhausted);
        }
    }

    private static long guardBeforeSend(QueryDeadlineGuard guard) {
        if (guard == null) return Long.MAX_VALUE;
        try { return guard.remainingNanos(); }
        catch (QueryDeadlineGuard.Expired exhausted) {
            throw new HandoffFailureException("plazo de respuesta agotado", NO_ENVIADO, exhausted);
        }
    }

    private long remainingNanos(RequestEnvelope request) {
        long remaining = java.time.Duration.between(ahora(), request.expiresAt()).toNanos();
        if (remaining <= 0) throw new HandoffFailureException("plazo de respuesta agotado");
        return Math.min(remaining, properties.confirmTimeout().toNanos());
    }

    /** Marca temporal UTC de la respuesta. */
    public Instant ahora() {
        return reloj.instant();
    }
}
