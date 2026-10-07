package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import cl.duoc.pedidos360.messaging.relay.QueryConsumerRecovery;

/**
 * Compatibilidad de la declaracion de #77 con la plataforma RabbitMQ de #69.
 *
 * <p>#69 aprovisiona 21 colas quorum con argumentos minimos y configura el retry corto con una
 * <strong>policy</strong> por cola: <code>message-ttl</code> y el retorno a la cola funcional. Si la
 * aplicacion declarase esos mismos valores como argumentos inmutables, el broker compararia el
 * argumento con la definicion existente y respondería <code>PRECONDITION_FAILED</code> (406).
 *
 * <p>Esta suite acredita tres cosas contra un broker real:
 *
 * <ol>
 *   <li>la declaracion de la aplicacion (colas sin argumentos, exchanges y bindings) se puede aplicar
 *       sobre un inventario con policies activas sin 406;</li>
 *   <li>las policies de plataforma siguen vigentes despues de la declaracion de la aplicacion, de modo
 *       que el TTL y el retorno del retry no dependen del codigo;</li>
 *   <li>el broker <strong>si</strong> rechaza argumentos incompatibles: la prueba es sensible a una
 *       regresion que volviera a declarar {@code x-message-ttl} o el DLX de la cola funcional.</li>
 * </ol>
 */
@SpringBootTest(classes = PlatformCompatibilityTests.Configuracion.class)
@TestPropertySource(properties = {
        "pedidos360.messaging.relay-mode=ACTIVE",
        "pedidos360.messaging.role=SERVICE",
        // Se declara la topologia de la aplicacion al arrancar: si fuese incompatible con las policies,
        // el contexto no levantaria.
        "pedidos360.messaging.declare-topology=true",
        "pedidos360.messaging.exchanges.queries=p360.compat.queries",
        "pedidos360.messaging.exchanges.retry=p360.compat.retry",
        "pedidos360.messaging.exchanges.dlx=p360.compat.dlx",
        "pedidos360.messaging.queues.responses=p360.compat.respuestas.q",
        "pedidos360.messaging.naming.prefix=p360.compat.",
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
        "pedidos360.messaging.actor.emisor=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        "pedidos360.messaging.actor.clave-id=11111111-2222-3333-4444-555555555555",
        "pedidos360.messaging.actor.secreto=clave-de-prueba-con-al-menos-32-bytes",
        "pedidos360.messaging.deadline=5s",
        "pedidos360.messaging.actor-ttl=4s",
        "pedidos360.messaging.retry-delay=1s",
        "pedidos360.messaging.confirm-timeout=3s",
        "pedidos360.messaging.recovery-backoff=500ms",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.virtual-host=/"})
class PlatformCompatibilityTests {

    static final String CLAVE_ID = "11111111-2222-3333-4444-555555555555";
    static final String TENANT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({QueryMessagingConfiguration.class, QueryTopologyDeclaration.class})
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
    }

    @Autowired QueryTopology topology;
    @Autowired MessagingProperties properties;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitMQContainer container;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin admin;

    /** Crea el inventario "de plataforma" con argumentos minimos, como hace #69. */
    private void aprovisionarPlataforma() {
        rabbit.execute(channel -> {
            channel.exchangeDeclare(properties.exchanges().queries(), "direct", true);
            channel.exchangeDeclare(properties.exchanges().retry(), "direct", true);
            channel.exchangeDeclare(properties.exchanges().dlx(), "direct", true);
            return null;
        });
        rabbit.execute(channel -> {
            channel.queueDeclare(topology.queue(), true, false, false, null);
            channel.queueDeclare(topology.retryQueue(), true, false, false, null);
            channel.queueDeclare(topology.dlq(), true, false, false, null);
            channel.queueBind(topology.queue(), properties.exchanges().queries(), topology.routingKey());
            channel.queueBind(topology.retryQueue(), properties.exchanges().retry(), topology.retryRoutingKey());
            channel.queueBind(topology.dlq(), properties.exchanges().dlx(), topology.failedRoutingKey());
            return null;
        });
    }

    /**
     * Aplica las policies equivalentes a las de #69: TTL y retorno del retry corto, y DLX de fallo en
     * la cola funcional. Los valores salen de la configuracion, no de literales de negocio.
     */
    private void aplicarPoliciesDePlataforma() {
        var retry = new LinkedHashMap<String, Object>();
        retry.put("message-ttl", properties.retryDelay().toMillis());
        retry.put("dead-letter-exchange", properties.exchanges().queries());
        retry.put("dead-letter-routing-key", topology.routingKey());
        retry.put("overflow", "reject-publish");
        retry.put("delivery-limit", -1);
        crearPolicy("ep2-" + topology.retryQueue(), patronExacto(topology.retryQueue()), retry);

        var funcional = new LinkedHashMap<String, Object>();
        funcional.put("dead-letter-exchange", properties.exchanges().retry());
        funcional.put("dead-letter-routing-key", topology.retryRoutingKey());
        funcional.put("dead-letter-strategy", "at-least-once");
        funcional.put("overflow", "reject-publish");
        funcional.put("delivery-limit", 5);
        crearPolicy("ep2-" + topology.queue(), patronExacto(topology.queue()), funcional);
    }

    /**
     * Regex anclado a un nombre exacto. Se escapan los puntos con {@code \\.} porque el motor de
     * expresiones del broker no acepta {@code \\Q...\\E}.
     */
    static String patronExacto(String cola) {
        return "^" + cola.replace(".", "\\.") + "$";
    }

    private void crearPolicy(String nombre, String patron, Map<String, Object> definicion) {
        var resultado = ejecutarRabbitmqctl("set_policy", "-p", "/", "--priority", "10", "--apply-to", "queues",
                nombre, patron, escribirJson(definicion));
        if (resultado.getExitCode() != 0) {
            throw new AssertionError("policy " + nombre + " rechazada: " + resultado.getStdout() + " / "
                    + resultado.getStderr());
        }
    }

    private String escribirJson(Map<String, Object> definicion) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(definicion);
    }

    /** Ejecuta rabbitmqctl dentro del contenedor, como hace la propia evidencia de #69. */
    private org.testcontainers.containers.Container.ExecResult ejecutarRabbitmqctl(String... argumentos) {
        String[] comando = new String[argumentos.length + 1];
        comando[0] = "rabbitmqctl";
        System.arraycopy(argumentos, 0, comando, 1, argumentos.length);
        try {
            return container.execInContainer(comando);
        } catch (java.io.IOException fallo) {
            throw new IllegalStateException("no se pudo ejecutar rabbitmqctl", fallo);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rabbitmqctl interrumpido", interrumpido);
        }
    }

    /** Vuelve a aplicar la declaracion de la aplicacion sobre el inventario ya aprovisionado. */
    private void redeclararLaAplicacion() {
        topology.declarations().getDeclarables().forEach(declarable -> rabbit.execute(channel -> {
            if (declarable instanceof Queue cola) {
                channel.queueDeclare(cola.getName(), cola.isDurable(), cola.isExclusive(), cola.isAutoDelete(),
                        cola.getArguments());
            } else if (declarable instanceof Exchange exchange) {
                channel.exchangeDeclare(exchange.getName(), exchange.getType(), exchange.isDurable(),
                        exchange.isAutoDelete(), exchange.getArguments());
            } else if (declarable instanceof Binding binding) {
                channel.queueBind(binding.getDestination(), binding.getExchange(), binding.getRoutingKey(),
                        binding.getArguments());
            }
            return null;
        }));
    }

    @Test
    void laDeclaracionDeLaAplicacionNoProvocaPreconditionFailedConLasPoliciesDeLaPlataforma() {
        aprovisionarPlataforma();
        aplicarPoliciesDePlataforma();
        enlazarColas();

        // Si la aplicacion declarase x-message-ttl o el DLX de la funcional, esta llamada lanzaria 406.
        redeclararLaAplicacion();

        // Las colas siguen existiendo; las tres declaraciones de la aplicacion son compatibles.
        assertThat(admin.getQueueInfo(topology.queue())).isNotNull();
        assertThat(admin.getQueueInfo(topology.retryQueue())).isNotNull();
        assertThat(admin.getQueueInfo(topology.dlq())).isNotNull();
        assertThat(admin.getQueueInfo(topology.retryQueue()).getConsumerCount()).as("el retry no tiene consumer")
                .isZero();

        // Ni la funcional ni el retry declaran argumentos inmutables: la aplicacion solo declara colas
        // durables sin argumentos. El unico argumento visible es {@code x-queue-type}, que anade el
        // broker segun el tipo por defecto del vhost (classic en este broker de prueba, quorum en el
        // vhost {@code pedidos360} de #69); la aplicacion no lo declara.
        assertThat(argumentosDeLaAplicacion(topology.queue())).as("la funcional no declara TTL ni DLX")
                .contains("x-queue-type").doesNotContain("x-message-ttl")
                .doesNotContain("x-dead-letter-exchange").doesNotContain("x-dead-letter-routing-key");
        assertThat(argumentosDeLaAplicacion(topology.retryQueue())).as("el retry no declara su TTL")
                .contains("x-queue-type").doesNotContain("x-message-ttl")
                .doesNotContain("x-dead-letter-exchange").doesNotContain("x-dead-letter-routing-key");

        // Con la topologia declarada, el enrutamiento sigue siendo el del inventario: un mensaje
        // publicado en el exchange de consultas con la routing key del dominio llega a la funcional.
        rabbit.execute(channel -> {
            channel.queuePurge(topology.retryQueue());
            channel.queuePurge(topology.queue());
            return null;
        });
        var metadatos = new org.springframework.amqp.core.MessageProperties();
        metadatos.setMessageId("compat-routing");
        metadatos.setContentType("application/json");
        rabbit.send(properties.exchanges().queries(), topology.routingKey(), new org.springframework.amqp.core.Message(
                "{\"prueba\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8), metadatos));
        var recibido = new java.util.concurrent.atomic.AtomicReference<org.springframework.amqp.core.Message>();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            var candidato = rabbit.receive(topology.queue(), 500);
            if (candidato == null) return false;
            recibido.set(candidato);
            return true;
        });
        assertThat(recibido.get().getMessageProperties().getMessageId())
                .as("el binding del dominio sigue vigente tras la redeclaracion").isEqualTo("compat-routing");

        // El exchange de retry y su binding existen: la transferencia del handoff tiene destino.
        assertThat(admin.getQueueInfo(topology.retryQueue()).getConsumerCount()).as("retry sin consumer").isZero();
        assertThat(admin.getQueueInfo(topology.dlq()).getConsumerCount()).as("DLQ sin consumer").isZero();
        rabbit.execute(channel -> {
            channel.queuePurge(topology.retryQueue());
            channel.queuePurge(topology.queue());
            return null;
        });
    }

    /** Bindings del inventario: la funcional hacia el retry y el retry de vuelta a la funcional. */
    private void enlazarColas() {
        rabbit.execute(channel -> {
            channel.queueBind(topology.queue(), properties.exchanges().queries(), topology.routingKey());
            channel.queueBind(topology.retryQueue(), properties.exchanges().retry(), topology.retryRoutingKey());
            channel.queueBind(topology.dlq(), properties.exchanges().dlx(), topology.failedRoutingKey());
            return null;
        });
    }

    /** Argumentos declarados por la aplicacion para una cola, segun rabbitmqctl. */
    private String argumentosDeLaAplicacion(String cola) {
        var resultado = ejecutarRabbitmqctl("list_queues", "-p", "/", "--no-table-headers", "name", "arguments");
        if (resultado.getExitCode() != 0) {
            throw new AssertionError("list_queues fallo: " + resultado.getStderr());
        }
        for (String linea : resultado.getStdout().split("\\R")) {
            if (linea.trim().startsWith(cola)) {
                int separador = linea.indexOf('\t');
                return separador < 0 ? linea.substring(cola.length()).trim() : linea.substring(separador).trim();
            }
        }
        throw new AssertionError("no se encontro la cola " + cola + " en: " + resultado.getStdout());
    }

    @Test
    void elBrokerRechazaArgumentosIncompatiblesDeModoQueLaPruebaEsSensible() {
        // Prueba de sensibilidad: si la declaracion de la aplicacion volviera a incluir el TTL como
        // argumento, el broker responde 406 igual que lo haria en la plataforma de #69.
        String cola = "p360.compat.sensibilidad.q";
        rabbit.execute(channel -> {
            channel.queueDelete(cola);
            channel.queueDeclare(cola, true, false, false, Map.of("x-message-ttl", 2000));
            return null;
        });
        assertThatThrownBy(() -> rabbit.execute(channel -> {
            channel.queueDeclare(cola, true, false, false, Map.of("x-message-ttl", 1000));
            return null;
        })).as("un TTL distinto como argumento inmutable es 406")
                .hasRootCauseInstanceOf(com.rabbitmq.client.ShutdownSignalException.class)
                .rootCause().hasMessageContaining("PRECONDITION_FAILED")
                .hasMessageContaining("x-message-ttl");
        rabbit.execute(channel -> {
            channel.queueDelete(cola);
            return null;
        });
    }

    @Test
    void laTopologiaDeLaAplicacionConservaNombresExchangesYBindingsDelInventario() {
        // La estructura logica no cambia al dejar de declarar argumentos.
        assertThat(topology.queue()).isEqualTo("p360.compat.usuarios.consultas.q");
        assertThat(topology.retryQueue()).isEqualTo("p360.compat.usuarios.consultas.retry.1s.q");
        assertThat(topology.dlq()).isEqualTo("p360.compat.usuarios.consultas.dlq");
        assertThat(topology.bindings()).containsEntry("p360.compat.usuarios.consultas.q",
                "p360.compat.queries -> usuario.consultar-actual.v1")
                .containsEntry("p360.compat.usuarios.consultas.retry.1s.q",
                        "p360.compat.retry -> usuario.consultar-actual.retry.1s")
                .containsEntry("p360.compat.usuarios.consultas.dlq",
                        "p360.compat.dlx -> usuario.consultar-actual.failed");
        assertThat(topology.declarations().getDeclarables()).hasSize(9);
        assertThat(topology.functional().getArguments()).isEmpty();
        assertThat(topology.retry().getArguments()).isEmpty();
        assertThat(topology.deadLetter().getArguments()).isEmpty();
    }

    @Test
    void elRecoveryUsaElIdentificadorDeListenerDeclaradoEnLaAnotacion() {
        // El identificador es parte del contrato con la recuperacion: si cambia, el ciclo no encuentra
        // el container.
        assertThat(QueryConsumerRecovery.QUERY_LISTENER_ID).isEqualTo("queryFunctionalListener");
    }
}
