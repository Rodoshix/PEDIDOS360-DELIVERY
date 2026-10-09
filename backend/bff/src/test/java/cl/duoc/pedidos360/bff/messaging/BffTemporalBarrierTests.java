package cl.duoc.pedidos360.bff.messaging;

import cl.duoc.pedidos360.messaging.*;
import cl.duoc.pedidos360.messaging.actor.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.fixture.*;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.relay.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real ES256; injected time, publisher and resolution. No broker/SQL in this suite. */
class BffTemporalBarrierTests {
    final Instant start=IdentityProofCodec.millis(Instant.now());
    final UUID tenant=UUID.randomUUID(), oid=UUID.randomUUID();
    final AtomicLong nanos=new AtomicLong();
    final BffIdentityProofTests.MutableClock clock=new BffIdentityProofTests.MutableClock(start);
    final JsonMapper json=JsonMapper.builder().build();
    final PendingCorrelationRegistry registry=new PendingCorrelationRegistry(16);
    final RequestEnvelopeContext codec=new RequestEnvelopeContext();
    final RequestPublisher publisher=mock(RequestPublisher.class);
    final ResponseSchema schema=spy(new ResponseSchema());
    final MessagingProperties props=new MessagingProperties(RelayMode.ACTIVE,RelayMode.Role.BFF,
            new MessagingProperties.Exchanges("p360.queries","p360.retry","p360.dlx"),
            new MessagingProperties.Queues("p360.bff.consultas.respuestas.q"),
            new MessagingProperties.Naming("p360.",".consultas.q",".consultas.retry.1s.q",".consultas.dlq",".retry.1s",".failed"),
            new MessagingProperties.DomainRouting("usuario.consultar-actual.v1","restaurante.listar.v1","producto.listar-disponibles.v1","pago.consultar.v1",
                    "usuario.consultar-actual","restaurante.listar","producto.listar-disponibles","pago.consultar"),
            Duration.ofSeconds(5),Duration.ofSeconds(4),Duration.ofSeconds(1),Duration.ofSeconds(3),Duration.ofMillis(50),false,16,262144);
    final JwtAuthenticationToken token=new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","RS256")
            .issuedAt(start.minusSeconds(1)).expiresAt(start.plusSeconds(60)).claim("tid",tenant.toString()).claim("oid",oid.toString()).build(),
            List.of(new SimpleGrantedAuthority("ROLE_CLIENTE"),new SimpleGrantedAuthority("SCOPE_access_as_user")));
    final QueryOperationBudget budget=QueryOperationBudget.start(token,Duration.ofSeconds(5),clock,nanos::get);
    BffQueryAdapter adapter(Domain domain) {
        var actors=new BffActorContextFactory(new ActorContextSigner(FixtureActorKeys.provider(),clock,Duration.ZERO),
                new BffActorProperties(tenant.toString(),Duration.ofSeconds(4),Set.of("p360.usuarios.consultas.q","p360.pagos.consultas.q")));
        var adapter=new BffQueryAdapter(new RequestFactory(props,actors),publisher,registry,codec,schema,props);
        var operation=mock(QueryInvoker.class); when(operation.operacion()).thenReturn(props.routing().operacion(domain));
        adapter.registrar(domain,operation); return adapter;
    }
    void advance(long millis) {clock.now=start.plusMillis(millis);nanos.set(Duration.ofMillis(millis).toNanos());}
    void response(int status,long atMillis,String mutation) {
        doAnswer(call->{
            RequestEnvelope request=call.getArgument(0); String correlation=call.getArgument(1);
            assertThat(call.getArgument(2,LongSupplier.class).getAsLong()).isLessThanOrEqualTo(Duration.ofSeconds(4).toNanos());
            var response=status==200?QueryResponse.exito(request,correlation,json.createObjectNode().put("id",1),clock.instant()):
                    QueryResponse.error(request,correlation,(status==403?QueryBusinessException.prohibido("fixture"):QueryBusinessException.noEncontrado("fixture")).aError(),clock.instant());
            var body=(tools.jackson.databind.node.ObjectNode)json.readTree(codec.escribirRespuesta(response));
            if(mutation!=null) body.put(mutation,mutation.equals("operacion")?"other.v1":UUID.randomUUID().toString());
            advance(atMillis);
            assertThat(registry.completar(correlation,json.writeValueAsBytes(body))).isTrue();
            assertThat(registry.completar(correlation,json.writeValueAsBytes(body))).isFalse();
            return 0L;
        }).when(publisher).publicarConMedicion(any(),anyString(),any(LongSupplier.class));
    }
    @ParameterizedTest @ValueSource(ints={200,403,404})
    void rejectsLateSuccessAndBusinessErrorsThenCannotReviveBudget(int status) {
        response(status,4207,null);
        assertThatThrownBy(()->adapter(Domain.USUARIOS).ejecutar(Domain.USUARIOS,json.createObjectNode(),token,budget))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(registry.enVuelo()).isZero(); advance(0);
        assertThatThrownBy(()->budget.requireRemaining(budget.originalDeadline())).isInstanceOf(QueryTimeoutException.class);
    }
    @ParameterizedTest @ValueSource(ints={200,403,404})
    void acceptsOnlyBeforeVerifiedLimits(int status) {
        response(status,3000,null);
        if(status==200) assertThat(adapter(Domain.USUARIOS).ejecutar(Domain.USUARIOS,json.createObjectNode(),token,budget).status()).isEqualTo(200);
        else assertThatThrownBy(()->adapter(Domain.USUARIOS).ejecutar(Domain.USUARIOS,json.createObjectNode(),token,budget))
                .isInstanceOfSatisfying(QueryBusinessException.class,e->assertThat(e.status().value()).isEqualTo(status));
        assertThat(registry.enVuelo()).isZero();
    }
    @ParameterizedTest @ValueSource(ints={200,403,404})
    void expiryDuringResolutionIsCheckedBeforeReturningOrThrowingBusinessError(int status) {
        response(status,3000,null);
        doAnswer(call->{var parsed=call.callRealMethod();advance(4000);return parsed;}).when(schema).leer(any(),anyInt());
        assertThatThrownBy(()->adapter(Domain.USUARIOS).ejecutar(Domain.USUARIOS,json.createObjectNode(),token,budget))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(registry.enVuelo()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"messageId","correlationId","operacion"})
    void rejectsDiscordantReferences(String mutation) {
        response(200,100,mutation);
        assertThatThrownBy(()->adapter(Domain.USUARIOS).ejecutar(Domain.USUARIOS,json.createObjectNode(),token,budget))
                .isInstanceOf(QueryUnavailableException.class);
        assertThat(registry.enVuelo()).isZero();
    }
    @Test void expiryWhileObtainingPayloadIsCheckedAtFinalReturn() {
        response(200,3000,null);
        doAnswer(call->{
            @SuppressWarnings("unchecked") var parsed=(Optional<QueryResponse>)call.callRealMethod();
            var response=spy(parsed.orElseThrow());
            doAnswer(access->{advance(4000);return access.callRealMethod();}).when(response).payload();
            return Optional.of(response);
        }).when(schema).leer(any(),anyInt());
        assertThatThrownBy(()->adapter(Domain.USUARIOS).ejecutar(Domain.USUARIOS,json.createObjectNode(),token,budget))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(registry.enVuelo()).isZero();
    }
    tools.jackson.databind.JsonNode pagosPayload(String mutation) {
        var proof=new IdentityProof(mutation.equals("tenant")?UUID.randomUUID():tenant,mutation.equals("oid")?UUID.randomUUID():oid,
                42,start,start,start.plusSeconds(4),mutation.equals("deadline")?start.plusSeconds(6):budget.originalDeadline(),UUID.randomUUID(),UUID.randomUUID());
        return json.createObjectNode().put("pagoId",1).put("pruebaIdentidad",mutation.equals("signature")?"bad":FixtureIdentityKeys.sign(proof));
    }
    BffQueryAdapter pagosAdapter() {
        var adapter=adapter(Domain.PAGOS);
        adapter.configurarPruebas(new BffIdentityProofValidator(new IdentityProofVerifier(FixtureIdentityKeys.keys(),clock,Duration.ofMillis(250))));
        return adapter;
    }
    @Test void explicitPagosSubdeadlinePreservesOriginalAndUsesSignedProof() {
        response(200,2900,null);
        var result=pagosAdapter().ejecutar(Domain.PAGOS,pagosPayload("valid"),token,budget,start.plusSeconds(3));
        assertThat(result.status()).isEqualTo(200); assertThat(budget.originalDeadline()).isEqualTo(start.plusSeconds(5));
        verify(publisher).publicarConMedicion(argThat(r->r.expiresAt().equals(start.plusSeconds(3))),anyString(),any(LongSupplier.class));
    }
    @Test void explicitPagosSubdeadlineIsCheckedAfterPublication() {
        response(200,3000,null);
        assertThatThrownBy(()->pagosAdapter().ejecutar(Domain.PAGOS,pagosPayload("valid"),token,budget,start.plusSeconds(3)))
                .isInstanceOf(QueryTimeoutException.class); assertThat(registry.enVuelo()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"tenant","oid","deadline","signature","RTooLate","noVerifier"})
    void rejectsUnverifiedOrIncoherentPagosLimitsBeforePublishing(String mutation) {
        var adapter=mutation.equals("noVerifier")?adapter(Domain.PAGOS):pagosAdapter();
        assertThatThrownBy(()->adapter.ejecutar(Domain.PAGOS,pagosPayload(mutation),token,budget,
                start.plusMillis(mutation.equals("RTooLate")?3751:3000))).isInstanceOf(QueryUnavailableException.class);
        verifyNoInteractions(publisher); assertThat(registry.enVuelo()).isZero();
    }
}
