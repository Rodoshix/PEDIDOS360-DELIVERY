package cl.duoc.pedidos360.pedidos.messaging;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
/** Integrante 3 supplies retry/confirmed transfer/DLQ here. Must settle delivery explicitly. */
@FunctionalInterface
public interface PedidoConfirmacionFailureHandler {
    void handle(Message message,Channel channel,Exception failure) throws java.io.IOException;
}
