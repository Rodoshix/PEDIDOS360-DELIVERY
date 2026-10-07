package cl.duoc.pedidos360.messaging;

import java.util.List;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.relay.HandoffPublisher;
import cl.duoc.pedidos360.messaging.relay.HandoffRecovery;
import cl.duoc.pedidos360.messaging.relay.QueryConsumer;
import cl.duoc.pedidos360.messaging.relay.QueryConsumerRecovery;
import cl.duoc.pedidos360.messaging.relay.QueryFailureHandler;
import cl.duoc.pedidos360.messaging.relay.QueryPrecheck;
import cl.duoc.pedidos360.messaging.relay.QueryProcessor;
import cl.duoc.pedidos360.messaging.relay.QueryReplyPublisher;

/**
 * Beans del lado consumidor: retry corto, DLQ y consumidor base de la cola funcional.
 *
 * <p>No se activa en el BFF porque exige un {@code QueryTopology}, que solo declara un servicio
 * consumidor de consultas. La base no implementa ninguna operacion de dominio: el servicio aporta
 * el {@link QueryProcessor}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "relay-mode", havingValue = "ACTIVE")
public class QueryConsumerConfiguration {

    /** Rol requerido por la operacion, segun el contrato HTTP vigente. */
    public static final String ROLES_PROPERTY = "pedidos360.messaging.actor.roles-permitidos";

    @Bean
    @ConditionalOnMissingBean
    QueryReplyPublisher queryReplyPublisher(RabbitTemplate rabbit, RequestEnvelopeContext contexto,
            MessagingProperties properties) {
        return new QueryReplyPublisher(rabbit, contexto, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    HandoffPublisher handoffPublisher(RabbitTemplate rabbit, RequestEnvelopeContext contexto,
            MessagingProperties properties, QueryTopology queryTopology) {
        return new HandoffPublisher(rabbit, contexto, properties, queryTopology);
    }

    @Bean
    @ConditionalOnMissingBean
    QueryFailureHandler queryFailureHandler(HandoffPublisher handoff, QueryReplyPublisher respuestas,
            MessagingProperties properties, QueryTopology queryTopology) {
        return new QueryFailureHandler(handoff, respuestas, properties, queryTopology);
    }

    /**
     * Recuperacion activa del consumidor de consultas.
     *
     * <p>Es la pieza que cumple el criterio "handoff fallido conserva el original con recuperacion":
     * cierra el canal para que el broker reentregue el mensaje sin confirmar y reinicia el listener
     * con backoff, en un executor propio y sin bloquear el hilo del listener. Un servicio puede
     * sustituirla registrando su propio bean {@link HandoffRecovery} bajo otro nombre: si reutiliza el
     * nombre de este metodo, Spring rechaza la definicion duplicada.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    HandoffRecovery handoffRecovery(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry registry,
            MessagingProperties properties) {
        return new QueryConsumerRecovery(registry, properties.recoveryBackoff());
    }

    /**
     * Precheck por defecto: exige al menos uno de los roles configurados.
     *
     * <p>Cada servicio puede sustituirlo registrando su propio bean.
     */
    @Bean
    @ConditionalOnMissingBean
    QueryPrecheck queryPrecheck(org.springframework.core.env.Environment env) {
        String declarados = env.getProperty(ROLES_PROPERTY, "CLIENTE,ADMIN");
        var roles = java.util.Arrays.stream(declarados.split(",")).map(String::trim).filter(r -> !r.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return QueryPrecheck.exigeRol(roles);
    }

    /**
     * Consumidor base de la cola funcional del dominio.
     *
     * <p>Requiere topologia y procesador del dominio. Si el procesador aun no existe, no se levanta
     * listener alguno: los issue #78 a #81 agregan su procesador sin tocar la base.
     */
    @Bean
    @ConditionalOnMissingBean
    QueryConsumer queryConsumer(ObjectProvider<QueryTopology> topologia, ObjectProvider<QueryProcessor> procesador,
            RequestEnvelopeContext contexto, ActorContextSigner actor, QueryReplyPublisher respuestas,
            QueryFailureHandler fallos, String emisorEsperado, ObjectProvider<QueryPrecheck> precheck,
            HandoffRecovery recuperacion, MessagingProperties properties) {
        QueryTopology topology = topologia.getIfAvailable();
        QueryProcessor processor = procesador.getIfAvailable();
        if (topology == null || processor == null) return null;
        return new QueryConsumer(contexto, actor, processor, respuestas, fallos, topology, emisorEsperado,
                precheck.getIfAvailable(), recuperacion, properties.maxBodyBytes());
    }
}
