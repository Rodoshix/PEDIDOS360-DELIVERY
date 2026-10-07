package cl.duoc.pedidos360.messaging.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;

import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.QueryTopology;
import cl.duoc.pedidos360.messaging.actor.ActorContextException;
import cl.duoc.pedidos360.messaging.envelope.EnvelopeException;
import cl.duoc.pedidos360.messaging.envelope.QueryBusinessException;
import cl.duoc.pedidos360.messaging.envelope.QueryResponse;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;

/**
 * Politica de fallos simple de las consultas.
 *
 * <pre>
 * cola funcional -> fallo transitorio -> retry corto -> cola funcional -> segundo fallo -> DLQ
 * fallo definitivo (sobre, actor, operacion, payload, plazo) ------------------------> DLQ
 * error de negocio esperado (403/404/409) -----------------------------> respuesta correlacionada
 * </pre>
 *
 * <p>Un solo retry: no se usan las etapas 5/30/120, que pertenecen al flujo de Pedidos.
 *
 * <p>Regla de confirmacion: el request original se confirma solo cuando la respuesta o la
 * transferencia quedo confirmada por el broker sin return.
 *
 * <p>Regla de hilo: {@link #gestionar} se ejecuta en el hilo del listener. Un fallo de handoff
 * <strong>no</strong> se reintenta con espera en ese hilo — eso bloquearia la unica ventana de
 * {@code prefetch=1}. Se intenta una unica transferencia inmediata y, si no se confirma, se devuelve
 * {@link Resultado#SIN_CONFIRMAR}: la recuperacion del consumidor (cierre de canal con backoff y
 * reinicio del listener) la ejecuta {@link QueryConsumerRecovery} en su propio executor, de modo que
 * la redelivery del broker rehace el intento completo.
 *
 * <p>Distincion que no debe perderse:
 *
 * <ul>
 *   <li><strong>retry del mensaje</strong>: cola de retry con TTL de plataforma, un solo intento,
 *       sin consumidor y sin dormir ningun hilo;</li>
 *   <li><strong>reintento del handoff</strong>: inmediato y unico; si no se confirma, se delega en la
 *       recuperacion asincrona del consumidor.</li>
 * </ul>
 */
public final class QueryFailureHandler {

    /** Resultado del tratamiento de un fallo. Solo un resultado con transferencia confirmada autoriza ACK. */
    public enum Resultado {
        /** Error de negocio esperado respondido y confirmado: se puede ACK. */
        RESPONDIDO,
        /** Transferido al retry corto: el request original tambien se confirma. */
        REINTENTADO,
        /** Transferido a la DLQ: el request original se confirma. */
        DLQ,
        /** La transferencia no se confirmo: el mensaje permanece sin ACK y se recupera el consumidor. */
        SIN_CONFIRMAR
    }

    private static final Logger log = LoggerFactory.getLogger(QueryFailureHandler.class);

    private final HandoffPublisher handoff;
    private final QueryReplyPublisher respuestas;
    private final MessagingProperties properties;
    private final QueryTopology topology;

    public QueryFailureHandler(HandoffPublisher handoff, QueryReplyPublisher respuestas,
            MessagingProperties properties, QueryTopology topology) {
        this.handoff = handoff;
        this.respuestas = respuestas;
        this.properties = properties;
        this.topology = topology;
    }

    /**
     * Gestiona el fallo de una consulta sin las propiedades AMQP originales.
     *
     * <p>Se conserva para clasificacion y diagnostico; la ruta de production pasa las propiedades del
     * mensaje recibido para que la transferencia preserve {@code correlationId} y {@code replyTo}.
     */
    public Resultado gestionar(RequestEnvelope envelope, int retryCount, Exception fallo, String correlationId,
            String replyTo, boolean plazoVencido) {
        return gestionar(envelope, retryCount, fallo, correlationId, replyTo, plazoVencido, null);
    }

    /**
     * Gestiona el fallo de una consulta.
     *
     * @param plazoVencido distingue el caso en que el envelope ya no tiene presupuesto: un mensaje
     *     vencido no se reintenta, se diagnostica y va a DLQ.
     * @param original propiedades del mensaje recibido, clonadas en la transferencia. Sin ellas la
     *     respuesta del retry no tendria destino.
     */
    public Resultado gestionar(RequestEnvelope envelope, int retryCount, Exception fallo, String correlationId,
            String replyTo, boolean plazoVencido, MessageProperties original) {
        if (plazoVencido) {
            log.error("Consulta messageId={} correlationId={} plazo vencido: no se ejecuta ni se reintenta; {}",
                    messageIdSeguro(envelope), correlationId, fallo.getClass().getSimpleName());
            return transferir(envelope, retryCount, fallo, Destino.DLQ, original);
        }
        if (respondeAlSolicitante(fallo)) {
            log.warn("Consulta messageId={} correlationId={} respuesta de negocio: {}", messageIdSeguro(envelope),
                    correlationId, fallo.getMessage());
            try {
                respuestas.publicar(envelope, replyTo, correlationId, respuestaDeFallo(envelope, fallo, correlationId));
                return Resultado.RESPONDIDO;
            } catch (HandoffFailureException handoffFallido) {
                log.error("Consulta messageId={} correlationId={} respuesta de negocio no confirmada: {}",
                        messageIdSeguro(envelope), correlationId, handoffFallido.getMessage());
                return transferir(envelope, retryCount, fallo, Destino.RETRY, original);
            }
        }
        if (definitivo(fallo)) {
            log.error("Consulta messageId={} correlationId={} retryCount={} fallo definitivo: {} - {}",
                    messageIdSeguro(envelope), correlationId, retryCount, fallo.getClass().getSimpleName(),
                    fallo.getMessage());
            return transferir(envelope, retryCount, fallo, Destino.DLQ, original);
        }
        if (retryCount >= 1) {
            log.error("Consulta messageId={} correlationId={} retryCount={} agotado: {}", messageIdSeguro(envelope),
                    correlationId, retryCount, fallo.getClass().getSimpleName());
            return transferir(envelope, retryCount, fallo, Destino.DLQ, original);
        }
        log.warn("Consulta messageId={} correlationId={} fallo transitorio, retry corto: {}",
                messageIdSeguro(envelope), correlationId, fallo.getClass().getSimpleName());
        return transferir(envelope, retryCount, fallo, Destino.RETRY, original);
    }

    /** Clase de error diagnosticada, sin datos sensibles. */
    public static String claseDeFallo(Exception fallo) {
        return fallo.getClass().getSimpleName();
    }

    private enum Destino { RETRY, DLQ }

    /**
     * Error de negocio esperado: se responde con el codigo equivalente al contrato HTTP y se confirma
     * el mensaje, sin retry ni DLQ.
     *
     * <p>Un 404 solo se responde cuando la operacion describe un recurso concreto
     * ({@code mapNotFound}). En un listado sin parametro, un 404 inesperado es un fallo definitivo y
     * termina en DLQ para su inspeccion.
     */
    private boolean respondeAlSolicitante(Exception fallo) {
        if (!(fallo instanceof QueryBusinessException negocio)) return false;
        return negocio.status().value() != 404 || topology.mapNotFound();
    }

    /**
     * Definitivo: no se ejecuta y no se reintenta.
     *
     * <p>Solo se clasifican aqui los fallos que no pueden mejorar con otra entrega: sobre invalido,
     * autorizacion del sobre rechazada o autorizacion de dominio denegada. Una excepcion no
     * clasificada, aunque sea de tipo {@code IllegalArgumentException}, recibe el retry corto: el
     * operador debe ver el fallo real del procesador en lugar de perder el mensaje en el primer
     * intento.
     */
    private boolean definitivo(Exception fallo) {
        return fallo instanceof EnvelopeException || fallo instanceof ActorContextException
                || fallo instanceof org.springframework.security.access.AccessDeniedException;
    }

    private QueryResponse respuestaDeFallo(RequestEnvelope envelope, Exception fallo, String correlationId) {
        var negocio = (QueryBusinessException) fallo;
        return QueryResponse.error(envelope, correlationId, negocio.aError(), respuestas.ahora());
    }

    /**
     * Transferencia inmediata y unica, sin espera en el hilo del listener.
     *
     * <p>Si no se confirma, devuelve {@link Resultado#SIN_CONFIRMAR}: el mensaje queda sin ACK y
     * {@link QueryConsumerRecovery} cierra el canal y reinicia el listener con backoff. No se usa
     * {@code requeue=true} ni un bucle de reintentos caliente.
     */
    private Resultado transferir(RequestEnvelope envelope, int retryCount, Exception fallo, Destino destino,
            MessageProperties original) {
        try {
            if (destino == Destino.RETRY) {
                handoff.aRetry(envelope, retryCount, claseDeFallo(fallo), original);
                return Resultado.REINTENTADO;
            }
            handoff.aDlq(envelope, retryCount, claseDeFallo(fallo), original);
            return Resultado.DLQ;
        } catch (HandoffFailureException handoffFallido) {
            log.error("Consulta messageId={} transferencia a {} no confirmada; queda SIN CONFIRMAR "
                    + "y la recuperacion del consumidor rehara el intento: {}", messageIdSeguro(envelope), destino,
                    handoffFallido.getMessage());
            return Resultado.SIN_CONFIRMAR;
        }
    }

    private static String messageIdSeguro(RequestEnvelope envelope) {
        return envelope == null || envelope.messageId() == null ? "DESCONOCIDO" : envelope.messageId().toString();
    }
}
