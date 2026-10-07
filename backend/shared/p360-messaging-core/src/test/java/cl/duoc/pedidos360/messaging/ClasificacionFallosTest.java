package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import cl.duoc.pedidos360.messaging.envelope.QueryBusinessException;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.relay.HandoffPublisher;
import cl.duoc.pedidos360.messaging.relay.QueryFailureHandler;
import cl.duoc.pedidos360.messaging.relay.QueryReplyPublisher;

/** Comprueba la clasificacion de fallos sin broker: no depende de RabbitMQ. */
class ClasificacionFallosTest {

    @Test
    void unErrorDeNegocioEsperadoNoSeReintentaYSeResponde() {
        var propiedades = MessagingContractTests.propiedades();
        var topologia = QueryTopology.of(propiedades, Domain.USUARIOS);
        var contexto = new RequestEnvelopeContext();
        var fallos = new QueryFailureHandler(null, null, propiedades, topologia);
        RequestEnvelope envelope = RequestEnvelope.crear(java.util.UUID.randomUUID(), topologia.routingKey(),
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(), "sobre",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(5));
        // El destino de respuesta y la topologia se validan; se usa un publicador inalcanzable para
        // confirmar que la ruta elegida es la respuesta y no el retry.
        var conexion = new CachingConnectionFactory("127.0.0.1");
        conexion.setPort(1);
        conexion.setConnectionTimeout(200);
        var publicador = new RabbitTemplate(conexion);
        var respuestas = new QueryReplyPublisher(publicador, contexto, propiedades);
        var handoff = new HandoffPublisher(publicador, contexto, propiedades, topologia);
        var handler = new QueryFailureHandler(handoff, respuestas, propiedades, topologia);
        QueryFailureHandler.Resultado resultado = handler.gestionar(envelope, 0,
                QueryBusinessException.prohibido("No pertenece."), "corr-403",
                propiedades.queues().responses(), false);
        System.out.println("RESULTADO_403=" + resultado);
        assertThat(resultado).isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        conexion.destroy();
    }

    @Test
    void unFalloDefinitivoNoSeReintenta() {
        var propiedades = MessagingContractTests.propiedades();
        var topologia = QueryTopology.of(propiedades, Domain.USUARIOS);
        var contexto = new RequestEnvelopeContext();
        var conexion = new CachingConnectionFactory("127.0.0.1");
        conexion.setPort(1);
        conexion.setConnectionTimeout(200);
        var publicador = new RabbitTemplate(conexion);
        var respuestas = new QueryReplyPublisher(publicador, contexto, propiedades);
        var handoff = new HandoffPublisher(publicador, contexto, propiedades, topologia);
        var handler = new QueryFailureHandler(handoff, respuestas, propiedades, topologia);
        RequestEnvelope envelope = RequestEnvelope.crear(java.util.UUID.randomUUID(), topologia.routingKey(),
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(), "sobre",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(5));
        QueryFailureHandler.Resultado resultado = handler.gestionar(envelope, 0,
                new cl.duoc.pedidos360.messaging.envelope.EnvelopeException(
                        cl.duoc.pedidos360.messaging.envelope.EnvelopeException.Reason.ESQUEMA_INVALIDO, "invalido"),
                "corr-x", propiedades.queues().responses(), false);
        assertThat(resultado).isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        System.out.println("RESULTADO_DEFINITIVO=" + resultado + " retryDelay=" + propiedades.retryDelay()
                + " handoffAttempts=" + propiedades.handoffAttempts());
        conexion.destroy();
    }

    @Test
    void unFalloTransitorioVaAlRetry() {
        var propiedades = MessagingContractTests.propiedades();
        var topologia = QueryTopology.of(propiedades, Domain.USUARIOS);
        var contexto = new RequestEnvelopeContext();
        var conexion = new CachingConnectionFactory("127.0.0.1");
        conexion.setPort(1);
        conexion.setConnectionTimeout(200);
        var publicador = new RabbitTemplate(conexion);
        var respuestas = new QueryReplyPublisher(publicador, contexto, propiedades);
        var handoff = new HandoffPublisher(publicador, contexto, propiedades, topologia);
        var handler = new QueryFailureHandler(handoff, respuestas, propiedades, topologia);
        RequestEnvelope envelope = RequestEnvelope.crear(java.util.UUID.randomUUID(), topologia.routingKey(),
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(), "sobre",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(5));
        QueryFailureHandler.Resultado primero = handler.gestionar(envelope, 0,
                new cl.duoc.pedidos360.messaging.envelope.QueryTemporaryException("temporal"), "corr-y",
                propiedades.queues().responses(), false);
        assertThat(primero).isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        assertThat(propiedades.retryDelay()).isEqualTo(Duration.ofSeconds(1));
    }
}
