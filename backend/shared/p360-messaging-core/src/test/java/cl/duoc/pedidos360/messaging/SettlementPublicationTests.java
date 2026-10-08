package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cl.duoc.pedidos360.messaging.relay.HandoffFailureException.ResultadoPublicacion.*;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.ShutdownSignalException;
import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.relay.*;
import tools.jackson.databind.json.JsonMapper;

/** Fallos inyectados: no acredita una partición real de red. */
class SettlementPublicationTests {
    final MessagingProperties p = MessagingContractTests.propiedades();
    final QueryTopology topology = QueryTopology.of(p, Domain.USUARIOS);
    final RequestEnvelopeContext context = new RequestEnvelopeContext();
    RequestEnvelope request(Instant expiry) {
        return RequestEnvelope.crear(UUID.randomUUID(), topology.routingKey(),
                JsonMapper.builder().build().createObjectNode(), "fixture", expiry.minusSeconds(5), expiry);
    }

    @ParameterizedTest
    @CsvSource({"response,ok", "response,io", "response,closed", "retry,ok", "retry,io", "retry,closed",
                "dlq,ok", "dlq,io", "dlq,closed"})
    void settlementNeverReentersBusinessOrPublishesAnAlternative(String destination, String ack) throws Exception {
        var req = request(Instant.now().plusSeconds(5));
        var replies = mock(QueryReplyPublisher.class);
        when(replies.ahora()).thenAnswer(c -> Instant.now());
        var handoff = mock(HandoffPublisher.class);
        var failures = spy(new QueryFailureHandler(handoff, replies, p, topology));
        var recovery = mock(HandoffRecovery.class);
        var channel = mock(Channel.class);
        if (ack.equals("io")) doThrow(new IOException("fixture")).when(channel).basicAck(7, false);
        if (ack.equals("closed")) doThrow(new AlreadyClosedException(
                new ShutdownSignalException(false, false, null, null))).when(channel).basicAck(7, false);
        var executions = new AtomicInteger();
        QueryProcessor processor = (a,r) -> {
            executions.incrementAndGet();
            if (!destination.equals("response")) throw new QueryTemporaryException("fixture");
            return JsonMapper.builder().build().createObjectNode();
        };
        var consumer = new QueryConsumer(context, mock(ActorContextSigner.class), processor, replies,
                failures, topology, "fixture", null, recovery, 262144);
        var metadata = new MessageProperties();
        metadata.setDeliveryTag(7); metadata.setCorrelationId("corr"); metadata.setReplyTo(p.queues().responses());
        metadata.setMessageId(req.messageId().toString()); metadata.setRetryCount(destination.equals("dlq") ? 1 : 0);
        consumer.consumir(new Message(context.escribir(req), metadata), channel);
        assertThat(executions).hasValue(1);
        verify(channel).basicAck(7, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        if (destination.equals("response")) {
            verify(replies).publicar(eq(req), any(), any(), any());
            verifyNoInteractions(handoff);
            verify(failures, never()).gestionar(any(), anyInt(), any(), any(), any(), anyBoolean(), any());
        } else {
            verify(replies, never()).publicar(any(), any(), any(), any());
            verify(failures).gestionar(eq(req), anyInt(), any(), any(), any(), anyBoolean(), eq(metadata));
            if (destination.equals("retry")) {
                verify(handoff).aRetry(eq(req), eq(0), any(), eq(metadata));
                verify(handoff, never()).aDlq(any(), anyInt(), any(), any());
            } else {
                verify(handoff).aDlq(eq(req), eq(1), any(), eq(metadata));
                verify(handoff, never()).aRetry(any(), anyInt(), any(), any());
            }
        }
        var order = inOrder(replies, handoff, channel, recovery);
        if (destination.equals("response")) order.verify(replies).publicar(any(), any(), any(), any());
        else if (destination.equals("retry")) order.verify(handoff).aRetry(any(), anyInt(), any(), any());
        else order.verify(handoff).aDlq(any(), anyInt(), any(), any());
        order.verify(channel).basicAck(7, false);
        if (ack.equals("ok")) verifyNoInteractions(recovery);
        else order.verify(recovery).sinConfirmar(eq(req.messageId().toString()), eq("corr"), eq("settlement"), any());
    }

    @Test void programmingErrorsInSettlementAreNotHiddenOrRoutedToFailurePolicy() throws Exception {
        var req = request(Instant.now().plusSeconds(5));
        var replies = mock(QueryReplyPublisher.class); when(replies.ahora()).thenReturn(Instant.now());
        var failures = mock(QueryFailureHandler.class); var recovery = mock(HandoffRecovery.class);
        var channel = mock(Channel.class);
        doThrow(new IllegalStateException("programming fixture")).when(channel).basicAck(anyLong(), anyBoolean());
        var consumer = new QueryConsumer(context, mock(ActorContextSigner.class),
                (a,r) -> JsonMapper.builder().build().createObjectNode(), replies, failures, topology,
                "fixture", null, recovery, 262144);
        var metadata = new MessageProperties(); metadata.setCorrelationId("corr"); metadata.setReplyTo(p.queues().responses());
        assertThatThrownBy(() -> consumer.consumir(new Message(context.escribir(req), metadata), channel))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(failures, recovery);
    }

    @Test void uncertaintyIsExplicitAndIndependentOfJavaCause() {
        assertThat(new HandoffFailureException("fixture", INCIERTO).resultadoIncierto()).isTrue();
        assertThat(new HandoffFailureException("fixture", NO_ENVIADO, new IOException()).resultadoIncierto()).isFalse();
        assertThat(new HandoffFailureException("fixture", RECHAZADO_CONFIRMADO, new IOException()).resultadoIncierto()).isFalse();
        assertThat(new HandoffFailureException("fixture", CONFIRMADO).resultadoIncierto()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"request", "reply", "retry"})
    void expiryDuringSendIsUncertainForEveryFunctionalPublisher(String publisher) {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(c -> { Thread.sleep(250); return null; }).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var req = request(Instant.now().plusMillis(150));
        var failure = catchThrowable(() -> publish(publisher, rabbit, req));
        var publication = classification(failure);
        assertThat(publication.resultado()).isEqualTo(INCIERTO);
        verify(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var handoff = mock(HandoffPublisher.class); var replies = mock(QueryReplyPublisher.class);
        assertThat(new QueryFailureHandler(handoff, replies, p, topology)
                .gestionar(req, 0, publication, "corr", p.queues().responses(), true))
                .isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        verifyNoInteractions(handoff);
    }

    @ParameterizedTest @CsvSource({"reply,nack", "reply,return", "reply,transport", "reply,interrupt",
            "retry,nack", "retry,return", "retry,transport", "retry,interrupt",
            "request,nack", "request,return", "request,transport", "request,interrupt"})
    void conclusiveRejectionAndTransportFailuresHaveExplicitStates(String publisher, String fault) {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(c -> {
            var cd = c.getArgument(3, CorrelationData.class);
            if (fault.equals("transport")) throw new org.springframework.amqp.AmqpIOException(new IOException("fixture"));
            if (fault.equals("interrupt")) { Thread.currentThread().interrupt(); return null; }
            if (fault.equals("return")) cd.setReturned(new org.springframework.amqp.core.ReturnedMessage(
                    c.getArgument(2), 312, "NO_ROUTE", "fixture", "fixture"));
            cd.getFuture().complete(new CorrelationData.Confirm(!fault.equals("nack"), "fixture")); return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        try {
            var failure = catchThrowable(() -> publish(publisher, rabbit, request(Instant.now().plusSeconds(5))));
            assertThat(classification(failure).resultado()).isEqualTo(
                    fault.equals("nack") || fault.equals("return") ? RECHAZADO_CONFIRMADO : INCIERTO);
            if (fault.equals("interrupt")) assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest @ValueSource(strings={"request", "reply", "retry"})
    void expiryBeforeSendIsNotSent(String publisher) {
        var rabbit = mock(RabbitTemplate.class);
        assertThat(classification(catchThrowable(() -> publish(publisher, rabbit, request(Instant.now().minusSeconds(1)))))
                .resultado()).isEqualTo(NO_ENVIADO);
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @ParameterizedTest @ValueSource(strings={"request", "reply", "retry"})
    void timeoutAwaitingConfirmIsUncertain(String publisher) {
        var rabbit = mock(RabbitTemplate.class);
        var req = request(Instant.now().plusMillis(150));
        assertThat(classification(catchThrowable(() -> publish(publisher, rabbit, req))).resultado()).isEqualTo(INCIERTO);
        verify(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @ParameterizedTest @ValueSource(strings={"request", "reply", "retry"})
    void serializationFailureWithCauseIsNotSent(String publisher) {
        var rabbit = mock(RabbitTemplate.class); var codec = mock(RequestEnvelopeContext.class);
        when(codec.escribir(any())).thenThrow(new IllegalArgumentException("fixture"));
        when(codec.escribirRespuesta(any())).thenThrow(new IllegalArgumentException("fixture"));
        var req = request(Instant.now().plusSeconds(5));
        var failure = catchThrowable(() -> {
            switch (publisher) {
                case "request" -> new RequestPublisher(rabbit, codec, p, "fixture").publicar(req, "corr");
                case "reply" -> new QueryReplyPublisher(rabbit, codec, p).publicar(req, p.queues().responses(), "corr",
                        QueryResponse.exito(req, "corr", JsonMapper.builder().build().createObjectNode(), Instant.now()));
                case "retry" -> new HandoffPublisher(rabbit, codec, p, topology).aRetry(req, 0, "fixture", null);
            }
        });
        assertThat(classification(failure).resultado()).isEqualTo(NO_ENVIADO);
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private HandoffFailureException classification(Throwable failure) {
        assertThat(failure).isNotNull();
        return failure instanceof HandoffFailureException h ? h : (HandoffFailureException) failure.getCause();
    }
    private void publish(String publisher, RabbitTemplate rabbit, RequestEnvelope req) {
        switch (publisher) {
            case "request" -> new RequestPublisher(rabbit, context, p, "fixture").publicar(req, "corr");
            case "reply" -> new QueryReplyPublisher(rabbit, context, p).publicar(req, p.queues().responses(), "corr",
                    QueryResponse.exito(req, "corr", JsonMapper.builder().build().createObjectNode(), Instant.now()));
            case "retry" -> new HandoffPublisher(rabbit, context, p, topology).aRetry(req, 0, "fixture", null);
        }
    }
}
