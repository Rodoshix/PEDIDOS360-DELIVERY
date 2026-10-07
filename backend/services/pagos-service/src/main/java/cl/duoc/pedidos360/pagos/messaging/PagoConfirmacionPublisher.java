package cl.duoc.pedidos360.pagos.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="pedidos360.messaging",name="coordination-mode",havingValue="RABBITMQ")
public class PagoConfirmacionPublisher {
    private final RabbitTemplate rabbit;
    private final RabbitProperties properties;
    public PagoConfirmacionPublisher(RabbitTemplate rabbit, RabbitProperties properties) {
        this.rabbit=rabbit; this.properties=properties;
        rabbit.setMandatory(true);
    }
    /** Success means broker acceptance, never domain confirmation. */
    public void publish(OutboxStore.Claim claim) throws Exception {
        MessageProperties metadata=new MessageProperties();
        metadata.setMessageId(claim.messageId().toString());
        metadata.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadata.setContentEncoding("UTF-8");
        metadata.setAppId("pagos-service");
        metadata.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        metadata.setHeader("retry-count",0);
        // Correlation identifies this attempt; AMQP message_id identifies the logical command.
        var correlation=new CorrelationData(claim.messageId()+":"+UUID.randomUUID());
        rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),
            new Message(claim.payload().getBytes(StandardCharsets.UTF_8),metadata),correlation);
        var confirm=correlation.getFuture().get(properties.confirmTimeout().toMillis(),TimeUnit.MILLISECONDS);
        if (!confirm.ack()) throw new PublicationRejectedException(PublicationRejectedException.Reason.BROKER_NACK);
        if (correlation.getReturned()!=null) throw new PublicationRejectedException(PublicationRejectedException.Reason.UNROUTABLE);
    }
}
