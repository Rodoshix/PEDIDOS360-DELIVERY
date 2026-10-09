package cl.duoc.pedidos360.messaging;

import cl.duoc.pedidos360.messaging.actor.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.relay.*;
import com.rabbitmq.client.Channel;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;
import static cl.duoc.pedidos360.messaging.relay.HandoffFailureException.ResultadoPublicacion.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deterministic injected clock/transport/ACK faults; not a network partition test. */
class GuardedQueryReliabilityTests {
    final MessagingProperties p=MessagingContractTests.propiedades();
    final QueryTopology topology=QueryTopology.of(p,Domain.PAGOS);
    final RequestEnvelopeContext codec=new RequestEnvelopeContext();
    final Instant start=Instant.now();
    final QueryDeadlineGuardTests.MutableClock clock=new QueryDeadlineGuardTests.MutableClock();
    GuardedQueryReliabilityTests() { clock.now=start; }
    RequestEnvelope request() { return RequestEnvelope.crear(UUID.randomUUID(),topology.routingKey(),
            JsonMapper.builder().build().createObjectNode(),"fixture",start,start.plusSeconds(5)); }
    QueryDeadlineGuard guard(RequestEnvelope r) { return new QueryDeadlineGuard(r.expiresAt(),clock,System::nanoTime); }
    void confirmed(RabbitTemplate rabbit) {
        doAnswer(c->{c.getArgument(3,CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
                .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @ParameterizedTest @ValueSource(strings={"reply","retry"})
    void guardExpiryInsideSendIsUncertainAndCannotPublishAlternative(String target) {
        var r=request(); var g=guard(r); var rabbit=mock(RabbitTemplate.class);
        doAnswer(c->{clock.now=start.plusSeconds(6);return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        Throwable error=catchThrowable(()->{
            if(target.equals("reply")) new QueryReplyPublisher(rabbit,codec,p,clock).publicar(r,p.queues().responses(),"corr",QueryResponse.exito(r,"corr",r.payload(),start),g);
            else new HandoffPublisher(rabbit,codec,p,topology).aRetry(r,0,"fixture",null,g);
        });
        assertThat(error).isInstanceOfSatisfying(HandoffFailureException.class,e->assertThat(e.resultado()).isEqualTo(INCIERTO));
        var handoff=mock(HandoffPublisher.class); var replies=new QueryReplyPublisher(rabbit,codec,p,clock);
        assertThat(new QueryFailureHandler(handoff,replies,p,topology).gestionar(r,0,(Exception)error,"corr",p.queues().responses(),true,null,g))
                .isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        verifyNoInteractions(handoff);
    }
    @ParameterizedTest @ValueSource(strings={"reply","retry"})
    void terminalGuardPreventsSendAfterBackwardClock(String target) {
        var r=request(); var g=guard(r); clock.now=start.plusSeconds(6); assertThat(g.exhausted()).isTrue(); clock.now=start.minusSeconds(30);
        var rabbit=mock(RabbitTemplate.class);
        Throwable error=catchThrowable(()->{
            if(target.equals("reply")) new QueryReplyPublisher(rabbit,codec,p,clock).publicar(r,p.queues().responses(),"corr",QueryResponse.exito(r,"corr",r.payload(),start),g);
            else new HandoffPublisher(rabbit,codec,p,topology).aRetry(r,0,"fixture",null,g);
        });
        assertThat(error).isInstanceOfSatisfying(HandoffFailureException.class,e->assertThat(e.resultado()).isEqualTo(NO_ENVIADO));
        verify(rabbit,never()).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @ParameterizedTest @ValueSource(strings={"confirm","nack","return","transport","interrupt","expiry","timeout","ackFailure"})
    void guardedConsumerSettlesOnlyAfterConfirmedTransfer(String mode) throws Exception {
        var r=request(); var rabbit=mock(RabbitTemplate.class); var channel=mock(Channel.class); var recovery=mock(HandoffRecovery.class);
        var actor=mock(ActorContextSigner.class);
        when(actor.verificar(any(),any(),any(),any())).thenReturn(new ActorContext(UUID.randomUUID(),UUID.randomUUID(),
                Set.of("CLIENTE"),Set.of("access_as_user"),start,start.plusSeconds(4),topology.queue(),UUID.randomUUID()));
        doAnswer(c->{
            verify(channel,never()).basicAck(anyLong(),anyBoolean());
            var cd=c.getArgument(3,CorrelationData.class); String exchange=c.getArgument(0);
            if(!exchange.isEmpty()) { cd.getFuture().complete(new CorrelationData.Confirm(true,null)); return null; }
            if(mode.equals("transport")) throw new java.io.IOException("injected");
            if(mode.equals("interrupt")) { Thread.currentThread().interrupt(); return null; }
            if(mode.equals("timeout")) return null;
            if(mode.equals("return")) cd.setReturned(new ReturnedMessage(c.getArgument(2),312,"NO_ROUTE","","fixture"));
            cd.getFuture().complete(new CorrelationData.Confirm(!mode.equals("nack"),null)); return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        if(mode.equals("ackFailure")) doThrow(new java.io.IOException("injected ACK")).when(channel).basicAck(7,false);
        var replies=new QueryReplyPublisher(rabbit,codec,p,clock); var handoff=new HandoffPublisher(rabbit,codec,p,topology);
        var failures=new QueryFailureHandler(handoff,replies,p,topology);
        QueryProcessor processor=(a,envelope)->{
            if(mode.equals("expiry")) clock.now=start.plusSeconds(6);
            return envelope.payload();
        };
        var consumer=new QueryConsumer(codec,actor,processor,replies,failures,topology,"fixture",null,recovery,262144,clock,true);
        var metadata=new MessageProperties(); metadata.setMessageId(r.messageId().toString()); metadata.setCorrelationId(UUID.randomUUID().toString());
        metadata.setReplyTo(p.queues().responses()); metadata.setDeliveryTag(7);
        try {
            consumer.consumir(new Message(codec.escribir(r),metadata),channel);
            if(List.of("transport","interrupt","timeout").contains(mode)) {
                verify(channel,never()).basicAck(anyLong(),anyBoolean());
                verify(rabbit,times(1)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
                verify(recovery).sinConfirmar(any(),any(),any(),any());
            } else {
                verify(channel).basicAck(7,false);
                if(mode.equals("confirm")||mode.equals("ackFailure")) verify(rabbit,times(1)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
                if(mode.equals("expiry")) verify(rabbit).send(eq(p.exchanges().dlx()),eq(topology.failedRoutingKey()),any(Message.class),any(CorrelationData.class));
                if(mode.equals("nack")||mode.equals("return")) verify(rabbit).send(eq(p.exchanges().retry()),eq(topology.retryRoutingKey()),any(Message.class),any(CorrelationData.class));
                if(mode.equals("ackFailure")) verify(recovery).sinConfirmar(any(),any(),eq("settlement"),any());
                else verifyNoInteractions(recovery);
            }
        } finally { Thread.interrupted(); }
    }
    @Test void expiredDiagnosticDlqUsesIndependentConfirmWindowAndPreservesOriginal() {
        var r=request(); var g=guard(r); clock.now=start.plusSeconds(6); assertThat(g.exhausted()).isTrue();
        var rabbit=mock(RabbitTemplate.class); confirmed(rabbit);
        var replies=new QueryReplyPublisher(rabbit,codec,p,clock); var handoff=new HandoffPublisher(rabbit,codec,p,topology);
        assertThat(new QueryFailureHandler(handoff,replies,p,topology).gestionar(r,0,new QueryDeadlineGuard.Expired(),"corr",p.queues().responses(),true,null,g))
                .isEqualTo(QueryFailureHandler.Resultado.DLQ);
        verify(rabbit).send(eq(p.exchanges().dlx()),eq(topology.failedRoutingKey()),argThat(m->codec.leer(m.getBody(),262144).equals(r)),any(CorrelationData.class));
    }
    @ParameterizedTest @ValueSource(strings={"nack","return","transport","timeout"})
    void expiredDlqFailureNeverSettlesOrAttemptsFunctionalAlternative(String mode) {
        var r=request(); var g=guard(r); clock.now=start.plusSeconds(6);
        var rabbit=mock(RabbitTemplate.class);
        doAnswer(c->{
            var cd=c.getArgument(3,CorrelationData.class);
            if(mode.equals("transport")) throw new java.io.IOException("injected");
            if(mode.equals("timeout")) return null;
            if(mode.equals("return")) cd.setReturned(new ReturnedMessage(c.getArgument(2),312,"NO_ROUTE","p360.dlx","fixture"));
            cd.getFuture().complete(new CorrelationData.Confirm(!mode.equals("nack"),null)); return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var replies=new QueryReplyPublisher(rabbit,codec,p,clock);
        var handoff=new HandoffPublisher(rabbit,codec,p,topology);
        assertThat(new QueryFailureHandler(handoff,replies,p,topology).gestionar(r,0,new QueryDeadlineGuard.Expired(),"corr",p.queues().responses(),true,null,g))
                .isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        verify(rabbit,times(1)).send(eq(p.exchanges().dlx()),eq(topology.failedRoutingKey()),any(Message.class),any(CorrelationData.class));
        assertThat(g.exhausted()).isTrue();
    }
    @Test void extremeOrOverlongEnvelopeIsProtocolFailureBeforeBudgetArithmeticOrBusiness() throws Exception {
        var actor=mock(ActorContextSigner.class); var processor=mock(QueryProcessor.class);
        var rabbit=mock(RabbitTemplate.class); confirmed(rabbit);
        var replies=new QueryReplyPublisher(rabbit,codec,p,clock);
        var handoff=new HandoffPublisher(rabbit,codec,p,topology);
        var failures=new QueryFailureHandler(handoff,replies,p,topology);
        var channel=mock(Channel.class);
        var consumer=new QueryConsumer(codec,actor,processor,replies,failures,topology,"fixture",null,mock(HandoffRecovery.class),262144,clock,true);
        for(var expiry:List.of(start.plusSeconds(6),Instant.parse("9999-12-31T00:00:00Z"))) {
            var r=RequestEnvelope.crear(UUID.randomUUID(),topology.routingKey(),request().payload(),"fixture",start,expiry);
            var metadata=new MessageProperties(); metadata.setMessageId(r.messageId().toString()); metadata.setCorrelationId(UUID.randomUUID().toString());
            metadata.setReplyTo(p.queues().responses()); metadata.setDeliveryTag(7);
            consumer.consumir(new Message(codec.escribir(r),metadata),channel);
        }
        verifyNoInteractions(actor,processor);
        verify(rabbit,times(2)).send(eq(p.exchanges().dlx()),eq(topology.failedRoutingKey()),any(Message.class),any(CorrelationData.class));
        verify(channel,times(2)).basicAck(7,false);
    }
}
