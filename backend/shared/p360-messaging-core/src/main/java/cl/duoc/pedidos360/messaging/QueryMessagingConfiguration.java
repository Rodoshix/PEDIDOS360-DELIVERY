package cl.duoc.pedidos360.messaging;

import java.util.Map;
import java.util.LinkedHashMap;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
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

    /** kid activo del BFF. Nunca un valor por defecto. SERVICE no necesita clave de emisión. */
    public static final String CLAVE_ID_PROPERTY = "pedidos360.messaging.actor.clave-id";

    public static final String PRIVADA_PROPERTY = "pedidos360.messaging.actor.private-jwk";
    public static final String PUBLICAS_PROPERTY = "pedidos360.messaging.actor.public-jwks";

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

    /** Carga exclusivamente configuración local confiable. Nunca URLs ni claves del mensaje. */
    public static SigningKeyProvider proveedorDeClave(Environment env) {
        String role = env.getProperty("pedidos360.messaging.role", "SERVICE");
        if (!role.equals("BFF") && !role.equals("SERVICE"))
            throw new IllegalStateException("rol de relay inválido");
        String privada = env.getProperty(PRIVADA_PROPERTY, "");
        String publicas = env.getProperty(PUBLICAS_PROPERTY, "");
        if (!role.equals("BFF") && !privada.isBlank())
            throw new IllegalStateException("un consumer no puede cargar clave privada de emisión");
        try {
            var json = tools.jackson.databind.json.JsonMapper.builder()
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            if (publicas.isBlank() || publicas.length() > 65536) throw new IllegalArgumentException();
            json.readTree(publicas);
            Map<UUID, SigningKey> confiables = new LinkedHashMap<>();
            var set = JWKSet.parse(publicas);
            if (set.getKeys().isEmpty() || set.getKeys().size() > 16) throw new IllegalArgumentException();
            for (var raw : set.getKeys()) {
                if (!(raw instanceof ECKey ec) || ec.isPrivate()) throw new IllegalArgumentException();
                var key = new SigningKey(UUID.fromString(ec.getKeyID()), ec);
                if (confiables.putIfAbsent(key.keyId(), key) != null) throw new IllegalArgumentException();
            }
            SigningKey emision = null;
            if (role.equals("BFF")) {
                if (privada.isBlank() || privada.length() > 8192) throw new IllegalArgumentException();
                json.readTree(privada);
                ECKey ec = ECKey.parse(privada);
                emision = new SigningKey(UUID.fromString(ec.getKeyID()), ec);
                if (!ec.isPrivate() || !emision.keyId().toString().equals(env.getProperty(CLAVE_ID_PROPERTY))
                        || !confiables.containsKey(emision.keyId())
                        || !ec.toPublicJWK().equals(confiables.get(emision.keyId()).material()))
                    throw new IllegalArgumentException();
                // Detecta privada incoherente con la pública antes de recibir tráfico.
                var probe = new com.nimbusds.jose.JWSObject(new com.nimbusds.jose.JWSHeader(
                        com.nimbusds.jose.JWSAlgorithm.ES256), new com.nimbusds.jose.Payload("configuration-check"));
                probe.sign(new com.nimbusds.jose.crypto.ECDSASigner(ec));
                if (!probe.verify(new com.nimbusds.jose.crypto.ECDSAVerifier(ec.toPublicJWK())))
                    throw new IllegalArgumentException();
            }
            final SigningKey firma = emision;
            final Map<UUID, SigningKey> verificadores = Map.copyOf(confiables);
            return new SigningKeyProvider() {
                public Optional<SigningKey> claveParaFirmar() { return Optional.ofNullable(firma); }
                public Optional<SigningKey> clavePorId(UUID id) { return Optional.ofNullable(verificadores.get(id)); }
            };
        } catch (Exception invalid) {
            // No adjuntar la causa: los parsers pueden incluir material criptográfico en sus mensajes.
            throw new IllegalStateException("relay ACTIVE exige claves EC P-256 locales válidas; privada solo BFF");
        }
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
