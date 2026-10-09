package cl.duoc.pedidos360.pagos;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.relay.QueryDeadlineGuard;
import cl.duoc.pedidos360.pagos.dto.PagoResponse;
import cl.duoc.pedidos360.pagos.messaging.*;
import cl.duoc.pedidos360.pagos.service.PagoService;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Projection/SQL/clock delays below are injections, not real transport or DB partitions. */
class PagosQueryProcessorTests {
    final Instant now=Instant.parse("2026-10-08T12:00:00Z");
    final UUID tenant=UUID.randomUUID(),oid=UUID.randomUUID();
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final AtomicLong ticks=new AtomicLong();
    final JsonMapper json=spy(JsonMapper.builder().build());
    final PagoService service=mock(PagoService.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final IdentityProofVerifier verifier=new IdentityProofVerifier(IdentityProofKeys.load(PagosQueryKeys.proofs(),PagosQueryKeys.actors()),clock,Duration.ofMillis(250));
    final ActorContext actor=new ActorContext(tenant,oid,Set.of("ADMIN"),Set.of("access_as_user"),now,now.plusMillis(3750),IdentityProof.AUDIENCE,PagosQueryKeys.ACTOR_ID);
    RequestEnvelope request() {
        var p=new IdentityProof(tenant,oid,10,now,now,now.plusSeconds(4),now.plusSeconds(5),UUID.randomUUID(),UUID.randomUUID());
        return RequestEnvelope.crear(UUID.randomUUID(),IdentityProof.OPERATION,json.createObjectNode().put("pagoId",1).put("pruebaIdentidad",PagosQueryKeys.proof(p)),"verified by consumer",now,now.plusMillis(3750));
    }
    PagosQueryProcessor processor() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(service.obtener(any(),eq(1L))).thenReturn(new PagoResponse(1L,2L,10L,1000L,"CLP","EFECTIVO","PENDIENTE",now));
        return new PagosQueryProcessor(service,verifier,json,jdbc,manager);
    }
    @ParameterizedTest @ValueSource(strings={"sql","projection"})
    void noFunctionalResultAfterBudgetExpiresInsideSqlOrProjection(String phase) {
        var processor=processor(); var r=request(); var guard=new QueryDeadlineGuard(r.expiresAt(),clock,ticks::get);
        if(phase.equals("sql")) doAnswer(c->{ticks.set(3_750_000_000L);return new PagoResponse(1L,2L,10L,1000L,"CLP","EFECTIVO","PENDIENTE",now);}).when(service).obtener(any(),any());
        else doAnswer(c->{ticks.set(3_750_000_000L);return c.callRealMethod();}).when(json).valueToTree(any(PagoQueryResponse.class));
        assertThatThrownBy(()->processor.procesar(actor,r,guard)).isInstanceOf(QueryDeadlineGuard.Expired.class);
        ticks.set(0);
        assertThat(guard.exhausted()).isTrue();
        assertThatThrownBy(()->processor.procesar(actor,r,guard)).isInstanceOf(QueryDeadlineGuard.Expired.class);
        verify(service,times(1)).obtener(any(),any());
    }
    @Test void verifiedLocalIdentityAndAdminRolesReachExistingService() {
        var processor=processor(); var r=request(); var guard=new QueryDeadlineGuard(r.expiresAt(),clock,ticks::get);
        assertThat(processor.procesar(actor,r,guard).propertyNames()).hasSize(8);
        verify(service).obtener(argThat(a->a.tenantId().equals(tenant)&&a.usuarioId()==10L&&a.esAdmin()),eq(1L));
        verify(jdbc).queryForObject(eq("SELECT set_config('statement_timeout', ?, true)"),eq(String.class),eq("3750ms"));
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"pagoId\":1}","{\"pagoId\":1,\"pruebaIdentidad\":null}",
            "{\"pagoId\":9223372036854775808,\"pruebaIdentidad\":\"x\"}","{\"pagoId\":1.0,\"pruebaIdentidad\":\"x\"}",
            "{\"pagoId\":1,\"pruebaIdentidad\":\"x\",\"tenantId\":\"fake\"}","{\"pagoId\":true,\"pruebaIdentidad\":\"x\"}",
            "{\"pagoId\":1,\"pruebaIdentidad\":{}}","{\"pagoId\":-1,\"pruebaIdentidad\":\"x\"}"})
    void strictPayloadRejectsInvalidShapesBeforeDomain(String body) {
        assertThatThrownBy(()->PagoQueryRequest.read(json.readTree(body))).isInstanceOf(EnvelopeException.class);
        verifyNoInteractions(service,jdbc);
    }
}
