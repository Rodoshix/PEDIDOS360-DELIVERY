package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(prefix="pedidos360.messaging",name="coordination-mode",havingValue="RABBITMQ")
public class PedidoConfirmacionConsumer {
    private final PedidoConfirmacionProcessor processor;
    private final PedidoConfirmacionFailureHandler failures;
    public PedidoConfirmacionConsumer(PedidoConfirmacionProcessor processor,PedidoConfirmacionFailureHandler failures) {
        this.processor=processor; this.failures=failures;
    }
    @RabbitListener(queues="${pedidos360.messaging.queues.confirmacion}",containerFactory="confirmacionListenerFactory")
    public void consume(Message message,Channel channel) throws java.io.IOException {
        java.util.UUID id;
        try { id=processor.process(message); }
        catch (Exception failure) { failures.handle(message,channel,failure); return; }
        try { channel.basicAck(message.getMessageProperties().getDeliveryTag(),false); }
        catch (java.io.IOException uncertainAck) {
            failures.handle(message,channel,uncertainAck);
            return;
        }
        LoggerFactory.getLogger(getClass()).info("Confirmation messageId={} ACK",id);
    }
}
