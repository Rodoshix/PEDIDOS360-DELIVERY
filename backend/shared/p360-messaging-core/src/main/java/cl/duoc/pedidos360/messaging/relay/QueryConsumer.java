package cl.duoc.pedidos360.messaging.relay;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;

import com.rabbitmq.client.Channel;

import cl.duoc.pedidos360.messaging.QueryTopology;
import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.envelope.EnvelopeException;
import cl.duoc.pedidos360.messaging.envelope.QueryResponse;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;

/**
 * Consumidor base de consultas con ACK manual.
 *
 * <p>Orden de una entrega:
 *
 * <ol>
 *   <li>deserializacion estricta del envelope;</li>
 *   <li>comprobacion del plazo absoluto antes de ejecutar;</li>
 *   <li>operacion declarada por el servicio contra la routing key configurada;</li>
 *   <li>verificacion del contexto de actor: firma, emisor, destino y vigencia;</li>
 *   <li>comprobaciones propias del dominio;</li>
 *   <li>ejecucion de la operacion;</li>
 *   <li>publicacion confirmada de la respuesta correlacionada;</li>
 *   <li>ACK manual solo despues de esa confirmacion.</li>
 * </ol>
 *
 * <p>Nunca se confirma el request antes de que exista respuesta confirmada o transferencia
 * confirmada a retry/DLQ. No existe un ciclo de {@code requeue=true} como retry normal.
 *
 * <p>El procesador debe ser idempotente: si la respuesta se confirma y despues se pierde el ACK, la
 * redelivery puede volver a ejecutar la operacion. El BFF descarta la respuesta duplicada.
 */
public class QueryConsumer {

    private static final Logger log = LoggerFactory.getLogger(QueryConsumer.class);

    private final RequestEnvelopeContext contexto;
    private final ActorContextSigner actor;
    private final QueryProcessor procesador;
    private final QueryReplyPublisher respuestas;
    private final QueryFailureHandler fallos;
    private final QueryTopology topology;
    private final String operacionEsperada;
    private final String emisorEsperado;
    private final List<String> destinosPermitidos;
    private final QueryPrecheck precheck;
    private final HandoffRecovery recuperacion;
    private final Clock reloj;
    private final int maxBodyBytes;

    /**
     * @param emisorEsperado tenant de Entra que debe haber emitido el sobre.
     * @param precheck comprobaciones adicionales del dominio, posteriores a la firma.
     * @param recuperacion politica aplicada cuando una transferencia queda sin confirmar.
     */
    public QueryConsumer(RequestEnvelopeContext contexto, ActorContextSigner actor, QueryProcessor procesador,
            QueryReplyPublisher respuestas, QueryFailureHandler fallos, QueryTopology topology,
            String emisorEsperado, QueryPrecheck precheck, HandoffRecovery recuperacion, int maxBodyBytes) {
        this.contexto = contexto;
        this.actor = actor;
        this.procesador = procesador;
        this.respuestas = respuestas;
        this.fallos = fallos;
        this.topology = topology;
        this.operacionEsperada = topology.routingKey();
        this.emisorEsperado = emisorEsperado;
        this.destinosPermitidos = List.of(topology.queue());
        this.precheck = precheck;
        this.recuperacion = recuperacion;
        this.reloj = Clock.systemUTC();
        this.maxBodyBytes = maxBodyBytes;
    }

    public QueryTopology topology() {
        return topology;
    }

    @RabbitListener(queues = "#{@queryTopology.queue()}", containerFactory = "queryListenerFactory")
    public void consumir(Message message, Channel channel) throws IOException {
        MessageProperties metadatos = message.getMessageProperties();
        String correlationId = metadatos.getCorrelationId();
        String messageId = metadatos.getMessageId();
        // retry-count es un campo reservado de Spring AMQP: se lee por su API, no como header libre.
        int intentos = (int) Math.max(0, metadatos.getRetryCount());
        RequestEnvelope envelope = null;
        try {
            envelope = contexto.leer(message.getBody(), maxBodyBytes);
            if (envelope.vencido(reloj.instant())) {
                responderVencida(envelope, correlationId, metadatos.getReplyTo());
                // responderVencida siempre lanza: no se llega aqui.
            }
            validarOperacion(envelope);
            ActorContext contextoActor = actor.verificar(envelope.actor(), emisorEsperado, destinosPermitidos,
                    envelope.expiresAt());
            if (precheck != null) precheck.validar(contextoActor);
            var payload = procesador.procesar(contextoActor, envelope);
            respuestas.publicar(envelope, metadatos.getReplyTo(), correlationId,
                    QueryResponse.exito(envelope, correlationId, payload, respuestas.ahora()));
            confirmar(channel, metadatos.getDeliveryTag(), messageId, correlationId, intentos, "RESPONDIDA");
        } catch (Exception fallo) {
            gestionarFallo(message, channel, envelope, fallo, correlationId, intentos, messageId);
        }
    }

    private void gestionarFallo(Message message, Channel channel, RequestEnvelope envelope, Exception fallo,
            String correlationId, int intentos, String messageId) {
        RequestEnvelope diagnosticable = envelope != null ? envelope : sobreParaDiagnostico(messageId);
        QueryFailureHandler.Resultado resultado;
        try {
            resultado = fallos.gestionar(diagnosticable, intentos, fallo, correlationId,
                    message.getMessageProperties().getReplyTo(), fallo instanceof PlazoVencidoException);
        } catch (RuntimeException inesperado) {
            log.error("Consulta messageId={} fallo al gestionar el error; mensaje SIN CONFIRMAR: {}", messageId,
                    inesperado.getMessage());
            recuperacion.sinConfirmar(messageId, correlationId, "politica-de-fallos", inesperado);
            return;
        }
        if (resultado == QueryFailureHandler.Resultado.SIN_CONFIRMAR) {
            String destino = fallo instanceof PlazoVencidoException ? "dlq" : "transferencia";
            recuperacion.sinConfirmar(messageId, correlationId, destino, fallo);
            return;
        }
        confirmar(channel, message.getMessageProperties().getDeliveryTag(), messageId, correlationId, intentos,
                resultado.name());
    }

    private void validarOperacion(RequestEnvelope envelope) {
        if (!operacionEsperada.equals(envelope.operacion()))
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                    "la operacion no corresponde a esta cola funcional");
    }

    /**
     * Un mensaje vencido no se ejecuta ni se reintenta. Si al solicitante todavia le queda
     * presupuesto, recibe un error correlacionado 504; en ambos casos el mensaje se diagnostica y
     * termina en DLQ.
     */
    private void responderVencida(RequestEnvelope envelope, String correlationId, String replyTo) {
        var error = new QueryResponse.ErrorDetail("PLAZO_AGOTADO", "Plazo agotado",
                "La consulta no se ejecuto porque su plazo ya habia vencido.", 504);
        try {
            respuestas.publicar(envelope, replyTo, correlationId,
                    QueryResponse.error(envelope, correlationId, error, respuestas.ahora()));
        } catch (HandoffFailureException sinPresupuesto) {
            log.warn("Consulta messageId={} vencida y sin respuesta confirmada: {}", envelope.messageId(),
                    sinPresupuesto.getMessage());
        }
        throw new PlazoVencidoException("consulta vencida antes de ejecutarse");
    }

    private void confirmar(Channel channel, long deliveryTag, String messageId, String correlationId, int intentos,
            String resultado) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ackIncierto) {
            // El ACK no quedo confirmado: el broker reentregara. No se vuelve a transferir el mensaje
            // para no duplicar la respuesta ni la entrada en retry/DLQ.
            log.error("Consulta messageId={} correlationId={} ACK no confirmado; se espera redelivery: {}",
                    messageId, correlationId, ackIncierto.getMessage());
            return;
        }
        log.info("Consulta messageId={} correlationId={} retryCount={} {} ACK", messageId, correlationId, intentos,
                resultado);
    }

    /** Sobre minimo para diagnosticar un mensaje que no pudo deserializarse. */
    private RequestEnvelope sobreParaDiagnostico(String messageId) {
        UUID identidad;
        try {
            identidad = UUID.fromString(messageId);
        } catch (RuntimeException sinIdentidad) {
            identidad = UUID.randomUUID();
        }
        Instant ahora = reloj.instant();
        return RequestEnvelope.crear(identidad, operacionEsperada,
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(), "diagnostico", ahora,
                ahora.plusSeconds(1));
    }

    /** Marca interna: el envelope llego vencido. */
    static final class PlazoVencidoException extends RuntimeException {
        PlazoVencidoException(String detalle) {
            super(detalle);
        }
    }
}
