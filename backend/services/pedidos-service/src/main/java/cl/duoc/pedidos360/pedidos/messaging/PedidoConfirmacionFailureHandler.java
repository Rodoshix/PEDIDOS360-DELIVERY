package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
/** Failure policy boundary: explicit settlement or controlled recovery; never silent retention. */
@FunctionalInterface
public interface PedidoConfirmacionFailureHandler {
    void handle(Message message,Channel channel,Exception failure) throws java.io.IOException;
}
