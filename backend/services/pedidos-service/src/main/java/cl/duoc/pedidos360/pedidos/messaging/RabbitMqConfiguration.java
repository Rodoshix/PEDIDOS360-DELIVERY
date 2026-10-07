package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pedidos360.messaging",name="coordination-mode",havingValue="RABBITMQ")
public class RabbitMqConfiguration {
    @Bean DirectExchange pedidosCommandsExchange(RabbitProperties p) { return new DirectExchange(p.exchanges().commands(),true,false); }
    @Bean Queue pedidoConfirmacionQueue(RabbitProperties p) { return QueueBuilder.durable(p.queues().confirmacion()).build(); }
    @Bean Binding pedidoConfirmacionBinding(Queue pedidoConfirmacionQueue,DirectExchange pedidosCommandsExchange,RabbitProperties p) {
        return BindingBuilder.bind(pedidoConfirmacionQueue).to(pedidosCommandsExchange).with(p.routingKeys().confirmar());
    }
    @Bean SimpleRabbitListenerContainerFactory confirmacionListenerFactory(ConnectionFactory cf) {
        var factory=new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf); factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1); factory.setConcurrentConsumers(1); factory.setMaxConcurrentConsumers(1);
        return factory;
    }
    @Bean @ConditionalOnMissingBean(PedidoConfirmacionFailureHandler.class)
    PedidoConfirmacionFailureHandler pendingReliabilityPolicy() {
        // Deliberately retain unacked; no implicit framework requeue loop or competing policy.
        // Prefetch=1 stops this consumer until #68 settles failures or channel is restarted.
        return (message,channel,failure) -> LoggerFactory.getLogger(PedidoConfirmacionFailureHandler.class)
            .error("Confirmation messageId={} retained UNACKED: failure={} reason={}",
                safeMessageId(message),failure.getClass().getSimpleName(),
                failure instanceof ConfirmacionDefinitivaException d?d.reason():"UNEXPECTED");
    }
    private static String safeMessageId(Message message) {
        try { return java.util.UUID.fromString(message.getMessageProperties().getMessageId()).toString(); }
        catch (RuntimeException invalid) { return "INVALID"; }
    }
}
