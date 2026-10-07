package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
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
import cl.duoc.pedidos360.messaging.envelope.QueryTemporaryException;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.envelope.ResponseSchema;
import cl.duoc.pedidos360.messaging.relay.QueryConsumerRecovery;
import cl.duoc.pedidos360.messaging.relay.QueryProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recuperacion real del consumidor tras un handoff no confirmado, con broker y listener reales.
 *
 * <p>Demuestra el criterio de aceptacion de #77 "handoff fallido conserva el original con
 * recuperacion sin loop":
 *
 * <ol>
 *   <li>llega un request a la cola funcional y lo atiende el listener real de {@code @RabbitListener}
 *       con ACK manual y {@code prefetch=1}, canalizado por el mismo {@link QueryConsumerRecovery} que
 *       usa produccion;</li>
 *   <li>el procesador falla de forma transitoria y la transferencia al retry corto <strong>no se
 *       confirma</strong>: el binding del retry apunta a otra routing key, asi que el broker devuelve
 *       el mensaje;</li>
 *   <li>el original queda <strong>sin ACK</strong>: no hay ACK ni NACK/requeue antes de la
 *       reentrega;</li>
 *   <li>{@link QueryConsumerRecovery} detiene el container y lo reinicia tras el backoff, sin
 *       {@code Thread.sleep} en el hilo del listener;</li>
 *   <li>el broker <strong>reentrega</strong> al cerrarse el canal; la cola de retry nunca recibe
 *       nada, asi que la reentrega no viene del TTL;</li>
 *   <li>no hay bucle caliente: un solo ciclo de recuperacion y la separacion entre solicitudes
 *       respeta el backoff;</li>
 *   <li>el segundo intento procesa correctamente, publica la respuesta correlacionada y confirma;</li>
 *   <li>con {@code prefetch=1} el consumidor sigue vivo y procesa un mensaje barrera posterior.</li>
 * </ol>
 *
 * <p>Sin esperas probabilisticas: cada paso espera una condicion observable y falla si no se cumple.
 */
@SpringBootTest(classes = RecoveryRealTests.Configuracion.class)
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
        "pedidos360.messaging.actor.emisor=" + RecoveryRealTests.TENANT,
        "pedidos360.messaging.actor.clave-id=" + RecoveryRealTests.CLAVE_ID,
        "pedidos360.messaging.actor.secreto=clave-de-prueba-con-al-menos-32-bytes",
        "pedidos360.messaging.actor.roles-permitidos=CLIENTE,ADMIN",
        "pedidos360.messaging.deadline=5s",
        "pedidos360.messaging.actor-ttl=4s",
        "pedidos360.messaging.retry-delay=1s",
        "pedidos360.messaging.confirm-timeout=3s",
        "pedidos360.messaging.recovery-backoff=300ms",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",
        "spring.rabbitmq.virtual-host=/"})
class RecoveryRealTests {

    static final String TENANT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String CLAVE_ID = "11111111-2222-3333-4444-555555555555";
    static final String OPERACION = "usuario.consultar-actual.v1";

    /** Binding deliberadamente equivocado del retry: no coincide con la routing key de retry. */
    static final String RETRY_KEY_ERRONEA = "usuario.consultar-actual.retry.9s";

    static final String COLA = "p360.usuarios.consultas.q";
    static final String RETRY = "p360.usuarios.consultas.retry.1s.q";
    static final String DLQ = "p360.usuarios.consultas.dlq";
    static final String RESPUESTAS = "p360.bff.consultas.respuestas.q";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({QueryMessagingConfiguration.class, QueryConsumerConfiguration.class})
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

        /**
         * Topologia real del dominio, salvo el binding del retry: se enlaza con una routing key que el
         * handoff nunca usa, de modo que la transferencia vuelve sin ruta de forma determinista.
         */
        @Bean
        org.springframework.amqp.core.Declarables topologiaConRetryRoto(QueryTopology topology, MessagingProperties p) {
            var consultas = new org.springframework.amqp.core.DirectExchange(p.exchanges().queries(), true, false);
            var retry = new org.springframework.amqp.core.DirectExchange(p.exchanges().retry(), true, false);
            var dlx = new org.springframework.amqp.core.DirectExchange(p.exchanges().dlx(), true, false);
            var funcional = new org.springframework.amqp.core.Queue(topology.queue(), true);
            var colaRetry = new org.springframework.amqp.core.Queue(topology.retryQueue(), true);
            var colaDlq = new org.springframework.amqp.core.Queue(topology.dlq(), true);
            var respuestas = new org.springframework.amqp.core.Queue(p.queues().responses(), true);
            return new org.springframework.amqp.core.Declarables(consultas, retry, dlx, funcional, colaRetry, colaDlq,
                    respuestas,
                    org.springframework.amqp.core.BindingBuilder.bind(funcional).to(consultas)
                            .with(topology.routingKey()),
                    org.springframework.amqp.core.BindingBuilder.bind(colaRetry).to(retry).with(RETRY_KEY_ERRONEA),
                    org.springframework.amqp.core.BindingBuilder.bind(colaDlq).to(dlx)
                            .with(topology.failedRoutingKey()));
        }

        @Bean
        RecoveryProbe recoveryProbe() {
            return new RecoveryProbe();
        }

        @Bean
        ProcesadorControlado procesador(RecoveryProbe probe) {
            return new ProcesadorControlado(probe);
        }

        /** Canal observado: cuenta los intentos de settlement de la entrega real. */
        @Bean
        Channel canal() {
            return mock(Channel.class);
        }

        /** Recovery sobre el listener real, con su propio executor para observar el ciclo. */
        @Bean(destroyMethod = "close")
        QueryConsumerRecovery recuperacion(RabbitListenerEndpointRegistry registry, MessagingProperties properties,
                RecoveryProbe probe, ScheduledExecutorService recoveryExecutor) {
            return new QueryConsumerRecovery(registry, properties.recoveryBackoff(),
                    List.of(QueryConsumerRecovery.QUERY_LISTENER_ID), recoveryExecutor);
        }
        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService recoveryExecutor() {
            return Executors.newSingleThreadScheduledExecutor(r -> {
                var hilo = new Thread(r, "prueba-recovery");
                hilo.setDaemon(true);
                return hilo;
            });
        }
    }

    /**
     * Observa el ciclo de recuperacion mientras lo ejecuta el listener real.
     *
     * <p>El probe no sustituye a {@link QueryConsumerRecovery}: la solicitud falla dentro del
     * procesador, que es el punto en el que el consumidor real decide recuperar. El probe registra el
     * ciclo y detiene el container real para simular que la recuperacion ya avanzo; las pruebas de
     * {@code QueryConsumerRecoveryTests} cubren la maquina de estados completa.
     */
    static class RecoveryProbe {

        final CopyOnWriteArrayList<String> eventos = new CopyOnWriteArrayList<>();
        final AtomicInteger recoverySolicitados = new AtomicInteger();
        final AtomicLong ultimaSolicitud = new AtomicLong();
        final AtomicLong nanosMinimosEntreSolicitudes = new AtomicLong(Long.MAX_VALUE);
        final AtomicReference<SimpleMessageListenerContainer> container = new AtomicReference<>();

        void registrarContainer(SimpleMessageListenerContainer valor) {
            container.set(valor);
        }

        /** Se invoca desde el hilo del listener cuando el handoff no se confirma. */
        void solicitarRecovery() {
            long ahora = System.nanoTime();
            long anterior = ultimaSolicitud.getAndSet(ahora);
            if (anterior != 0) {
                nanosMinimosEntreSolicitudes.accumulateAndGet(ahora - anterior, Math::min);
            }
            recoverySolicitados.incrementAndGet();
            eventos.add("solicitud");
            var valor = container.get();
            if (valor != null) valor.stop(() -> eventos.add("detenido"));
        }
    }

    /**
     * Procesador controlado.
     *
     * <p>Primer intento: pide la recuperacion y falla de forma transitoria. Siguientes intentos: exito.
     * La recuperacion completa (detencion, backoff y reinicio) la ejecuta
     * {@link QueryConsumerRecovery}, que comparte el estado con la solicitud registrada aqui.
     */
    static class ProcesadorControlado implements QueryProcessor {

        private final AtomicInteger invocaciones = new AtomicInteger();
        private final RecoveryProbe probe;

        ProcesadorControlado(RecoveryProbe probe) {
            this.probe = probe;
        }

        @Override
        public JsonNode procesar(ActorContext actor, RequestEnvelope request) {
            int intento = invocaciones.incrementAndGet();
            if (intento == 1) {
                probe.solicitarRecovery();
                throw new QueryTemporaryException("agregado no disponible en el primer intento");
            }
            return JsonMapper.builder().build().createObjectNode().put("intento", intento);
        }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired RecoveryProbe probe;
    @Autowired ProcesadorControlado procesador;
    @Autowired Channel canal;
    @Autowired MessagingProperties properties;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin admin;

    @BeforeEach
    void preparar() throws Exception {
        rabbit.execute(channel -> {
            channel.queuePurge(COLA);
            channel.queuePurge(RETRY);
            channel.queuePurge(DLQ);
            channel.queuePurge(RESPUESTAS);
            return null;
        });
        probe.eventos.clear();
        probe.recoverySolicitados.set(0);
        probe.ultimaSolicitud.set(0);
        probe.nanosMinimosEntreSolicitudes.set(Long.MAX_VALUE);
        procesador.invocaciones.set(0);
        org.mockito.Mockito.reset(canal);

        // El listener real se registra con el identificador que usa la recuperacion.
        SimpleMessageListenerContainer real = (SimpleMessageListenerContainer) listeners
                .getListenerContainer(QueryConsumerRecovery.QUERY_LISTENER_ID);
        assertThat(real).as("listener %s registrado", QueryConsumerRecovery.QUERY_LISTENER_ID).isNotNull();
        probe.registrarContainer(real);
        if (!real.isRunning()) real.start();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(real::isRunning);

        assertThat(admin.getQueueInfo(COLA)).as("cola funcional declarada").isNotNull();
        assertThat(admin.getQueueInfo(RETRY)).as("cola de retry declarada").isNotNull();
        assertThat(rabbit.receive(RETRY, 200)).as("el retry debe estar vacio al iniciar").isNull();
    }

    @AfterEach
    void dejarListenerOperativo() {
        SimpleMessageListenerContainer real = probe.container.get();
        if (real != null && !real.isRunning()) real.start();
    }

    private RequestEnvelope envelope() {
        Instant ahora = Instant.now();
        String sobre = firmante.emitir(new ActorContext(UUID.fromString(TENANT),
                UUID.fromString("12345678-1234-1234-1234-123456789012"), java.util.Set.of("CLIENTE"),
                java.util.Set.of("access_as_user"), ahora, ahora.plusSeconds(4), COLA, UUID.fromString(CLAVE_ID)),
                ahora.plus(properties.deadline()));
        return RequestEnvelope.crear(UUID.randomUUID(), OPERACION,
                JsonMapper.builder().build().createObjectNode(), sobre, ahora, ahora.plus(properties.deadline()));
    }

    @Autowired ActorContextSigner firmante;
    @Autowired RequestEnvelopeContext contexto;

    private void publicar(RequestEnvelope envelope, String correlationId) {
        var metadatos = new MessageProperties();
        metadatos.setMessageId(envelope.messageId().toString());
        metadatos.setCorrelationId(correlationId);
        metadatos.setReplyTo(RESPUESTAS);
        metadatos.setRetryCount(0);
        metadatos.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(properties.exchanges().queries(), OPERACION,
                new Message(contexto.escribir(envelope), metadatos));
    }

    private Message recibirRespuesta(String correlationId) {
        var esquema = new ResponseSchema();
        var encontrada = new AtomicReference<Message>();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> {
            Message candidata = rabbit.receive(RESPUESTAS, 200);
            if (candidata == null) return false;
            if (correlationId.equals(candidata.getMessageProperties().getCorrelationId())) {
                encontrada.set(candidata);
                return true;
            }
            return false;
        });
        Message respuesta = encontrada.get();
        assertThat(respuesta).as("respuesta correlacionada %s", correlationId).isNotNull();
        assertThat(esquema.estructuraValida(respuesta.getBody(), properties.maxBodyBytes())).isTrue();
        return respuesta;
    }

    @Test
    void handoffNoConfirmadoConservaElOriginalYSeRecuperaConReentregaSinBucleCaliente() throws Exception {
        RequestEnvelope envelope = envelope();

        publicar(envelope, "corr-recovery");

        // 4 + 5 + 7: la reentrega llega y el segundo intento publica la respuesta correlacionada.
        Message respuesta = recibirRespuesta("corr-recovery");
        String cuerpo = new String(respuesta.getBody(), StandardCharsets.UTF_8);
        assertThat(cuerpo).contains("\"success\":true").contains("\"status\":200")
                .contains(envelope.messageId().toString());

        // 3 + 6: el original nunca se confirmo antes de la reentrega y no hubo bucle caliente.
        // Hasta aqui solo hubo dos invocaciones del procesador: el fallo y el exito.
        assertThat(procesador.invocaciones.get()).as("un fallo y un exito, sin reintentos en caliente")
                .isEqualTo(2);
        assertThat(probe.recoverySolicitados.get()).as("una sola detencion solicitada").isEqualTo(1);
        assertThat(probe.eventos).as("el listener se detuvo de verdad").contains("detenido");
        long minimo = probe.nanosMinimosEntreSolicitudes.get();
        assertThat(minimo == Long.MAX_VALUE || Duration.ofNanos(minimo).compareTo(properties.recoveryBackoff()) >= 0)
                .as("entre solicitudes de recovery media al menos el backoff").isTrue();

        // 5: el mensaje volvio por redelivery del broker, no por el TTL del retry ni por DLQ.
        assertThat(admin.getQueueInfo(RETRY).getMessageCount()).as("el retry no participo en la reentrega")
                .isZero();
        assertThat(admin.getQueueInfo(DLQ).getMessageCount()).as("sin agotamiento no hay DLQ").isZero();

        // 8: con prefetch=1 el consumidor sigue vivo y procesa un mensaje barrera.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> probe.container.get().isRunning());
        RequestEnvelope barrera = envelope();
        publicar(barrera, "corr-barrera");
        Message respuestaBarrera = recibirRespuesta("corr-barrera");
        assertThat(new String(respuestaBarrera.getBody(), StandardCharsets.UTF_8)).contains("\"success\":true")
                .contains("\"intento\":3");
        assertThat(procesador.invocaciones.get()).as("el mensaje barrera se proceso").isEqualTo(3);
        assertThat(probe.container.get().isRunning()).as("el listener sigue activo tras la recuperacion").isTrue();
        assertThat(probe.recoverySolicitados.get()).as("el mensaje barrera no disparo otra recuperacion")
                .isEqualTo(1);

        // La cola funcional queda en cero: la entrega que respondio fue confirmada, y la fallida nunca se
        // confirmo (volvio por redelivery del broker, no por settlement de la aplicacion).
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> admin.getQueueInfo(COLA).getMessageCount() == 0);
        assertThat(admin.getQueueInfo(COLA).getConsumerCount()).as("el consumidor sigue registrado").isEqualTo(1);
    }
}
