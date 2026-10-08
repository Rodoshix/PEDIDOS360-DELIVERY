package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cl.duoc.pedidos360.messaging.relay.HandoffFailureException.ResultadoPublicacion.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.relay.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Confirm/transporte inyectados: ninguna publicación alternativa ni broker real. */
class RequestPublisherBudgetCorrectionTests {
    final MessagingProperties properties=MessagingContractTests.propiedades();
    RequestEnvelope request(Instant expiry) {
        return RequestEnvelope.crear(UUID.randomUUID(),"usuario.consultar-actual.v1",JsonMapper.builder().build().createObjectNode(),"fixture",expiry.minusSeconds(5),expiry);
    }
    long remaining(String mode) {
        return switch(mode) {
            case "zero" -> 0;
            case "negative" -> -1;
            case "throws" -> throw new QueryTimeoutException("fixture-expired");
            default -> 2_000_000L;
        };
    }
    HandoffFailureException classification(Throwable failure) { return (HandoffFailureException)failure.getCause(); }
    @ParameterizedTest @ValueSource(strings={"zero","negative","throws","positive"})
    void timeoutAfterSendUsesActualBudgetEvidenceAndKeepsUncertainty(String mode) {
        var rabbit=mock(RabbitTemplate.class);var calls=new AtomicInteger();
        LongSupplier budget=()->calls.incrementAndGet()<=3?2_000_000L:remaining(mode);
        var failure=catchThrowable(()->new RequestPublisher(rabbit,new RequestEnvelopeContext(),properties,"fixture")
                .publicarConMedicion(request(Instant.now().plusSeconds(5)),"corr",budget));
        assertThat(failure).isInstanceOf(mode.equals("positive")?QueryUnavailableException.class:QueryTimeoutException.class);
        assertThat(classification(failure).resultado()).isEqualTo(INCIERTO);
        verify(rabbit,times(1)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @ParameterizedTest @ValueSource(strings={"zero","negative","throws"})
    void exhaustedCallbackBeforeSendNeverPublishes(String mode) {
        var rabbit=mock(RabbitTemplate.class);
        var failure=catchThrowable(()->new RequestPublisher(rabbit,new RequestEnvelopeContext(),properties,"fixture")
                .publicarConMedicion(request(Instant.now().plusSeconds(5)),"corr",()->remaining(mode)));
        assertThat(failure).isInstanceOf(QueryTimeoutException.class);
        assertThat(classification(failure).resultado()).isEqualTo(NO_ENVIADO);
        verify(rabbit,never()).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @ParameterizedTest @ValueSource(strings={"nack","return"})
    void conclusiveRejectionRemainsUnavailableRatherThanUncertain(String mode) {
        var rabbit=mock(RabbitTemplate.class);
        doAnswer(call->{var cd=call.getArgument(3,CorrelationData.class);
            if(mode.equals("return")) cd.setReturned(new org.springframework.amqp.core.ReturnedMessage(call.getArgument(2),312,"fixture","exchange","key"));
            cd.getFuture().complete(new CorrelationData.Confirm(!mode.equals("nack"),"fixture"));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var failure=catchThrowable(()->new RequestPublisher(rabbit,new RequestEnvelopeContext(),properties,"fixture")
                .publicarConMedicion(request(Instant.now().plusSeconds(5)),"corr",()->1_000_000_000L));
        assertThat(failure).isInstanceOf(QueryUnavailableException.class);
        assertThat(classification(failure).resultado()).isEqualTo(RECHAZADO_CONFIRMADO);
        verify(rabbit,times(1)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @Test void wallDeadlineExpiredBeforeSendNeverPublishes() {
        var rabbit=mock(RabbitTemplate.class);
        var failure=catchThrowable(()->new RequestPublisher(rabbit,new RequestEnvelopeContext(),properties,"fixture")
                .publicarConMedicion(request(Instant.now().minusMillis(1)),"corr",()->1_000_000_000L));
        assertThat(failure).isInstanceOf(QueryTimeoutException.class);assertThat(classification(failure).resultado()).isEqualTo(NO_ENVIADO);
        verify(rabbit,never()).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @Test void transportFailureWithLiveBudgetIsNotConvertedToTimeout() {
        var rabbit=mock(RabbitTemplate.class);
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("fixture")))
                .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var failure=catchThrowable(()->new RequestPublisher(rabbit,new RequestEnvelopeContext(),properties,"fixture")
                .publicarConMedicion(request(Instant.now().plusSeconds(5)),"corr",()->1_000_000_000L));
        assertThat(failure).isInstanceOf(QueryUnavailableException.class);assertThat(classification(failure).resultado()).isEqualTo(INCIERTO);
        verify(rabbit,times(1)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
}
