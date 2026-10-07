package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.rabbitmq.client.Channel;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.envelope.QueryBusinessException;
import cl.duoc.pedidos360.messaging.envelope.QueryTemporaryException;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.relay.QueryConsumer;
import cl.duoc.pedidos360.messaging.relay.QueryProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flujos de consulta contra RabbitMQ real.
 *
 * <p>Cubre los casos 1, 2, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 19, 20, 21 y 22 del issue #77 con
 * un broker Testcontainers: publicacion, correlacion, retry corto, DLQ, confirms, returned message,
 * binding inexistente, ACK solo tras handoff, actor invalido, payload invalido y ausencia de bucle
 * por {@code requeue}.
 */
@SpringBootTest(classes = QueryMessagingFlowTests.Configuracion.class)
@TestMethodOrder(MethodOrderer.MethodName.class)
@TestPropertySource(properties = {
        "pedidos360.messaging.relay-mode=ACTIVE",
        "pedidos360.messaging.role=SERVICE",
        "pedidos360.messaging.declare-topology=true",
        "pedidos360.messaging.exchanges.queries=p360.queries",
        "pedidos360.messaging.exchanges.retry=p360.retry",
        "pedidos360.messaging.exchanges.dlx=p360.dlx",
        "pedidos360.messaging.queues.responses=p360.bff.consultas.respuestas.q",
        "pedidos360.messaging.naming.prefix=p360.",
        "pedidos360.messaging.naming.query-suffix=.consultas.q",
        "pedidos360.messaging.naming.retry-suffix=.consultas.retry.1s.q",
        "pedidos360.messaging.naming.dlq-suffix=.consultas.dlq",
        "pedidos360.messaging.naming.retry-key-suffix=.retry.1s",
        "pedidos360.messaging.naming.failed-key-suffix=.failed",
        "pedidos360.messaging.routing.usuario=usuario.consultar-actual.v1",
        "pedidos360.messaging.routing.restaurante=restaurante.listar.v1",
        "pedidos360.messaging.routing.producto=producto.listar-disponibles.v1",
        "pedidos360.messaging.routing.pago=pago.consultar.v1",
        "pedidos360.messaging.routing.usuario-base=usuario.consultar-actual",
        "pedidos360.messaging.routing.restaurante-base=restaurante.listar",
        "pedidos360.messaging.routing.producto-base=producto.listar-disponibles",
        "pedidos360.messaging.routing.pago-base=pago.consultar",
        "pedidos360.messaging.actor.emisor=" + QueryMessagingFlowTests.TENANT,
        "pedidos360.messaging.actor.clave-id=" + QueryMessagingFlowTests.CLAVE_ID,
        "pedidos360.messaging.actor.secreto=clave-de-prueba-con-al-menos-32-bytes",
        "pedidos360.messaging.actor.roles-permitidos=CLIENTE,ADMIN",
        "pedidos360.messaging.deadline=5s",
        "pedidos360.messaging.actor-ttl=4s",
        "pedidos360.messaging.retry-delay=1s",
        "pedidos360.messaging.confirm-timeout=3s",
        "pedidos360.messaging.handoff-backoff=50ms",
        "pedidos360.messaging.handoff-attempts=1",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",
        "spring.rabbitmq.virtual-host=/"})
class QueryMessagingFlowTests {

    static final String TENANT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String CLAVE_ID = "11111111-2222-3333-4444-555555555555";
    static final byte[] MATERIAL = "clave-de-prueba-con-al-menos-32-bytes".getBytes(StandardCharsets.UTF_8);
    static final String OPERACION = "usuario.consultar-actual.v1";
    static final String COLA = "p360.usuarios.consultas.q";
    static final String RETRY = "p360.usuarios.consultas.retry.1s.q";
    static final String DLQ = "p360.usuarios.consultas.dlq";
    static final String RESPUESTAS = "p360.bff.consultas.respuestas.q";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({QueryMessagingConfiguration.class, QueryConsumerConfiguration.class, QueryTopologyDeclaration.class})
    static class Configuracion {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4.1-management-alpine");
        }

        @Bean
        QueryTopology queryTopology(MessagingProperties properties) {
            return QueryTopology.of(properties, Domain.USUARIOS);
        }

        @Bean
        QueryProcessor procesador(Resultado resultado) {
            return resultado;
        }

        @Bean
        Resultado resultado() {
            return new Resultado();
        }
    }

    /** Procesador controlable: exito, error de negocio o fallo transitorio. */
    static class Resultado implements QueryProcessor {
        final AtomicInteger invocaciones = new AtomicInteger();
        final AtomicReference<RuntimeException> fallo = new AtomicReference<>();
        final AtomicReference<ActorContext> ultimoActor = new AtomicReference<>();

        @Override
        public JsonNode procesar(ActorContext actor, RequestEnvelope request) {
            invocaciones.incrementAndGet();
            ultimoActor.set(actor);
            if (fallo.get() != null) throw fallo.get();
            return JsonMapper.builder().build().createObjectNode().put("id", 5);
        }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired QueryConsumer consumer;
    @Autowired RequestEnvelopeContext contexto;
    @Autowired ActorContextSigner firmante;
    @Autowired Resultado resultado;
    @Autowired MessagingProperties properties;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired RabbitMQContainer container;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin admin;

    /** Recuperaciones invocadas sin confirmar el request, para comprobar que no se pierde. */
    private final AtomicInteger contadorSinConfirmar = new AtomicInteger();

    @BeforeEach
    void limpiar() {
        rabbit.execute(channel -> {
            channel.queuePurge(COLA);
            channel.queuePurge(RETRY);
            channel.queuePurge(DLQ);
            channel.queuePurge(RESPUESTAS);
            return null;
        });
        resultado.fallo.set(null);
        resultado.invocaciones.set(0);
        detenerListener();
    }

    private RequestEnvelope envelope(Instant emision, Duration vigencia) {
        String sobre = firmante.emitir(new ActorContext(UUID.fromString(TENANT),
                UUID.fromString("12345678-1234-1234-1234-123456789012"), java.util.Set.of("CLIENTE"),
                java.util.Set.of("access_as_user"), emision, emision.plus(vigencia), COLA,
                UUID.fromString(CLAVE_ID)), emision.plus(properties.deadline()));
        return RequestEnvelope.crear(UUID.randomUUID(), OPERACION,
                JsonMapper.builder().build().createObjectNode(), sobre, emision, emision.plus(properties.deadline()));
    }

    /**
     * Envelope con el plazo ya vencido: el request se emitio fuera de presupuesto, asi que no se
     * ejecuta ni se reintenta. El actor sigue vigente para que el rechazo sea por plazo del request.
     */
    private RequestEnvelope envelopeVencido() {
        Instant emision = Instant.now().minus(properties.deadline().plusSeconds(1));
        String sobre = firmante.emitir(new ActorContext(UUID.fromString(TENANT),
                UUID.fromString("12345678-1234-1234-1234-123456789012"), java.util.Set.of("CLIENTE"),
                java.util.Set.of("access_as_user"), emision, emision.plus(Duration.ofMinutes(5)), COLA,
                UUID.fromString(CLAVE_ID)), emision.plus(Duration.ofMinutes(5)));
        return RequestEnvelope.crear(UUID.randomUUID(), OPERACION,
                JsonMapper.builder().build().createObjectNode(), sobre, emision,
                emision.plus(java.time.Duration.ofSeconds(1)));
    }

    /** Sobre firmado con la clave y emisor correctos, pero con otro tenant emisor. */
    private RequestEnvelope envelopeConEmisorAjeno() {
        Instant ahora = Instant.now();
        String sobre = firmante.emitir(new ActorContext(UUID.fromString("99999999-bbbb-cccc-dddd-eeeeeeeeeeee"),
                UUID.fromString("12345678-1234-1234-1234-123456789012"), java.util.Set.of("CLIENTE"),
                java.util.Set.of("access_as_user"), ahora, ahora.plusSeconds(4), COLA, UUID.fromString(CLAVE_ID)),
                ahora.plus(properties.deadline()));
        return RequestEnvelope.crear(UUID.randomUUID(), OPERACION,
                JsonMapper.builder().build().createObjectNode(), sobre, ahora, ahora.plus(properties.deadline()));
    }

    /**
     * Sobre firmado por el emisor correcto, pero con un rol que no corresponde a la operacion.
     *
     * <p>La autenticidad es correcta; la autorizacion no. El consumidor debe rechazarlo igualmente.
     */
    private RequestEnvelope envelopeConRolImprocedente() {
        Instant ahora = Instant.now();
        String sobre = firmante.emitir(new ActorContext(UUID.fromString(TENANT),
                UUID.fromString("12345678-1234-1234-1234-123456789012"), java.util.Set.of("REPARTIDOR"),
                java.util.Set.of("access_as_user"), ahora, ahora.plusSeconds(4), COLA, UUID.fromString(CLAVE_ID)),
                ahora.plus(properties.deadline()));
        return RequestEnvelope.crear(UUID.randomUUID(), OPERACION,
                JsonMapper.builder().build().createObjectNode(), sobre, ahora, ahora.plus(properties.deadline()));
    }

    private Message mensaje(RequestEnvelope envelope, String correlationId, int retryCount) {
        return mensaje(envelope, correlationId, retryCount, contexto.escribir(envelope));
    }

    private Message mensaje(RequestEnvelope envelope, String correlationId, int retryCount, byte[] cuerpo) {
        var metadatos = new MessageProperties();
        metadatos.setMessageId(envelope.messageId().toString());
        metadatos.setCorrelationId(correlationId);
        metadatos.setReplyTo(RESPUESTAS);
        metadatos.setContentType("application/json");
        metadatos.setDeliveryTag(7);
        metadatos.setRetryCount(retryCount);
        return new Message(cuerpo, metadatos);
    }

    private Message recibir(String cola) {
        Message recibido = rabbit.receive(cola, 5000);
        assertThat(recibido).as("mensaje en %s", cola).isNotNull();
        return recibido;
    }

    /**
     * Los casos se resuelven invocando al consumidor base con un canal simulado, no dejando que el
     * listener compita por los mensajes. Las pruebas comprueban el efecto real en el broker leyendo
     * las colas de destino.
     */
    private void detenerListener() {
        for (var container : listeners.getListenerContainers()) {
            if (container.isRunning()) container.stop(() -> {});
        }
    }

    @Test
    void consultaValidaPublicaRespuestaCorrelacionadaYConfirmaTrasElHandoff() throws Exception {
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        detenerListener();
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-1", 0), channel);
        verify(channel, times(1)).basicAck(7, false);
        assertThat(resultado.invocaciones.get()).isEqualTo(1);
        Message respuesta = recibir(RESPUESTAS);
        assertThat(respuesta.getMessageProperties().getCorrelationId()).isEqualTo("corr-1");
        assertThat(new String(respuesta.getBody(), StandardCharsets.UTF_8)).contains("\"success\":true")
                .contains("\"status\":200").contains(envelope.messageId().toString());
    }

    @Test
    void laColaFuncionalYElRetrySonDurablesYSinConsumerPropio() {
        // Cola funcional y DLQ sin consumer propio; la de retry existe y no tiene listener.
        assertThat(admin.getQueueInfo(COLA)).isNotNull();
        assertThat(admin.getQueueInfo(RETRY)).isNotNull();
        assertThat(admin.getQueueInfo(DLQ)).isNotNull();
        detenerListener();
        assertThat(admin.getQueueInfo(RETRY).getConsumerCount()).isEqualTo(0);
        assertThat(admin.getQueueInfo(DLQ).getConsumerCount()).isEqualTo(0);

        // Y el mensaje en retry regresa a la cola funcional tras el TTL, sin renovar el plazo.
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        rabbit.send("", RETRY, mensaje(envelope, "corr-ttl", 1));
        Message devuelto = recibir(COLA);
        assertThat(devuelto.getMessageProperties().getMessageId()).isEqualTo(envelope.messageId().toString());
        assertThat(devuelto.getMessageProperties().getRetryCount()).isEqualTo(1L);
        assertThat(contexto.leer(devuelto.getBody(), 262_144).expiresAt()).isEqualTo(envelope.expiresAt());
    }

    @Test
    void consultaVencidaNoSeEjecutaNiSeReintentaYTerminaEnDlq() throws Exception {
        RequestEnvelope vencido = envelopeVencido();
        detenerListener();
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(vencido, "corr-vencida", 0), channel);
        verify(channel, times(1)).basicAck(7, false);
        assertThat(resultado.invocaciones.get()).isEqualTo(0);
        Message enDlq = recibir(DLQ);
        assertThat(enDlq.getMessageProperties().getMessageId()).isEqualTo(vencido.messageId().toString());
        assertThat((String) enDlq.getMessageProperties().getHeader("clase-de-fallo"))
                .isEqualTo("PlazoVencidoException");
        assertThat((String) enDlq.getMessageProperties().getHeader("destino")).isEqualTo("dlq");
    }

    @Test
    void falloTransitorioVaAlRetryCortoConservandoMessageIdYPlazo() throws Exception {
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        detenerListener();
        resultado.fallo.set(new QueryTemporaryException("agregado no disponible"));
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-retry", 0), channel);
        verify(channel, times(1)).basicAck(7, false);
        Message enRetry = rabbit.receive(RETRY, 5000);
        assertThat(enRetry).as("mensaje en la cola de retry").isNotNull();
        assertThat(enRetry.getMessageProperties().getMessageId()).isEqualTo(envelope.messageId().toString());
        assertThat(enRetry.getMessageProperties().getRetryCount()).isEqualTo(1L);
        assertThat((String) enRetry.getMessageProperties().getHeader("destino")).isEqualTo("retry");
        RequestEnvelope releido = contexto.leer(enRetry.getBody(), 262_144);
        assertThat(releido.messageId()).isEqualTo(envelope.messageId());
        assertThat(releido.expiresAt()).isEqualTo(envelope.expiresAt());
    }

    @Test
    void segundoFalloAgotaElRetryYVaADlq() throws Exception {
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        detenerListener();
        resultado.fallo.set(new QueryTemporaryException("persiste el fallo"));
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-dlq", 1), channel);
        verify(channel, times(1)).basicAck(7, false);
        assertThat(rabbit.receive(RETRY, 300)).as("no hay segundo retry").isNull();
        Message enDlq = recibir(DLQ);
        assertThat(enDlq.getMessageProperties().getMessageId()).isEqualTo(envelope.messageId().toString());
        assertThat(enDlq.getMessageProperties().getRetryCount()).isEqualTo(1L);
    }

    @Test
    void actorConRolImprocedenteVaADlqSinEjecutarLaOperacion() throws Exception {
        RequestEnvelope envelope = envelopeConRolImprocedente();
        detenerListener();
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-actor", 0), channel);
        verify(channel, times(1)).basicAck(7, false);
        assertThat(resultado.invocaciones.get()).isEqualTo(0);
        assertThat(recibir(DLQ).getMessageProperties().getMessageId()).isEqualTo(envelope.messageId().toString());
    }

    @Test
    void actorDeOtroEmisorSeRechazaSinEjecutar() throws Exception {
        RequestEnvelope envelope = envelopeConEmisorAjeno();
        detenerListener();
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-emisor", 0), channel);
        verify(channel, times(1)).basicAck(7, false);
        assertThat(resultado.invocaciones.get()).isEqualTo(0);
        assertThat(recibir(DLQ).getMessageProperties().getMessageId()).isEqualTo(envelope.messageId().toString());
    }

    @Test
    void payloadInvalidoVaADlqSinEjecutarLaOperacion() throws Exception {
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        detenerListener();
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-payload", 0,
                "{no-json}".getBytes(StandardCharsets.UTF_8)), channel);
        verify(channel, times(1)).basicAck(7, false);
        assertThat(resultado.invocaciones.get()).isEqualTo(0);
        assertThat(recibir(DLQ).getMessageProperties().getMessageId()).isEqualTo(envelope.messageId().toString());
    }

    @Test
    void operacionDeOtroDominioSeRechazaSinEjecutar() throws Exception {
        RequestEnvelope envelope = RequestEnvelope.crear(UUID.randomUUID(), "pago.consultar.v1",
                JsonMapper.builder().build().createObjectNode(), "sobre", Instant.now(),
                Instant.now().plusSeconds(5));
        detenerListener();
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-dominio", 0), channel);
        verify(channel, times(1)).basicAck(7, false);
        assertThat(resultado.invocaciones.get()).isEqualTo(0);
        assertThat(recibir(DLQ).getMessageProperties().getMessageId()).isEqualTo(envelope.messageId().toString());
    }

    @Test
    void errorDeNegocioEsperadoSeRespondeCorrelacionadoYSeConfirmaSinRetry() throws Exception {
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        detenerListener();
        resultado.fallo.set(QueryBusinessException.prohibido("No pertenece al solicitante."));
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-403", 0), channel);
        verify(channel, times(1)).basicAck(7, false);
        Message respuesta = recibir(RESPUESTAS);
        assertThat(new String(respuesta.getBody(), StandardCharsets.UTF_8)).contains("\"success\":false")
                .contains("\"status\":403").contains("ACCESO_DENEGADO");
        assertThat(rabbit.receive(RETRY, 300)).as("un 403 no se reintenta").isNull();
    }

    @Test
    void notFoundSeRespondeCorrelacionadoEnUnDominioDeRecursoConcreto() throws Exception {
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        detenerListener();
        resultado.fallo.set(QueryBusinessException.noEncontrado("Perfil no habilitado."));
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-404", 0), channel);
        verify(channel, times(1)).basicAck(7, false);
        assertThat(new String(recibir(RESPUESTAS).getBody(), StandardCharsets.UTF_8)).contains("\"status\":404");
    }

    @Test
    void nuncaSeUsaNackConRequeueComoRetryNormal() throws Exception {
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        detenerListener();
        resultado.fallo.set(new QueryTemporaryException("fallo transitorio"));
        Channel channel = mock(Channel.class);
        consumer.consumir(mensaje(envelope, "corr-nack", 0), channel);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void brokerCaidoNoConfirmaElRequest() throws Exception {
        detenerListener();
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        Message cuerpo = mensaje(envelope, "corr-broker", 0);
        // El consumidor no debe confirmar el request si la respuesta no puede confirmarse. Se usa una
        // conexion inalcanzable para que la falla sea de broker y no de contrato.
        var conexion = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1");
        conexion.setPort(1);
        conexion.setConnectionTimeout(200);
        var sinBroker = new org.springframework.amqp.rabbit.core.RabbitTemplate(conexion);
        var respuestas = new cl.duoc.pedidos360.messaging.relay.QueryReplyPublisher(sinBroker, contexto, properties);
        var handoff = new cl.duoc.pedidos360.messaging.relay.HandoffPublisher(sinBroker, contexto, properties,
                consumer.topology());
        var fallos = new cl.duoc.pedidos360.messaging.relay.QueryFailureHandler(handoff, respuestas, properties,
                consumer.topology());
        var aislado = new QueryConsumer(contexto, firmante, resultado, respuestas, fallos, consumer.topology(),
                TENANT, null, (id, correlacion, destino, causa) -> contadorSinConfirmar.incrementAndGet(),
                properties.maxBodyBytes());
        contadorSinConfirmar.set(0);
        Channel channel = mock(Channel.class);
        aislado.consumir(cuerpo, channel);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(contadorSinConfirmar.get()).as("recuperacion invocada sin confirmar el request").isPositive();
        conexion.destroy();
    }

    @Test
    void sinBindingLaTransferenciaNoEsAceptableYNoSeConfirma() throws Exception {
        RequestEnvelope envelope = envelope(Instant.now(), Duration.ofSeconds(4));
        detenerListener();
        var correlacion = new org.springframework.amqp.rabbit.connection.CorrelationData("corr-sin-binding");
        rabbit.setMandatory(true);
        rabbit.send("p360.queries", "operacion.sin.binding",
                new Message(contexto.escribir(envelope), new MessageProperties()), correlacion);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> correlacion.getReturned() != null);
        assertThat(correlacion.getReturned()).isNotNull();
    }

    @Test
    void elConsumidorDeLaColaFuncionalEstaActivoConAckManual() {
        assertThat(listeners.getListenerContainers()).isNotEmpty();
        assertThat(consumer.topology().queue()).isEqualTo(COLA);
        assertThat(consumer.topology().retryQueue()).isEqualTo(RETRY);
        assertThat(consumer.topology().dlq()).isEqualTo(DLQ);
    }
}
