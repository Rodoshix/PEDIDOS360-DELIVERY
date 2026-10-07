package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ConfirmacionReliabilityProperties.class)
@ConditionalOnProperty(prefix="pedidos360.messaging",name="coordination-mode",havingValue="RABBITMQ")
public class RabbitMqConfiguration {
    @Bean DirectExchange pedidosCommandsExchange(RabbitProperties p) { return new DirectExchange(p.exchanges().commands(),true,false); }
    @Bean Queue pedidoConfirmacionQueue(RabbitProperties p) { return QueueBuilder.durable(p.queues().confirmacion()).build(); }
    @Bean Binding pedidoConfirmacionBinding(Queue pedidoConfirmacionQueue,DirectExchange pedidosCommandsExchange,RabbitProperties p) {
        return BindingBuilder.bind(pedidoConfirmacionQueue).to(pedidosCommandsExchange).with(p.routingKeys().confirmar());
    }
    @Bean SimpleRabbitListenerContainerFactory confirmacionListenerFactory(ConnectionFactory cf) {
        var factory=new SimpleRabbitListenerContainerFactory();
        factory.setContainerCustomizer(c -> c.setMessagePropertiesConverter(new ConfirmacionMessagePropertiesConverter()));
        factory.setConnectionFactory(cf); factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setForceStop(true); factory.setRecoveryInterval(5000L); factory.setPrefetchCount(1); factory.setConcurrentConsumers(1); factory.setMaxConcurrentConsumers(1);
        return factory;
    }
    @Bean ConfirmacionErrorClassifier confirmacionErrorClassifier() { return new ConfirmacionErrorClassifier(); }
    @Bean ConfirmacionRetryPublisher confirmacionRetryPublisher(org.springframework.amqp.rabbit.core.RabbitTemplate template,RabbitProperties p,ConfirmacionReliabilityProperties r) { return new ConfirmacionRetryPublisher(template,p,r); }
    @Bean ConfirmacionFailureReporter confirmacionFailureReporter(tools.jackson.databind.json.JsonMapper json) { return new ConfirmacionFailureReporter(json); }
    @Bean(destroyMethod="close") ConfirmacionConsumerRecovery confirmacionConsumerRecovery(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry registry,ConfirmacionReliabilityProperties r) { return new ConfirmacionConsumerRecovery(registry,r); }
    @Bean PedidoConfirmacionFailureHandler pedidoConfirmacionFailureHandler(ConfirmacionErrorClassifier c,ConfirmacionRetryPublisher p,ConfirmacionConsumerRecovery recovery,ConfirmacionFailureReporter reporter,ConfirmacionReliabilityProperties r) { return new DefaultPedidoConfirmacionFailureHandler(c,p,recovery,reporter,r); }
    @Bean Declarables confirmacionReliabilityTopology(RabbitProperties p,ConfirmacionReliabilityProperties r) {
        var retry=new DirectExchange(r.retryExchange(),true,false); var dlx=new DirectExchange(r.dlx(),true,false);
        var q5=retryQueue(r.retry5Queue(),5000,p); var q30=retryQueue(r.retry30Queue(),30000,p); var q120=retryQueue(r.retry120Queue(),120000,p);
        // Queue type and DLQ delivery-limit belong to platform #69, not this declaration.
        var dlq=QueueBuilder.durable(r.dlq()).build();
        return new Declarables(retry,dlx,q5,q30,q120,dlq,
            BindingBuilder.bind(q5).to(retry).with(r.retry5Key()),BindingBuilder.bind(q30).to(retry).with(r.retry30Key()),
            BindingBuilder.bind(q120).to(retry).with(r.retry120Key()),BindingBuilder.bind(dlq).to(dlx).with(r.failedRoutingKey()));
    }
    private Queue retryQueue(String name,int ttl,RabbitProperties p) { return QueueBuilder.durable(name).ttl(ttl).deadLetterExchange(p.exchanges().commands()).deadLetterRoutingKey(p.routingKeys().confirmar()).build(); }
}
