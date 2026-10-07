package cl.duoc.pedidos360.messaging.relay;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * transferencia quedo confirmada por el broker sin return. Si la transferencia no se confirma, el
 * mensaje permanece sin ACK con recuperacion acotada y sin {@code requeue=true}.
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
        /** La transferencia no se confirmo: el mensaje permanece sin ACK. */
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
     * Gestiona el fallo de una consulta.
     *
     * <p>El parametro {@code plazoVencido} distingue el caso en que el envelope ya no tiene
     * presupuesto: un mensaje vencido no se reintenta, se diagnostica y va a DLQ.
     */
    public Resultado gestionar(RequestEnvelope envelope, int retryCount, Exception fallo, String correlationId,
            String replyTo, boolean plazoVencido) {
        if (plazoVencido) {
            log.error("Consulta messageId={} correlationId={} plazo vencido: no se ejecuta ni se reintenta; {}",
                    messageIdSeguro(envelope), correlationId, fallo.getClass().getSimpleName());
            return transferir(envelope, retryCount, fallo, Destino.DLQ);
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
                return transferir(envelope, retryCount, fallo, Destino.RETRY);
            }
        }
        if (definitivo(fallo)) {
            log.error("Consulta messageId={} correlationId={} retryCount={} fallo definitivo: {} - {}",
                    messageIdSeguro(envelope), correlationId, retryCount, fallo.getClass().getSimpleName(),
                    fallo.getMessage());
            return transferir(envelope, retryCount, fallo, Destino.DLQ);
        }
        if (retryCount >= 1) {
            log.error("Consulta messageId={} correlationId={} retryCount={} agotado: {}", messageIdSeguro(envelope),
                    correlationId, retryCount, fallo.getClass().getSimpleName());
            return transferir(envelope, retryCount, fallo, Destino.DLQ);
        }
        log.warn("Consulta messageId={} correlationId={} fallo transitorio, retry corto: {}",
                messageIdSeguro(envelope), correlationId, fallo.getClass().getSimpleName());
        return transferir(envelope, retryCount, fallo, Destino.RETRY);
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
     * Recuperacion acotada del handoff: reintenta la transferencia con espera entre intentos.
     *
     * <p>No usa {@code requeue=true} ni produce un bucle inmediato. Si se agotan los intentos, el
     * mensaje queda sin confirmar para que el canal se recupere sin perderlo.
     */
    private Resultado transferir(RequestEnvelope envelope, int retryCount, Exception fallo, Destino destino) {
        HandoffFailureException ultimo = null;
        for (int intento = 1; intento <= properties.handoffAttempts(); intento++) {
            try {
                if (destino == Destino.RETRY) {
                    handoff.aRetry(envelope, retryCount, claseDeFallo(fallo));
                    return Resultado.REINTENTADO;
                }
                handoff.aDlq(envelope, retryCount, claseDeFallo(fallo));
                return Resultado.DLQ;
            } catch (HandoffFailureException handoffFallido) {
                ultimo = handoffFallido;
                log.warn("Consulta messageId={} intento {} de transferencia a {} no confirmado: {}",
                        messageIdSeguro(envelope), intento, destino, handoffFallido.getMessage());
                esperar(properties.handoffBackoff());
            }
        }
        if (destino == Destino.DLQ) {
            log.error("Consulta messageId={} transferencia a DLQ no confirmada; el mensaje permanece SIN CONFIRMAR: {}",
                    messageIdSeguro(envelope), ultimo == null ? "sin causa" : ultimo.getMessage());
            return Resultado.SIN_CONFIRMAR;
        }
        log.error("Consulta messageId={} handoff a retry no confirmado tras {} intentos; mensaje SIN CONFIRMAR: {}",
                messageIdSeguro(envelope), properties.handoffAttempts(),
                ultimo == null ? "sin causa" : ultimo.getMessage());
        return Resultado.SIN_CONFIRMAR;
    }

    private void esperar(Duration espera) {
        if (espera == null || espera.isZero() || espera.isNegative()) return;
        try {
            Thread.sleep(espera.toMillis());
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
        }
    }

    private static String messageIdSeguro(RequestEnvelope envelope) {
        return envelope == null || envelope.messageId() == null ? "DESCONOCIDO" : envelope.messageId().toString();
    }
}
