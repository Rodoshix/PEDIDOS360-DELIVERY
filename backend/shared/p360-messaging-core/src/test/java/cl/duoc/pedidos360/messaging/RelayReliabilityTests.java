package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import com.rabbitmq.client.Channel;
import cl.duoc.pedidos360.messaging.actor.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.relay.*;
import tools.jackson.databind.json.JsonMapper;

/** Tests unitarios de decisiones y presupuesto; no son evidencia de broker real. */
class RelayReliabilityTests {
    final MessagingProperties p=MessagingContractTests.propiedades();
    final QueryTopology topology=QueryTopology.of(p,Domain.USUARIOS);
    final RequestEnvelopeContext context=new RequestEnvelopeContext();
    RequestEnvelope request(Instant expiry) {
        return RequestEnvelope.crear(UUID.randomUUID(),topology.routingKey(),JsonMapper.builder().build().createObjectNode(),
                "fixture",expiry.minusSeconds(5),expiry);
    }
    QueryResponse response(RequestEnvelope request) {
        return QueryResponse.exito(request,"corr",JsonMapper.builder().build().createObjectNode(),Instant.now());
    }
    QueryFailureHandler handler(HandoffPublisher handoff,QueryReplyPublisher replies) {
        when(replies.ahora()).thenAnswer(call -> Instant.now());
        return new QueryFailureHandler(handoff,replies,p,topology);
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,Integer.MAX_VALUE})
    void failedBusinessReplyCannotExceedOneRetry(int retries) {
        var req=request(Instant.now().plusSeconds(5));
        var handoff=mock(HandoffPublisher.class); var replies=mock(QueryReplyPublisher.class);
        when(replies.publicar(any(),any(),any(),any())).thenThrow(new HandoffFailureException("return conocido"));
        var result=handler(handoff,replies).gestionar(req,retries,QueryBusinessException.prohibido("fixture"),"corr",p.queues().responses(),false);
        assertThat(result).isEqualTo(retries==0?QueryFailureHandler.Resultado.REINTENTADO:QueryFailureHandler.Resultado.DLQ);
        if(retries==0) { verify(handoff).aRetry(eq(req),eq(0),any(),isNull()); verify(handoff,never()).aDlq(any(),anyInt(),any(),any()); }
        else { verify(handoff).aDlq(eq(req),eq(retries),any(),isNull()); verify(handoff,never()).aRetry(any(),anyInt(),any(),any()); }
    }

    @Test void deadlineIsRecalculatedForBusinessAndTransientFailures() {
        var req=request(Instant.now().minusSeconds(1)); var handoff=mock(HandoffPublisher.class);
        var replies=mock(QueryReplyPublisher.class); var handler=handler(handoff,replies);
        for(Exception failure: new Exception[]{QueryBusinessException.prohibido("fixture"),new QueryTemporaryException("fixture")})
            assertThat(handler.gestionar(req,0,failure,"corr",p.queues().responses(),false)).isEqualTo(QueryFailureHandler.Resultado.DLQ);
        verify(handoff,times(2)).aDlq(eq(req),eq(0),any(),isNull());
        verify(replies,never()).publicar(any(),any(),any(),any());
        verify(handoff,never()).aRetry(any(),anyInt(),any(),any());
    }

    @Test void deadlineDuringFailedBusinessPublicationGoesToDlq() {
        var req=request(Instant.now().plusSeconds(5)); var handoff=mock(HandoffPublisher.class);
        var replies=mock(QueryReplyPublisher.class);
        var handler=handler(handoff,replies);
        when(replies.publicar(any(),any(),any(),any())).thenAnswer(call -> {
            when(replies.ahora()).thenReturn(req.expiresAt()); throw new HandoffFailureException("rejected");
        });
        assertThat(handler.gestionar(req,0,QueryBusinessException.prohibido("fixture"),"corr",p.queues().responses(),false))
                .isEqualTo(QueryFailureHandler.Resultado.DLQ);
        verify(handoff,never()).aRetry(any(),anyInt(),any(),any());
    }

    @Test void uncertainPublicationPreservesOriginalWithoutAnotherHandoff() {
        var req=request(Instant.now().plusSeconds(5)); var handoff=mock(HandoffPublisher.class);
        var replies=mock(QueryReplyPublisher.class); var handler=handler(handoff,replies);
        var uncertain=new HandoffFailureException("confirm incierto",new TimeoutException());
        when(replies.publicar(any(),any(),any(),any())).thenThrow(uncertain);
        assertThat(handler.gestionar(req,0,QueryBusinessException.prohibido("fixture"),"corr",p.queues().responses(),false))
                .isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        assertThat(handler.gestionar(req,0,uncertain,"corr",p.queues().responses(),false))
                .isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        verifyNoInteractions(handoff);
    }

    @Test void expiredFunctionalSendsAreNotAttemptedAndDlqHasIndependentConfirm() {
        var rabbit=mock(RabbitTemplate.class); var req=request(Instant.now().minusSeconds(1));
        var handoff=new HandoffPublisher(rabbit,context,p,topology);
        assertThatThrownBy(() -> new RequestPublisher(rabbit,context,p,"fixture").publicar(req,"corr"))
                .isInstanceOf(QueryTimeoutException.class);
        assertThatThrownBy(() -> new QueryReplyPublisher(rabbit,context,p).publicar(req,p.queues().responses(),"corr",response(req)))
                .isInstanceOf(HandoffFailureException.class);
        assertThatThrownBy(() -> handoff.aRetry(req,0,"fixture",null)).isInstanceOf(HandoffFailureException.class);
        verify(rabbit,never()).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        doAnswer(call -> { call.getArgument(3,CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true,null)); return null; })
                .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        handoff.aDlq(req,0,"PlazoVencidoException",null);
        verify(rabbit).send(eq(p.exchanges().dlx()),eq(topology.failedRoutingKey()),any(Message.class),any(CorrelationData.class));
        verify(rabbit,atLeastOnce()).setMandatory(true);
    }

    @ParameterizedTest @ValueSource(strings={"request","reply","retry"})
    void confirmWaitUsesRemainingBudget(String kind) {
        var rabbit=mock(RabbitTemplate.class); var req=request(Instant.now().plusMillis(150)); long start=System.nanoTime();
        assertThatThrownBy(() -> {
            switch(kind) {
                case "request" -> new RequestPublisher(rabbit,context,p,"fixture").publicar(req,"corr");
                case "reply" -> new QueryReplyPublisher(rabbit,context,p).publicar(req,p.queues().responses(),"corr",response(req));
                case "retry" -> new HandoffPublisher(rabbit,context,p,topology).aRetry(req,0,"fixture",null);
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test void expiryDuringSendIsUncertainAndReplyTtlNeverRenewsDeadline() {
        var rabbit=mock(RabbitTemplate.class); var now=Instant.parse("2026-10-08T10:00:00Z");
        var req=request(now.plusMillis(100)); var time=new AtomicReference<>(now);
        Clock clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        doAnswer(call -> {
            Message message=call.getArgument(2);
            assertThat(Long.parseLong(message.getMessageProperties().getExpiration())).isBetween(1L,100L);
            time.set(req.expiresAt()); return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThatThrownBy(() -> new QueryReplyPublisher(rabbit,context,p,clock).publicar(req,p.queues().responses(),"corr",response(req)))
                .isInstanceOf(HandoffFailureException.class)
                .satisfies(failure -> assertThat(((HandoffFailureException)failure).resultadoIncierto()).isTrue());
    }

    @Test void expiredDuringProcessorNeverPublishesFunctionalResponseAndAcksOnlyAfterDlq() throws Exception {
        var req=request(Instant.now().plusSeconds(5));
        var actor=mock(ActorContextSigner.class); var replies=mock(QueryReplyPublisher.class);
        var handoff=mock(HandoffPublisher.class); var failure=handler(handoff,replies);
        var recovery=mock(HandoffRecovery.class); var channel=mock(Channel.class); var time=new AtomicReference<>(req.occurredAt());
        Clock clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        var consumer=new QueryConsumer(context,actor,(a,r)-> { time.set(req.expiresAt());
            return JsonMapper.builder().build().createObjectNode(); },replies,failure,topology,"fixture",null,recovery,262144,clock);
        var metadata=new MessageProperties(); metadata.setDeliveryTag(7); metadata.setCorrelationId("corr"); metadata.setReplyTo(p.queues().responses());
        metadata.setMessageId(req.messageId().toString());
        doAnswer(call -> { verify(channel,never()).basicAck(anyLong(),anyBoolean()); return null; })
                .when(handoff).aDlq(any(),anyInt(),any(),any());
        consumer.consumir(new Message(context.escribir(req),metadata),channel);
        verify(replies,never()).publicar(any(),any(),any(),any()); verify(handoff).aDlq(eq(req),eq(0),any(),eq(metadata));
        verify(channel).basicAck(7,false); verifyNoInteractions(recovery);
    }

    @ParameterizedTest @ValueSource(strings={"nack","return","uncertain"})
    void failedDiagnosticHandoffNeverAcksAndInvokesExistingRecovery(String kind) throws Exception {
        var req=request(Instant.now().minusSeconds(1)); var replies=mock(QueryReplyPublisher.class);
        var handoff=mock(HandoffPublisher.class); var channel=mock(Channel.class); var recovery=mock(HandoffRecovery.class);
        var exception=kind.equals("uncertain")?new HandoffFailureException(kind,new TimeoutException()):new HandoffFailureException(kind);
        doThrow(exception).when(handoff).aDlq(any(),anyInt(),any(),any());
        var processor=mock(QueryProcessor.class);
        var consumer=new QueryConsumer(context,mock(ActorContextSigner.class),processor,replies,handler(handoff,replies),
                topology,"fixture",null,recovery,262144);
        var metadata=new MessageProperties(); metadata.setDeliveryTag(7); metadata.setMessageId(req.messageId().toString());
        consumer.consumir(new Message(context.escribir(req),metadata),channel);
        verify(channel,never()).basicAck(anyLong(),anyBoolean()); verify(recovery).sinConfirmar(any(),any(),any(),any());
        verifyNoInteractions(processor);
    }

    @Test void expiryDuringPrecheckDoesNotExecuteProcessor() throws Exception {
        var req=request(Instant.now().plusSeconds(5)); var time=new AtomicReference<>(req.occurredAt());
        Clock clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        var replies=mock(QueryReplyPublisher.class); var handoff=mock(HandoffPublisher.class);
        var processor=mock(QueryProcessor.class); var channel=mock(Channel.class);
        var consumer=new QueryConsumer(context,mock(ActorContextSigner.class),processor,replies,handler(handoff,replies),
                topology,"fixture",a -> time.set(req.expiresAt()),mock(HandoffRecovery.class),262144,clock);
        var metadata=new MessageProperties(); metadata.setMessageId(req.messageId().toString());
        consumer.consumir(new Message(context.escribir(req),metadata),channel);
        verifyNoInteractions(processor); verify(handoff).aDlq(eq(req),eq(0),any(),eq(metadata));
    }
}
