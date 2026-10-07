package cl.duoc.pedidos360.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.actor.SigningKey;
import cl.duoc.pedidos360.messaging.actor.SigningKeyProvider;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.relay.RequestPublisher;
import cl.duoc.pedidos360.messaging.relay.ResponseConsumer;

/**
 * Beans base de la mensajeria de consultas.
 *
 * <p>Se activa solo con {@code pedidos360.messaging.relay-mode=ACTIVE}. Con {@code DISABLED} no se
 * declara topologia, no se levantan listeners y HTTP sigue siendo el transporte predeterminado.
 *
 * <p>El modulo compartido no declara colas por si mismo: la declaracion de topologia es una
 * decision operativa de #69. Aqui solo viven los objetos de trabajo del BFF y de los servicios.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "relay-mode", havingValue = "ACTIVE")
@EnableConfigurationProperties(MessagingProperties.class)
public class QueryMessagingConfiguration {

    /** Nombre estable del bean de configuracion, usado por las expresiones de las colas. */
    public static final String PROPIEDADES_BEAN = "messagingProperties";

    /** Emisor del sobre: tenant de Entra compartido por BFF y servicios. */
    public static final String EMISOR_PROPERTY = "pedidos360.messaging.actor.emisor";

    /** Clave de firma vigente. Nunca un valor por defecto: sin clave no se firma ni se verifica. */
    public static final String CLAVE_ID_PROPERTY = "pedidos360.messaging.actor.clave-id";

    public static final String SECRETO_PROPERTY = "pedidos360.messaging.actor.secreto";

    /**
     * Alias con nombre estable de la configuracion central.
     *
     * <p>Se marca {@code primary} porque {@code @EnableConfigurationProperties} ya registra el mismo
     * tipo con un nombre generado: asi la inyeccion por tipo es inequivoca y las expresiones de cola
     * pueden usar {@code @messagingProperties}.
     */
    @Bean(name = PROPIEDADES_BEAN)
    @org.springframework.context.annotation.Primary
    MessagingProperties messagingProperties(MessagingProperties properties) {
        return properties;
    }

    @Bean
    @ConditionalOnMissingBean
    RequestEnvelopeContext requestEnvelopeContext() {
        return new RequestEnvelopeContext();
    }

    @Bean
    @ConditionalOnMissingBean
    ActorContextSigner actorContextSigner(Environment env) {
        Duration tolerancia = env.getProperty("pedidos360.messaging.actor.tolerancia-reloj", Duration.class,
                Duration.ofSeconds(5));
        return new ActorContextSigner(proveedorDeClave(env), Clock.systemUTC(), tolerancia);
    }

    /** Una sola clave simetrica vigente; la rotacion agrega claves historicas sin cambiar el contrato. */
    public static SigningKeyProvider proveedorDeClave(Environment env) {
        String claveId = env.getProperty(CLAVE_ID_PROPERTY, "");
        String secreto = env.getProperty(SECRETO_PROPERTY, "");
        if (claveId.isBlank() || secreto.isBlank()) throw new IllegalStateException(
                "relay-mode ACTIVE exige " + CLAVE_ID_PROPERTY + " y " + SECRETO_PROPERTY
                        + " (32 caracteres o mas) desde variable de entorno o gestor de secretos.");
        UUID id;
        try {
            id = UUID.fromString(claveId.trim());
        } catch (RuntimeException invalido) {
            throw new IllegalStateException("el identificador de clave debe ser un UUID.", invalido);
        }
        byte[] material = secreto.getBytes(StandardCharsets.UTF_8);
        if (material.length < 32) throw new IllegalStateException(
                "el material de firma debe tener al menos 32 bytes; nunca se versiona en Git.");
        SigningKey clave = new SigningKey(id, material);
        return new SigningKeyProvider() {
            @Override
            public Optional<SigningKey> claveParaFirmar() {
                return Optional.of(clave);
            }

            @Override
            public Optional<SigningKey> clavePorId(UUID keyId) {
                return clave.keyId().equals(keyId) ? Optional.of(clave) : Optional.empty();
            }
        };
    }

    /** Tenant esperado como emisor del sobre. */
    @Bean
    @ConditionalOnMissingBean(name = "emisorEsperado")
    String emisorEsperado(Environment env) {
        String emisor = env.getProperty(EMISOR_PROPERTY, "");
        if (emisor.isBlank()) throw new IllegalStateException(
                "relay-mode ACTIVE exige " + EMISOR_PROPERTY + " (tenant de Entra).");
        return emisor;
    }

    /**
     * Fabricas de listener para consultas: ACK manual, prefetch 1 y concurrencia 1.
     *
     * <p>La concurrencia 1 mantiene el orden y evita transferencias simultaneas del mismo mensaje
     * ante un fallo. Escalar consumidores exige coordinacion de plataforma.
     */
    @Bean
    @ConditionalOnMissingBean(name = "queryListenerFactory")
    SimpleRabbitListenerContainerFactory queryListenerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setMissingQueuesFatal(false);
        return factory;
    }

    /** El cuerpo viaja como bytes: el envelope lo interpreta el contrato, no un converter. */
    @Bean
    @ConditionalOnMissingBean
    SimpleMessageConverter queryMessageConverter() {
        var converter = new SimpleMessageConverter();
        converter.setAllowedListPatterns(java.util.List.of("*"));
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    RequestPublisher requestPublisher(org.springframework.amqp.rabbit.core.RabbitTemplate rabbit,
            RequestEnvelopeContext contexto, MessagingProperties properties, Environment env) {
        return new RequestPublisher(rabbit, contexto, properties,
                env.getProperty("spring.application.name", "pedidos360"));
    }

    /**
     * Consumidor de la cola tecnica de respuestas.
     *
     * <p>Se crea solo cuando el proceso declara el papel {@code BFF}. Los servicios consumidores no
     * declaran ese papel y quedan sin este listener, aunque compartan la misma configuracion.
     */
    @Bean
    @ConditionalOnProperty(prefix = "pedidos360.messaging", name = "role", havingValue = "BFF")
    ResponseConsumer responseConsumer(MessagingProperties properties,
            cl.duoc.pedidos360.messaging.relay.PendingCorrelationRegistry correlationRegistry) {
        return new ResponseConsumer(properties, correlationRegistry);
    }
}
