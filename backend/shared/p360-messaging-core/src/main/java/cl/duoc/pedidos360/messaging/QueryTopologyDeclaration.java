package cl.duoc.pedidos360.messaging;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaracion de la topologia de consultas cuando el despliegue la habilita.
 *
 * <p>Requiere que el dominio haya registrado su {@link QueryTopology}. La declaracion de la cola
 * tecnica de respuestas la aporta {@code BffResponseQueueConfiguration} en el BFF.
 *
 * <p>La declaracion efectiva de la plataforma completa (argumentos por cola, policies y permisos)
 * queda coordinada con #69. Esta clase solo declara lo que el proceso realmente usa.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "declare-topology", havingValue = "true")
public class QueryTopologyDeclaration {

    /** Colas, exchanges y bindings del dominio consumidor. */
    @Bean
    Declarables domainTopologyDeclarations(QueryTopology queryTopology) {
        return queryTopology.declarations();
    }

    /**
     * Cola tecnica de respuestas compartida.
     *
     * <p>Durable, sin retry ni DLQ propia: la aplicacion retira las respuestas huerfanas o tardias
     * con ACK inmediato y la cola conserva el mensaje hasta ese ACK.
     */
    @Bean
    Queue responseQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.queues().responses()).build();
    }
}
