package cl.duoc.pedidos360.messaging.relay;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import com.rabbitmq.client.Channel;

import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.envelope.ResponseSchema;

/**
 * Consumidor de la cola tecnica de respuestas del BFF.
 *
 * <p>Resuelve la correlacion y confirma siempre la respuesta, incluso cuando no hay espera asociada:
 * una respuesta tardia o duplicada no puede quedar acumulada en la cola. Las correlaciones
 * desconocidas se registran como diagnostico y se descartan.
 *
 * <p>La cola compartida conserva el mensaje hasta este ACK. Esta solucion contempla una instancia
 * BFF; escalar exige resolver la propiedad de las correlaciones antes de habilitar consumidores
 * competidores.
 */
public class ResponseConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResponseConsumer.class);

    private final PendingCorrelationRegistry correlaciones;
    private final ResponseSchema esquema;
    private final String colaRespuestas;
    private final int maxBodyBytes;

    public ResponseConsumer(MessagingProperties properties, PendingCorrelationRegistry correlaciones) {
        this.correlaciones = correlaciones;
        this.esquema = new ResponseSchema();
        this.colaRespuestas = properties.queues().responses();
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @RabbitListener(queues = "#{@messagingProperties.queues().responses()}", containerFactory = "queryListenerFactory")
    public void consumir(Message message, Channel channel) throws IOException {
        String correlationId = message.getMessageProperties().getCorrelationId();
        try {
            if (!esquema.estructuraValida(message.getBody(), maxBodyBytes))
                log.warn("Respuesta descartada en {} correlationId={}: estructura fuera del contrato", colaRespuestas,
                        correlationId);
            else if (!correlaciones.completar(correlationId, message.getBody()))
                log.warn("Respuesta descartada en {} correlationId={}: desconocida, tardia o duplicada",
                        colaRespuestas, correlationId);
        } catch (RuntimeException inesperado) {
            log.error("Respuesta descartada en {} correlationId={}: {}", colaRespuestas, correlationId,
                    inesperado.getMessage());
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
