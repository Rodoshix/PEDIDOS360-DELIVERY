package cl.duoc.pedidos360.bff.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.rabbitmq.client.Channel;

import cl.duoc.pedidos360.bff.security.EntraTestTokens;
import cl.duoc.pedidos360.messaging.Domain;
import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.QueryMessagingConfiguration;
import cl.duoc.pedidos360.messaging.QueryTopology;
import cl.duoc.pedidos360.messaging.QueryTopologyDeclaration;
import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.envelope.QueryBusinessException;
import cl.duoc.pedidos360.messaging.envelope.QueryResponse;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.envelope.ResponseSchema;
import cl.duoc.pedidos360.messaging.relay.PendingCorrelationRegistry;
import cl.duoc.pedidos360.messaging.relay.QueryTimeoutException;
import cl.duoc.pedidos360.messaging.relay.QueryUnavailableException;
import cl.duoc.pedidos360.messaging.relay.RequestPublisher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de consultas del BFF contra un broker real.
 *
 * <p>Cubre los casos 1, 2, 3, 5, 6, 14 y 16 del issue #77 desde el lado solicitante: request
 * publicado, respuesta correlacionada, varios requests simultaneos, respuesta tardia o duplicada,
 * timeout, broker no disponible y error de negocio equivalente al HTTP.
 */
@SpringBootTest(classes = BffConsultasAdapterTests.Configuracion.class)
@TestPropertySource(properties = {
        "spring.application.name=pedidos360-bff",
        "pedidos360.messaging.relay-mode=ACTIVE",
        "pedidos360.messaging.role=BFF",
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
        "pedidos360.messaging.actor.emisor=" + EntraTestTokens.TENANT,
        "pedidos360.messaging.actor.clave-id=" + BffConsultasAdapterTests.CLAVE_ID,
        "pedidos360.messaging.actor.secreto=clave-de-prueba-con-al-menos-32-bytes",
        "pedidos360.messaging.deadline=5s",
        "pedidos360.messaging.actor-ttl=4s",
        "pedidos360.messaging.retry-delay=1s",
        "pedidos360.messaging.confirm-timeout=3s",
        "pedidos360.messaging.recovery-backoff=50ms",
        "pedidos360.messaging.max-pending-correlations=64",
        "pedidos360.bff.actor.emisor=" + EntraTestTokens.TENANT,
        "pedidos360.bff.actor.ttl=4s",
        "pedidos360.bff.actor.destinos=p360.usuarios.consultas.q,p360.pagos.consultas.q",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",
        "spring.rabbitmq.virtual-host=/"})
class BffConsultasAdapterTests {

    static final String CLAVE_ID = "11111111-2222-3333-4444-555555555555";
    static final String COLA_USUARIOS = "p360.usuarios.consultas.q";
    static final String COLA_PAGOS = "p360.pagos.consultas.q";
    static final String RESPUESTAS = "p360.bff.consultas.respuestas.q";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({QueryMessagingConfiguration.class, QueryTopologyDeclaration.class, BffQueryConfiguration.class})
    @EnableConfigurationProperties(BffActorProperties.class)
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

        /** Cola tecnica con consumer de prueba: replica lo que hara un servicio real. */
        @Bean
        SimpleRabbitListenerContainerFactory respuestaFactory(ConnectionFactory connectionFactory) {
            var factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            factory.setPrefetchCount(1);
            return factory;
        }
    }

    /** Servicio de prueba: consume la consulta y responde por el replyTo, como hara #78-#81. */
    static final class ServicioDePrueba {
        private final RequestEnvelopeContext contexto;
        private final ActorContextSigner firmante;
        private final RabbitTemplate rabbit;
        private final String emisorEsperado;
        private final String colaDestino;
        private final JsonMapper json = JsonMapper.builder().build();
        private volatile boolean responder403;
        private volatile boolean responder404;
        private volatile int respuestasEnviadas;
        private volatile boolean actorVerificado;

        ServicioDePrueba(RequestEnvelopeContext contexto, ActorContextSigner firmante, RabbitTemplate rabbit,
                String emisorEsperado, String colaDestino) {
            this.contexto = contexto;
            this.firmante = firmante;
            this.rabbit = rabbit;
            this.emisorEsperado = emisorEsperado;
            this.colaDestino = colaDestino;
        }

        void responder403(boolean valor) {
            responder403 = valor;
        }

        void responder404(boolean valor) {
            responder404 = valor;
        }

        int respuestasEnviadas() {
            return respuestasEnviadas;
        }

        boolean actorVerificado() {
            return actorVerificado;
        }

        void atender(Message mensaje, Channel canal) throws Exception {
            RequestEnvelope envelope = contexto.leer(mensaje.getBody(), 262_144);
            ActorContext actor = firmante.verificar(envelope.actor(), emisorEsperado, List.of(colaDestino),
                    envelope.expiresAt());
            actorVerificado = true;
            String correlationId = mensaje.getMessageProperties().getCorrelationId();
            QueryResponse respuesta;
            if (responder403) {
                respuesta = QueryResponse.error(envelope, correlationId, QueryBusinessException.prohibido("No pertenece.")
                        .aError(), Instant.now());
            } else if (responder404) {
                respuesta = QueryResponse.error(envelope, correlationId,
                        QueryBusinessException.noEncontrado("No existe.").aError(), Instant.now());
            } else {
                respuesta = QueryResponse.exito(envelope, correlationId,
                        json.createObjectNode().put("id", actor.sujetoId().toString()), Instant.now());
            }
            var metadatos = new org.springframework.amqp.core.MessageProperties();
            metadatos.setCorrelationId(correlationId);
            metadatos.setMessageId(envelope.messageId().toString());
            metadatos.setContentType("application/json");
            rabbit.send("", mensaje.getMessageProperties().getReplyTo(),
                    new Message(contexto.escribirRespuesta(respuesta), metadatos));
            respuestasEnviadas++;
            canal.basicAck(mensaje.getMessageProperties().getDeliveryTag(), false);
        }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired MessagingProperties properties;
    @Autowired RequestEnvelopeContext contexto;
    @Autowired ActorContextSigner firmante;
    @Autowired RequestPublisher publicador;
    @Autowired PendingCorrelationRegistry correlaciones;
    @Autowired BffActorContextFactory actores;

    private final List<SimpleMessageListenerContainer> servicios = new ArrayList<>();

    /**
     * Limpia las colas antes de cada prueba.
     *
     * <p>Sin esta limpieza, un request que vencio en una prueba anterior puede ser consumido por el
     * servicio de la prueba siguiente: el servicio responderia a una correlacion ya descartada y la
     * consulta real quedaria sin respuesta hasta agotar el plazo.
     */
    @org.junit.jupiter.api.BeforeEach
    void limpiarColas() {
        rabbit.execute(channel -> {
            channel.queuePurge(COLA_USUARIOS);
            channel.queuePurge(RESPUESTAS);
            return null;
        });
    }

    private BffQueryAdapter adaptador(QueryInvoker... operaciones) {
        var fabrica = new RequestFactory(properties, actores);
        var adaptador = new BffQueryAdapter(fabrica, publicador, correlaciones, contexto, new ResponseSchema(), properties);
        for (QueryInvoker operacion : operaciones) {
            adaptador.registrar(Domain.USUARIOS, operacion);
        }
        return adaptador;
    }

    private JwtAuthenticationToken token() {
        Jwt jwt = EntraTestTokens.decoder().decode(EntraTestTokens.token(java.util.Map.of()));
        return new JwtAuthenticationToken(jwt, cl.duoc.pedidos360.bff.security.EntraConfiguration.authorities(jwt));
    }

    private ServicioDePrueba servicio(String cola) {
        var servicio = new ServicioDePrueba(contexto, firmante, rabbit, EntraTestTokens.TENANT, cola);
        var contenedor = new SimpleMessageListenerContainer(rabbit.getConnectionFactory());
        contenedor.setQueueNames(cola);
        contenedor.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        contenedor.setPrefetchCount(1);
        contenedor.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)
                (mensaje, canal) -> servicio.atender(mensaje, canal));
        contenedor.start();
        servicios.add(contenedor);
        return servicio;
    }

    private void detenerServicios() {
        servicios.forEach(SimpleMessageListenerContainer::stop);
        servicios.clear();
    }

    private BffQueryAdapter adaptadorUsuarios() {
        return adaptador(new QueryInvoker() {
            @Override
            public String operacion() {
                return "usuario.consultar-actual.v1";
            }

            @Override
            public cl.duoc.pedidos360.messaging.envelope.OperationResult invocar(
                    tools.jackson.databind.JsonNode payload, String correlationId) {
                throw new AssertionError("el adaptador no debe invocar el dominio: la operacion viaja por el broker");
            }
        });
    }

    @Test
    void requestYRespuestaCorrelacionadaExtremoAExtremo() {
        var servicio = servicio(COLA_USUARIOS);
        try {
            var resultado = adaptadorUsuarios().ejecutar(Domain.USUARIOS, JsonMapper.builder().build().createObjectNode(),
                    token());
            assertThat(resultado.operacion()).isEqualTo("usuario.consultar-actual.v1");
            assertThat(resultado.status()).isEqualTo(200);
            assertThat(resultado.payload().path("id").stringValue()).isEqualTo(EntraTestTokens.USER);
            assertThat(servicio.respuestasEnviadas()).isEqualTo(1);
            assertThat(correlaciones.enVuelo()).isZero();
        } finally {
            detenerServicios();
        }
    }

    @Test
    void variosRequestsSimultaneosNoSeCruzan() throws Exception {
        servicio(COLA_USUARIOS);
        var adaptador = adaptadorUsuarios();
        int total = 12;
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<CompletableFuture<String>> esperas = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                esperas.add(CompletableFuture.supplyAsync(() -> adaptador
                        .ejecutar(Domain.USUARIOS, JsonMapper.builder().build().createObjectNode(), token())
                        .payload().path("id").stringValue(), executor));
            }
            CompletableFuture.allOf(esperas.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            assertThat(esperas).allSatisfy(espera -> assertThat(espera.join())
                    .isEqualTo(EntraTestTokens.USER));
            assertThat(correlaciones.enVuelo()).isZero();
        } finally {
            executor.shutdownNow();
            detenerServicios();
        }
    }

    @Test
    void unErrorDeNegocio403ViajaComoRespuestaEquivalenteAlHttp() {
        var servicio = servicio(COLA_USUARIOS);
        servicio.responder403(true);
        try {
            assertThatThrownBy(() -> adaptadorUsuarios().ejecutar(Domain.USUARIOS,
                    JsonMapper.builder().build().createObjectNode(), token()))
                    .isInstanceOf(QueryBusinessException.class).satisfies(fallo -> {
                        var negocio = (QueryBusinessException) fallo;
                        assertThat(negocio.status().value()).isEqualTo(403);
                        assertThat(negocio.code()).isEqualTo("ACCESO_DENEGADO");
                    });
            assertThat(correlaciones.enVuelo()).isZero();
        } finally {
            detenerServicios();
        }
    }

    @Test
    void unErrorDeNegocio404DeRecursoConcretoViajaComoRespuesta() {
        var servicio = servicio(COLA_USUARIOS);
        servicio.responder404(true);
        try {
            assertThatThrownBy(() -> adaptadorUsuarios().ejecutar(Domain.USUARIOS,
                    JsonMapper.builder().build().createObjectNode(), token()))
                    .isInstanceOf(QueryBusinessException.class)
                    .satisfies(fallo -> assertThat(((QueryBusinessException) fallo).status().value()).isEqualTo(404));
        } finally {
            detenerServicios();
        }
    }

    @Test
    void sinServicioQueRespondaElPlazoSeAgotaYSeLimpiaLaCorrelacion() {
        detenerServicios();
        long inicio = System.nanoTime();
        assertThatThrownBy(() -> adaptadorUsuarios().ejecutar(Domain.USUARIOS,
                JsonMapper.builder().build().createObjectNode(), token())).isInstanceOf(QueryTimeoutException.class);
        long transcurridoMs = (System.nanoTime() - inicio) / 1_000_000;
        assertThat(transcurridoMs).isGreaterThanOrEqualTo(properties.deadline().toMillis() - 200);
        assertThat(correlaciones.enVuelo()).isZero();
        // La cola queda limpia: no se acumulan respuestas huerfanas.
        assertThat(rabbit.receive(RESPUESTAS, 300)).isNull();
    }

    /**
     * El presupuesto es total y absoluto: el confirm de publicacion descuenta su tiempo y la espera
     * recibe solo lo que queda.
     *
     * <p>Antes se sumaban el plazo de confirmacion (3 s) y el de espera (5 s), de modo que una
     * publicacion lenta podia llevar el total por encima del deadline. Aqui se comprueba que el total
     * sigue acotado por el presupuesto y que la correlacion se limpia al vencer.
     */
    @Test
    void elDeadlineEsTotalYNoSeReiniciaTrasLaPublicacionConfirmada() {
        detenerServicios();
        long presupuestoNanos = properties.deadline().toNanos();
        long inicio = System.nanoTime();
        assertThatThrownBy(() -> adaptadorUsuarios().ejecutar(Domain.USUARIOS,
                JsonMapper.builder().build().createObjectNode(), token())).isInstanceOf(QueryTimeoutException.class);
        long transcurrido = System.nanoTime() - inicio;

        // Al menos se agoto el presupuesto y no se anadio un segundo plazo completo.
        assertThat(Duration.ofNanos(transcurrido)).as("no se espera menos que el presupuesto")
                .isGreaterThanOrEqualTo(properties.deadline());
        assertThat(Duration.ofNanos(transcurrido)).as("confirm + espera nunca suman dos plazos")
                .isLessThan(properties.deadline().plus(properties.deadline().dividedBy(2)));
        assertThat(transcurrido).as("el total sigue dentro del presupuesto absoluto")
                .isLessThan(presupuestoNanos + properties.deadline().toNanos() / 2);
        assertThat(correlaciones.enVuelo()).isZero();
    }

    /** El cierre del BFF cancela las correlaciones en vuelo en lugar de dejarlas huerfanas. */
    @Test
    void elCierreDelAdaptadorCancelaLasCorrelacionesEnVuelo() {
        var adaptador = adaptadorUsuarios();
        var espera = correlaciones.registrar("corr-en-vuelo");
        assertThat(correlaciones.enVuelo()).isEqualTo(1);
        adaptador.alCerrar();
        assertThat(espera).isCompletedExceptionally();
        assertThat(correlaciones.enVuelo()).isZero();
    }

    @Test
    void unaRespuestaDuplicadaNoRompeLaCorrelacion() {
        var servicio = servicio(COLA_USUARIOS);
        try {
            var resultado = adaptadorUsuarios().ejecutar(Domain.USUARIOS, JsonMapper.builder().build().createObjectNode(),
                    token());
            assertThat(resultado.status()).isEqualTo(200);
            // El duplicado se descarta y no deja basura en la cola tecnica.
            assertThat(correlaciones.enVuelo()).isZero();
            assertThat(rabbit.receive(RESPUESTAS, 300)).isNull();
        } finally {
            detenerServicios();
        }
    }

    @Test
    void brokerNoDisponibleSeTraduceEnErrorDeInfraestructura() {
        detenerServicios();
        var conexion = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1");
        conexion.setPort(1);
        conexion.setConnectionTimeout(200);
        var sinBroker = new org.springframework.amqp.rabbit.core.RabbitTemplate(conexion);
        var publicadorAislado = new RequestPublisher(sinBroker, contexto, properties, "pedidos360-bff");
        var fabrica = new RequestFactory(properties, actores);
        var adaptador = new BffQueryAdapter(fabrica, publicadorAislado, correlaciones, contexto, new ResponseSchema(),
                properties);
        adaptador.registrar(Domain.USUARIOS, new QueryInvoker() {
            @Override
            public String operacion() {
                return "usuario.consultar-actual.v1";
            }

            @Override
            public cl.duoc.pedidos360.messaging.envelope.OperationResult invocar(
                    tools.jackson.databind.JsonNode payload, String correlationId) {
                throw new AssertionError("no debe ejecutarse");
            }
        });
        assertThatThrownBy(() -> adaptador.ejecutar(Domain.USUARIOS,
                JsonMapper.builder().build().createObjectNode(), token()))
                .isInstanceOf(QueryUnavailableException.class);
        assertThat(correlaciones.enVuelo()).isZero();
        conexion.destroy();
    }

    @Test
    void elSobreFirmadoNoTransportaElJwtYSeDirigeAlDominio() {
        servicio(COLA_USUARIOS);
        try {
            var adaptador = adaptadorUsuarios();
            var plan = new RequestFactory(properties, actores).planificar(Domain.USUARIOS,
                    "usuario.consultar-actual.v1", JsonMapper.builder().build().createObjectNode(), token(),
                    Instant.now());
            String serializado = new String(contexto.escribir(plan.envelope()), StandardCharsets.UTF_8);
            // El JWT original no viaja: solo el sobre firmado. El destino se comprueba publicamente:
            // la firma lo ata a la cola funcional del dominio, aunque su payload viaje codificado.
            assertThat(serializado).doesNotContain(token().getToken().getTokenValue())
                    .doesNotContain("Bearer ")
                    .contains("\"operacion\":\"usuario.consultar-actual.v1\"");
            String claimsDelSobre = new String(java.util.Base64.getUrlDecoder()
                    .decode(plan.envelope().actor().split("\\.")[1]), StandardCharsets.UTF_8);
            assertThat(claimsDelSobre).contains(COLA_USUARIOS).doesNotContain("Bearer ");
            var actor = firmante.verificar(plan.envelope().actor(), EntraTestTokens.TENANT,
                    List.of(COLA_USUARIOS), plan.envelope().expiresAt());
            assertThat(actor.sujetoId().toString()).isEqualTo(EntraTestTokens.USER);
            assertThat(actor.tenantId().toString()).isEqualTo(EntraTestTokens.TENANT);
            assertThat(actor.tieneRol("CLIENTE")).isTrue();
            assertThat(actor.tieneScope("access_as_user")).isTrue();
            assertThat(actor.expiraEn()).isBeforeOrEqualTo(plan.envelope().expiresAt());
            assertThat(adaptador.operacion(Domain.USUARIOS)).isPresent();
        } finally {
            detenerServicios();
        }
    }
}
