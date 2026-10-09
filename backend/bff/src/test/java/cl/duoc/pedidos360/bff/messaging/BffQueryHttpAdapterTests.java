package cl.duoc.pedidos360.bff.messaging;

import cl.duoc.pedidos360.messaging.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.fixture.FixtureIdentityKeys;
import cl.duoc.pedidos360.messaging.relay.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real cryptographic validators and shared adapter; transport/time injection, no broker. */
class BffQueryHttpAdapterTests {
    final BffTemporalBarrierTests f = new BffTemporalBarrierTests();
    final List<RequestEnvelope> sent = new ArrayList<>();
    long afterUsersAt = -1;
    tools.jackson.databind.json.JsonMapper httpJson = f.json;
    BffQueryHttpAdapter facade(String mode) {
        var adapter = spy(f.adapter(Domain.USUARIOS));
        adapter.configurarPruebas(new BffIdentityProofValidator(new IdentityProofVerifier(FixtureIdentityKeys.keys(), f.clock, Duration.ofMillis(250))));
        doReturn(f.budget).when(adapter).iniciarOperacion(f.token);
        doAnswer(call -> { var result = call.callRealMethod(); if (afterUsersAt >= 0) f.advance(afterUsersAt); return result; })
                .when(adapter).ejecutarVerificado(eq(Domain.USUARIOS), any(), eq(f.token), eq(f.budget), any());
        @SuppressWarnings("unchecked") ObjectProvider<BffQueryAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        var env = new MockEnvironment().withProperty("bff.queries.pagos",mode).withProperty("bff.queries.usuarios",mode);
        for (Domain d : Domain.values()) env.setProperty("pedidos360.messaging.routing." + switch(d) {
            case USUARIOS -> "usuario"; case RESTAURANTES -> "restaurante"; case PRODUCTOS -> "producto"; case PAGOS -> "pago";
        }, f.props.routing().operacion(d));
        return new BffQueryHttpAdapter(env, provider, httpJson);
    }
    void answers(String mutation, int status, long payTime) {
        doAnswer(call -> {
            RequestEnvelope r = call.getArgument(0); String corr = call.getArgument(1); sent.add(r);
            tools.jackson.databind.JsonNode payload;
            if (r.operacion().startsWith("usuario")) {
                var proof = new IdentityProof(mutation.equals("tenant") ? UUID.randomUUID() : f.tenant,
                        mutation.equals("oid") ? UUID.randomUUID() : f.oid, mutation.equals("localId") ? 99 : 42,
                        f.start, f.start, f.start.plusSeconds(4), f.budget.originalDeadline(),
                        mutation.equals("requestId") ? UUID.randomUUID() : r.messageId(), UUID.randomUUID());
                var profile = f.json.createObjectNode().put("id",42).put("nombre","Test").put("apellido","User")
                        .put("email","local@example.test").putNull("telefono").put("activo",!mutation.equals("inactive"))
                        .put("creadoEn",f.start.toString()).put("actualizadoEn",f.start.toString());
                if (!mutation.equals("missing")) profile.put("pruebaIdentidad",mutation.equals("signature") ? "invalid" : FixtureIdentityKeys.sign(proof));
                payload = profile;
                f.advance(mutation.equals("expiredUsers") ? 4000 : 500);
            } else {
                assertThat(r.payload().size()).isEqualTo(2);
                var verified = new IdentityProofVerifier(FixtureIdentityKeys.keys(), f.clock, Duration.ofMillis(250))
                        .verify(r.payload().path("pruebaIdentidad").stringValue(), f.tenant, f.oid, sent.getFirst().messageId(), f.budget.originalDeadline());
                assertThat(r.expiresAt()).isEqualTo(verified.expiraEn().minusMillis(250));
                payload = f.json.createObjectNode().put("pagoId",1).put("pedidoId",2).put("usuarioId",42).put("monto",1000)
                        .put("moneda","CLP").put("metodo","EFECTIVO").put("estado","PENDIENTE").put("fecha",f.start.toString());
                if (mutation.equals("extraPay")) ((tools.jackson.databind.node.ObjectNode)payload).put("private","secret");
                f.advance(payTime);
            }
            var response = r.operacion().startsWith("usuario") || status == 200 ? QueryResponse.exito(r,corr,payload,f.clock.instant())
                    : QueryResponse.error(r,corr,(status==403?QueryBusinessException.prohibido("sensitive"):QueryBusinessException.noEncontrado("sensitive")).aError(),f.clock.instant());
            var bytes = f.codec.escribirRespuesta(response);
            assertThat(f.registry.completar(corr,bytes)).isTrue(); assertThat(f.registry.completar(corr,bytes)).isFalse();
            return 0L;
        }).when(f.publisher).publicarConMedicion(any(),anyString(),any(LongSupplier.class));
    }
    @Test void chainPreservesOriginalRequestProofDeadlineAndPublicDto() {
        answers("valid",200,1000); var result=facade("RABBITMQ").consultar("GET","/pagos/1",f.token).orElseThrow();
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().toString()).doesNotContain("pruebaIdentidad","tenantId","entraObjectId");
        assertThat(sent).hasSize(2); assertThat(f.budget.originalDeadline()).isEqualTo(f.start.plusSeconds(5));
        assertThat(f.registry.enVuelo()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"tenant","oid","requestId","localId","signature","missing","inactive","extraPay"})
    void invalidProofOrPublicResultFailsClosed(String mutation) {
        answers(mutation,200,1000); var result=facade("RABBITMQ").consultar("GET","/pagos/1",f.token).orElseThrow();
        assertThat(result.getStatusCode().value()).isEqualTo(502);
        assertThat(sent).hasSize(mutation.equals("extraPay")?2:1); assertThat(f.registry.enVuelo()).isZero();
    }
    @ParameterizedTest @ValueSource(ints={200,403,404})
    void latePaymentsSuccessAndBusinessErrorNeverBecomeHttpResults(int status) {
        answers("valid",status,3750); var result=facade("RABBITMQ").consultar("GET","/pagos/1",f.token).orElseThrow();
        assertThat(result.getStatusCode().value()).isEqualTo(504);
        f.advance(0); assertThatThrownBy(()->f.budget.requireRemaining(f.budget.originalDeadline())).isInstanceOf(QueryTimeoutException.class);
    }
    @Test void usersExpiryStopsChainBeforePayments() {
        answers("expiredUsers",200,1000);
        assertThat(facade("RABBITMQ").consultar("GET","/pagos/1",f.token).orElseThrow().getStatusCode().value()).isEqualTo(504);
        assertThat(sent).hasSize(1);
    }
    @Test void expiryBetweenProofAndPaymentDoesNotPublishAgain() {
        answers("valid",200,1000); afterUsersAt = 3750;
        assertThat(facade("RABBITMQ").consultar("GET","/pagos/1",f.token).orElseThrow().getStatusCode().value()).isEqualTo(504);
        assertThat(sent).hasSize(1);
    }
    @Test void expiryDuringFinalHttpSerializationRejectsSuccessfulPayment() {
        answers("valid",200,1000); httpJson = spy(f.json);
        doAnswer(c -> { var body = c.callRealMethod(); f.advance(3750); return body; }).when(httpJson).writeValueAsString(any());
        assertThat(facade("RABBITMQ").consultar("GET","/pagos/1",f.token).orElseThrow().getStatusCode().value()).isEqualTo(504);
        assertThat(sent).hasSize(2);
    }
    @ParameterizedTest @ValueSource(strings={"/productos","/productos/1","/productos/restaurante/1","/restaurantes/1","/pagos/pedido/1","/carrito"})
    void unmigratedQueriesRemainHttp(String path) {
        assertThat(facade("RABBITMQ").consultar("GET",path,f.token)).isEmpty(); verifyNoInteractions(f.publisher);
    }
    @Test void defaultHttpNeverCallsBrokerAndUnavailableRabbitNeverFallsBack() {
        assertThat(facade("HTTP").consultar("GET","/pagos/1",f.token)).isEmpty(); verifyNoInteractions(f.publisher);
        doThrow(new QueryUnavailableException("uncertain",null)).when(f.publisher).publicarConMedicion(any(),anyString(),any(LongSupplier.class));
        assertThat(facade("RABBITMQ").consultar("GET","/pagos/1",f.token).orElseThrow().getStatusCode().value()).isEqualTo(502);
        verify(f.publisher,times(1)).publicarConMedicion(any(),anyString(),any(LongSupplier.class));
    }
    @Test void incompatibleModesFailAtStartup() {
        @SuppressWarnings("unchecked") ObjectProvider<BffQueryAdapter> provider=mock(ObjectProvider.class);
        assertThatThrownBy(()->new BffQueryHttpAdapter(new MockEnvironment().withProperty("bff.queries.pagos","RABBITMQ"),provider,f.json)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new BffQueryHttpAdapter(new MockEnvironment().withProperty("bff.queries.pagos","TYPO"),provider,f.json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void neitherHttpClientIsInvokedAfterUncertainPublication() {
        doThrow(new QueryUnavailableException("uncertain",null)).when(f.publisher).publicarConMedicion(any(),anyString(),any(LongSupplier.class));
        var http=mock(java.net.http.HttpClient.class); var env=new MockEnvironment(); var selected=facade("RABBITMQ");
        var users=new cl.duoc.pedidos360.bff.client.UsuariosClient(http,env,selected);
        var commerce=new cl.duoc.pedidos360.bff.client.ComercioClient(http,env,selected);
        assertThat(users.call("GET","/usuarios/me",null,f.token).getStatusCode().value()).isEqualTo(502);
        // A separate HTTP operation has its own original budget.
        var other=new BffQueryHttpAdapterTests();
        doThrow(new QueryUnavailableException("uncertain",null)).when(other.f.publisher).publicarConMedicion(any(),anyString(),any(LongSupplier.class));
        commerce=new cl.duoc.pedidos360.bff.client.ComercioClient(http,env,other.facade("RABBITMQ"));
        assertThat(commerce.call("GET","/pagos/1",null,other.f.token).getStatusCode().value()).isEqualTo(502);
        verifyNoInteractions(http);
    }
}
