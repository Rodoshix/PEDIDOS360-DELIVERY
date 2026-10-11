package cl.duoc.pedidos360.pagos.messaging;

import cl.duoc.pedidos360.messaging.*;
import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.relay.*;
import cl.duoc.pedidos360.pagos.service.PagoService;
import java.time.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.amqp.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in wiring, no declarations. The existing publisher retains spring.rabbitmq settings. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "relay-mode", havingValue = "ACTIVE")
@EnableConfigurationProperties({MessagingProperties.class, org.springframework.boot.amqp.autoconfigure.RabbitProperties.class})
public class PagosQueryConfiguration {
    @Bean(name = "rabbitConnectionFactory") @Primary
    CachingConnectionFactory publisherConnectionFactory(
            @Qualifier("spring.rabbitmq-org.springframework.boot.amqp.autoconfigure.RabbitProperties")
            org.springframework.boot.amqp.autoconfigure.RabbitProperties p, ResourceLoader resources) throws Exception {
        return connection(p, resources);
    }

    @Bean(name = "rabbitTemplate") @Primary
    RabbitTemplate publisherTemplate(@Qualifier("rabbitConnectionFactory") ConnectionFactory cf,
            @Qualifier("spring.rabbitmq-org.springframework.boot.amqp.autoconfigure.RabbitProperties")
            org.springframework.boot.amqp.autoconfigure.RabbitProperties p) {
        var template = new RabbitTemplate();
        new RabbitTemplateConfigurer(p).configure(template, cf);
        return template;
    }

    @Bean CachingConnectionFactory queryConnectionFactory(
            Environment environment,
            @Qualifier("spring.rabbitmq-org.springframework.boot.amqp.autoconfigure.RabbitProperties")
            org.springframework.boot.amqp.autoconfigure.RabbitProperties publisher, ResourceLoader resources) throws Exception {
        var p = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("pagos.consultas.rabbitmq", org.springframework.boot.amqp.autoconfigure.RabbitProperties.class)
                .orElseThrow(() -> new IllegalStateException("conexión de consultas requerida"));
        if (p.getUsername().isBlank() || p.getUsername().equals("guest") || p.getPassword().isBlank()
                || p.getUsername().equals(publisher.getUsername()))
            throw new IllegalStateException("consultas requieren credenciales propias explícitas");
        var cf = connection(p, resources);
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cf.setPublisherReturns(true);
        return cf;
    }

    private static CachingConnectionFactory connection(org.springframework.boot.amqp.autoconfigure.RabbitProperties p,
            ResourceLoader resources) throws Exception {
        var bean = new RabbitConnectionFactoryBean();
        new RabbitConnectionFactoryBeanConfigurer(resources, p).configure(bean);
        bean.afterPropertiesSet();
        var cf = new CachingConnectionFactory(bean.getObject());
        new CachingConnectionFactoryConfigurer(p).configure(cf, p);
        return cf;
    }

    @Bean RabbitTemplate queryRabbitTemplate(@Qualifier("queryConnectionFactory") ConnectionFactory cf) {
        var template = new RabbitTemplate(cf);
        template.setMandatory(true);
        return template;
    }

    @Bean SimpleRabbitListenerContainerFactory queryListenerFactory(@Qualifier("queryConnectionFactory") ConnectionFactory cf) {
        var f = new SimpleRabbitListenerContainerFactory();
        f.setConnectionFactory(cf); f.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        f.setPrefetchCount(1); f.setConcurrentConsumers(1); f.setMaxConcurrentConsumers(1);
        f.setMissingQueuesFatal(false);
        f.setContainerCustomizer(container -> container.setAutoDeclare(false));
        return f;
    }

    @Bean ActorContextSigner queryActorVerifier(Environment env) {
        if (!"SERVICE".equals(env.getProperty("pedidos360.messaging.role", "SERVICE")))
            throw new IllegalStateException("Pagos solo admite role SERVICE");
        return new ActorContextSigner(QueryMessagingConfiguration.proveedorDeClave(env), Clock.systemUTC(),
                env.getProperty("pedidos360.messaging.actor.tolerancia-reloj", Duration.class, Duration.ofSeconds(5)));
    }

    @Bean IdentityProofVerifier pagosIdentityProofVerifier(Environment env) {
        String prefix = "pedidos360.messaging.identity-proof.";
        if (!env.getProperty(prefix + "enabled", Boolean.class, false)
                || !env.getProperty(prefix + "private-jwk", "").isBlank())
            throw new IllegalStateException("consultas Pagos requieren prueba habilitada y material exclusivamente público");
        return new IdentityProofVerifier(IdentityProofKeys.load(env.getProperty(prefix + "public-jwks"),
                env.getProperty(QueryMessagingConfiguration.PUBLICAS_PROPERTY)), Clock.systemUTC(),
                env.getProperty(prefix + "clock-margin", Duration.class, Duration.ofMillis(250)));
    }

    @Bean QueryTopology queryTopology(MessagingProperties p) {
        if (!p.isNombresValidos() || !p.isPlantillasValidas() || !p.isTiemposValidos() || !p.isLimitesValidos()
                || p.deadline().compareTo(Duration.ofSeconds(5)) > 0 || p.declareTopology())
            throw new IllegalStateException("configuración de consultas inválida; topología solo plataforma");
        return QueryTopology.of(p, Domain.PAGOS);
    }
    @Bean RequestEnvelopeContext queryEnvelopeContext() { return new RequestEnvelopeContext(); }
    @Bean QueryReplyPublisher queryReplyPublisher(@Qualifier("queryRabbitTemplate") RabbitTemplate rabbit,
            RequestEnvelopeContext context, MessagingProperties p) { return new QueryReplyPublisher(rabbit, context, p); }
    @Bean HandoffPublisher queryHandoffPublisher(@Qualifier("queryRabbitTemplate") RabbitTemplate rabbit,
            RequestEnvelopeContext context, MessagingProperties p, QueryTopology topology) {
        return new HandoffPublisher(rabbit, context, p, topology);
    }
    @Bean QueryFailureHandler queryFailureHandler(HandoffPublisher handoff, QueryReplyPublisher replies,
            MessagingProperties p, QueryTopology topology) { return new QueryFailureHandler(handoff, replies, p, topology); }
    @Bean(destroyMethod = "close") QueryConsumerRecovery queryRecovery(RabbitListenerEndpointRegistry registry, MessagingProperties p) {
        return new QueryConsumerRecovery(registry, p.recoveryBackoff());
    }
    @Bean PagosQueryProcessor pagosQueryProcessor(PagoService pagos, IdentityProofVerifier verifier, JsonMapper json,
            JdbcTemplate jdbc, PlatformTransactionManager manager) { return new PagosQueryProcessor(pagos, verifier, json, jdbc, manager); }
    @Bean QueryConsumer queryConsumer(RequestEnvelopeContext context, ActorContextSigner actor, PagosQueryProcessor processor,
            QueryReplyPublisher replies, QueryFailureHandler failures, QueryTopology topology,
            QueryConsumerRecovery recovery, MessagingProperties p, Environment env) {
        String tenant = env.getProperty(QueryMessagingConfiguration.EMISOR_PROPERTY, "");
        try { if (!java.util.UUID.fromString(tenant).toString().equals(tenant)) throw new IllegalArgumentException(); }
        catch (RuntimeException invalid) { throw new IllegalStateException("tenant del consumer requerido"); }
        // Processor validates the proof before replying 403 for an authenticated authorization denial.
        return new QueryConsumer(context, actor, processor, replies, failures, topology, tenant,
                null, recovery, p.maxBodyBytes(), Clock.systemUTC(), true);
    }
}
