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
 * <p>Si la transferencia no se confirma, el mensaje queda sin ACK y se solicita la recuperacion del
 * consumidor a {@link HandoffRecovery}. Con {@code prefetch=1} esa recuperacion es imprescindible:
 * sin ella el canal queda sano y el consumidor detenido indefinidamente.
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
    private final boolean guarded;

    /**
     * @param emisorEsperado tenant de Entra que debe haber emitido el sobre.
     * @param precheck comprobaciones adicionales del dominio, posteriores a la firma.
     * @param recuperacion politica aplicada cuando una transferencia queda sin confirmar.
     */
    public QueryConsumer(RequestEnvelopeContext contexto, ActorContextSigner actor, QueryProcessor procesador,
            QueryReplyPublisher respuestas, QueryFailureHandler fallos, QueryTopology topology,
            String emisorEsperado, QueryPrecheck precheck, HandoffRecovery recuperacion, int maxBodyBytes) {
        this(contexto, actor, procesador, respuestas, fallos, topology, emisorEsperado, precheck,
                recuperacion, maxBodyBytes, Clock.systemUTC());
    }

    public QueryConsumer(RequestEnvelopeContext contexto, ActorContextSigner actor, QueryProcessor procesador,
            QueryReplyPublisher respuestas, QueryFailureHandler fallos, QueryTopology topology,
            String emisorEsperado, QueryPrecheck precheck, HandoffRecovery recuperacion, int maxBodyBytes, Clock reloj) {
        this(contexto, actor, procesador, respuestas, fallos, topology, emisorEsperado, precheck,
                recuperacion, maxBodyBytes, reloj, false);
    }

    public QueryConsumer(RequestEnvelopeContext contexto, ActorContextSigner actor, QueryProcessor procesador,
            QueryReplyPublisher respuestas, QueryFailureHandler fallos, QueryTopology topology,
            String emisorEsperado, QueryPrecheck precheck, HandoffRecovery recuperacion, int maxBodyBytes,
            Clock reloj, boolean guarded) {
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
        this.reloj = java.util.Objects.requireNonNull(reloj);
        this.maxBodyBytes = maxBodyBytes;
        this.guarded = guarded;
    }

    public QueryTopology topology() {
        return topology;
    }

    /**
     * Listener de la cola funcional.
     *
     * <p>El identificador es estable ({@link QueryConsumerRecovery#QUERY_LISTENER_ID}) porque la
     * recuperacion necesita localizar este container en el registro de listeners.
     */
    @RabbitListener(id = QueryConsumerRecovery.QUERY_LISTENER_ID, queues = "#{@queryTopology.queue()}",
            containerFactory = "queryListenerFactory")
    public void consumir(Message message, Channel channel) throws IOException {
        long receivedTicks = System.nanoTime();
        Instant receivedAt = reloj.instant();
        MessageProperties metadatos = message.getMessageProperties();
        String correlationId = metadatos.getCorrelationId();
        String messageId = metadatos.getMessageId();
        // retry-count es un campo reservado de Spring AMQP: se lee por su API, no como header libre.
        int intentos = (int) Math.min(Integer.MAX_VALUE, Math.max(0, metadatos.getRetryCount()));
        RequestEnvelope envelope = null;
        QueryDeadlineGuard guard = null;
        try {
            envelope = contexto.leer(message.getBody(), maxBodyBytes);
            if (guarded) {
                if (java.time.Duration.between(envelope.occurredAt(), envelope.expiresAt())
                        .compareTo(java.time.Duration.ofSeconds(5)) > 0
                        || envelope.occurredAt().isAfter(receivedAt.plusSeconds(5)))
                    throw new EnvelopeException(EnvelopeException.Reason.TIEMPO_INVALIDO,
                            "consulta fuera del presupuesto temporal permitido");
                guard = new QueryDeadlineGuard(envelope.expiresAt(), reloj, System::nanoTime, receivedTicks, receivedAt);
                guard.remainingNanos();
                // Retry DLX restores the original functional routing key. Headers cannot grant an exception.
                if (!operacionEsperada.equals(metadatos.getReceivedRoutingKey())
                        || !MessageProperties.CONTENT_TYPE_JSON.equals(metadatos.getContentType())
                        || !envelope.messageId().toString().equals(messageId)
                        || correlationId == null || !canonicalUuid(correlationId)
                        || !respuestas.replyToPermitido(metadatos.getReplyTo()))
                    throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                            "propiedades AMQP no corresponden al contrato");
            }
            if (envelope.vencido(reloj.instant())) {
                throw new PlazoVencidoException("consulta vencida antes de ejecutarse");
            }
            validarOperacion(envelope);
            ActorContext contextoActor = actor.verificar(envelope.actor(), emisorEsperado, destinosPermitidos,
                    envelope.expiresAt());
            if (guard != null) guard.narrow(contextoActor.expiraEn());
            if (precheck != null) precheck.validar(contextoActor);
            if (envelope.vencido(reloj.instant()))
                throw new PlazoVencidoException("consulta vencida antes del procesamiento");
            var payload = guard == null ? procesador.procesar(contextoActor, envelope)
                    : procesador.procesar(contextoActor, envelope, guard);
            if (guard != null) guard.remainingNanos();
            if (envelope.vencido(reloj.instant()))
                throw new PlazoVencidoException("consulta vencida durante el procesamiento");
            var respuesta = QueryResponse.exito(envelope, correlationId, payload, respuestas.ahora());
            if (guard == null) respuestas.publicar(envelope, metadatos.getReplyTo(), correlationId, respuesta);
            else respuestas.publicar(envelope, metadatos.getReplyTo(), correlationId, respuesta, guard);
        } catch (Exception fallo) {
            gestionarFallo(message, channel, envelope, fallo, correlationId, intentos, messageId, guard);
            return;
        }
        // Settlement fuera del tratamiento de negocio: nunca origina otra publicación.
        confirmar(channel, metadatos.getDeliveryTag(), messageId, correlationId, intentos, "RESPONDIDA");
    }

    private void gestionarFallo(Message message, Channel channel, RequestEnvelope envelope, Exception fallo,
            String correlationId, int intentos, String messageId, QueryDeadlineGuard guard) {
        RequestEnvelope diagnosticable = envelope != null ? envelope : sobreParaDiagnostico(messageId);
        MessageProperties original = message.getMessageProperties();
        QueryFailureHandler.Resultado resultado;
        try {
            // Las propiedades originales viajan a la transferencia: sin ellas el retry perderia
            // correlationId y replyTo, y la respuesta del segundo intento no tendria destino.
            if (guard == null) resultado = fallos.gestionar(diagnosticable, intentos, fallo, correlationId,
                    original.getReplyTo(), fallo instanceof PlazoVencidoException || diagnosticable.vencido(reloj.instant()), original);
            else resultado = fallos.gestionar(diagnosticable, intentos, fallo, correlationId,
                    original.getReplyTo(), fallo instanceof PlazoVencidoException || diagnosticable.vencido(reloj.instant())
                            || guard != null && guard.exhausted(), original, guard);
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
        // El ACK usa la etiqueta de la entrega recibida, no la de la copia transferida.
        confirmar(channel, original.getDeliveryTag(), messageId, correlationId, intentos, resultado.name());
    }

    private void validarOperacion(RequestEnvelope envelope) {
        if (!operacionEsperada.equals(envelope.operacion()))
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                    "la operacion no corresponde a esta cola funcional");
    }

    private static boolean canonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void confirmar(Channel channel, long deliveryTag, String messageId, String correlationId, int intentos,
            String resultado) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException | com.rabbitmq.client.ShutdownSignalException ackIncierto) {
            // Settlement incierto: conservar el original y recuperar con backoff.
            // La redelivery puede repetir una lectura; no se publica otra copia aquí.
            log.error("Consulta messageId={} correlationId={} ACK no confirmado; se espera redelivery: {}",
                    messageId, correlationId, ackIncierto.getClass().getSimpleName());
            recuperacion.sinConfirmar(messageId, correlationId, "settlement", ackIncierto);
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
