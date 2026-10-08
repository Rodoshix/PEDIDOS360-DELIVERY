package cl.duoc.pedidos360.bff.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cl.duoc.pedidos360.messaging.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.fixture.*;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.relay.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.StringNode;

/** Regresiones de la auditoría: demoras locales inyectadas, sin broker ni espera real. */
class BffIdentityCorrectionTests {
    final BffIdentityProofTests f = new BffIdentityProofTests();
    final BffIdentityProofTests.MutableClock clock = new BffIdentityProofTests.MutableClock(f.now);
    QueryOperationBudget budget() { return f.budget(clock); }
    BffIdentityProofValidator validator() {
        return new BffIdentityProofValidator(new IdentityProofVerifier(FixtureIdentityKeys.keys(),clock,Duration.ofMillis(250)));
    }
    void advance(long millis) { f.nanos.set(Duration.ofMillis(millis).toNanos()); clock.now=f.now.plusMillis(millis); }
    @ParameterizedTest @ValueSource(longs={3749,3750,4001,5001})
    void projectionMustFinishBeforeConservativeAndGlobalLimits(long finish) {
        var b=budget(); var p=f.plan(b); var payload=f.profile(FixtureIdentityKeys.sign(f.proof(p,b)));
        // Se copian solo escalares permitidos; la pausa afecta una copia incluida en la whitelist.
        payload.set("nombre",new StringNode("Ana") {
            @Override public StringNode deepCopy() { advance(finish); return new StringNode("Ana"); }
        });
        advance(3748);
        if (finish==3749) assertThat(validator().validate(payload,p,b).perfil().size()).isEqualTo(8);
        else {
            assertThatThrownBy(()->validator().validate(payload,p,b)).isInstanceOf(QueryTimeoutException.class);
            advance(0);
            assertThatThrownBy(()->b.requireRemaining(b.originalDeadline())).isInstanceOf(QueryTimeoutException.class);
        }
    }
    @ParameterizedTest @ValueSource(longs={3749,3750,4001,5001})
    void exactVerificationBoundaries(long at) {
        var b=budget();var p=f.plan(b);var payload=f.profile(FixtureIdentityKeys.sign(f.proof(p,b)));advance(at);
        if(at==3749) assertThat(validator().validate(payload,p,b).prueba().usuarioId()).isEqualTo(42);
        else assertThatThrownBy(()->validator().validate(payload,p,b)).isInstanceOf(QueryTimeoutException.class);
    }
    @ParameterizedTest @ValueSource(strings={"id","nombre","apellido","email","telefono","activo","creadoEn","actualizadoEn"})
    void everyHttpFieldMustBePresent(String field) {
        var b=budget();var p=f.plan(b);var payload=f.profile(FixtureIdentityKeys.sign(f.proof(p,b)));payload.remove(field);
        assertThatThrownBy(()->validator().validate(payload,p,b)).isInstanceOf(QueryUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(strings={"id","nombre","apellido","email","telefono","activo","creadoEn","actualizadoEn"})
    void everyHttpFieldHasItsActualDtoType(String field) {
        var b=budget();var p=f.plan(b);var payload=f.profile(FixtureIdentityKeys.sign(f.proof(p,b)));payload.putObject(field).put("pruebaIdentidad",payload.path("pruebaIdentidad").stringValue());
        assertThatThrownBy(()->validator().validate(payload,p,b)).isInstanceOf(QueryUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(strings={"extra","nestedProof","invalidTime","offsetTime","nullName","nullCreated","idMismatch","idZero","inactive"})
    void unexpectedDataAndInvalidProfileAreRejected(String mutation) {
        var b=budget();var p=f.plan(b);var payload=f.profile(FixtureIdentityKeys.sign(f.proof(p,b)));
        switch(mutation) {
            case "extra" -> payload.put("secret","unexpected");
            case "nestedProof" -> payload.putObject("extra").put("pruebaIdentidad",payload.path("pruebaIdentidad").stringValue());
            case "invalidTime" -> payload.put("creadoEn","not-an-instant");
            case "offsetTime" -> payload.put("actualizadoEn","2026-10-08T10:00:00+00:00");
            case "nullName" -> payload.putNull("nombre");
            case "nullCreated" -> payload.putNull("creadoEn");
            case "idMismatch" -> payload.put("id",999);
            case "idZero" -> payload.put("id",0);
            case "inactive" -> payload.put("activo",false);
        }
        assertThatThrownBy(()->validator().validate(payload,p,b)).isInstanceOf(QueryUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void validProjectionPreservesEightFieldsPrecisionAndNullablePhone(boolean phonePresent) {
        var b=budget();var p=f.plan(b);var payload=f.profile(FixtureIdentityKeys.sign(f.proof(p,b)));
        if(phonePresent) payload.put("telefono","+56912345678");
        var expected=payload.deepCopy();expected.remove("pruebaIdentidad");
        var result=validator().validate(payload,p,b);
        assertThat(result.perfil()).isEqualTo(expected);assertThat(result.perfil().size()).isEqualTo(8);
        assertThat(result.perfil().has("pruebaIdentidad")).isFalse();
    }
    @ParameterizedTest @ValueSource(longs={3750,5000})
    void observedEffectiveOrGlobalExpiryCannotBeReactivatedOrPlanAnotherQuery(long expiry) {
        var b=budget();var effective=expiry==5000?b.originalDeadline():f.now.plusMillis(expiry);
        clock.now=effective;
        assertThatThrownBy(()->b.requireRemaining(effective)).isInstanceOf(QueryTimeoutException.class);
        clock.now=f.now.minusSeconds(60);
        assertThatThrownBy(()->b.requireRemaining(b.originalDeadline())).isInstanceOf(QueryTimeoutException.class);
        var factory=new RequestFactory(properties(),mock(BffActorContextFactory.class));
        assertThatThrownBy(()->factory.planificar(Domain.PAGOS,"pago.consultar.v1",f.json.createObjectNode(),f.token(f.now.plusSeconds(10)),b,b.originalDeadline())).isInstanceOf(QueryTimeoutException.class);
        assertThatThrownBy(b::claimUsuarios).isInstanceOf(QueryTimeoutException.class);
    }
    @Test void clockChangesWhileLiveKeepOriginalMonotonicSubdeadlineCalculation() {
        var b=budget();advance(3000);clock.now=f.now.minusSeconds(60);
        assertThat(b.requireRemaining(b.originalDeadline())).isEqualTo(Duration.ofSeconds(2).toNanos());
        assertThat(b.requireRemaining(f.now.plusSeconds(4))).isEqualTo(Duration.ofSeconds(1).toNanos());
        clock.now=f.now.plusMillis(3500);
        assertThat(b.requireRemaining(f.now.plusSeconds(4))).isEqualTo(Duration.ofMillis(500).toNanos());
    }
    @Test void terminalStateIsVisibleToConcurrentCallsAfterRollback() throws Exception {
        var b=budget();clock.now=b.originalDeadline();assertThatThrownBy(()->b.requireRemaining(b.originalDeadline())).isInstanceOf(QueryTimeoutException.class);clock.now=f.now;
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts=new ArrayList<Future<Throwable>>();
            for(int i=0;i<32;i++) attempts.add(threads.submit(()->catchThrowable(()->b.requireRemaining(b.originalDeadline()))));
            for(var attempt:attempts) assertThat(attempt.get()).isInstanceOf(QueryTimeoutException.class);
        }
    }
    @ParameterizedTest @ValueSource(longs={3749,3750,4001,5001})
    void adapterChecksAgainAfterSuccessfulValidationBeforeReturning(long finish) {
        var b=budget();var p=f.plan(b);var properties=properties();var factory=mock(RequestFactory.class);when(factory.properties()).thenReturn(properties);
        when(factory.planificar(eq(Domain.USUARIOS),anyString(),any(),any(),same(b),eq(b.originalDeadline()))).thenReturn(p);
        var registry=new PendingCorrelationRegistry(8);var context=new RequestEnvelopeContext();var publisher=mock(RequestPublisher.class);
        var payload=f.profile(FixtureIdentityKeys.sign(f.proof(p,b)));
        doAnswer(c->{registry.completar(p.correlationId(),context.escribirRespuesta(QueryResponse.exito(p.envelope(),p.correlationId(),payload,f.now)));return 0L;})
                .when(publisher).publicarConMedicion(eq(p.envelope()),eq(p.correlationId()),any(LongSupplier.class));
        var checking=validator();var delayed=mock(BffIdentityProofValidator.class);
        when(delayed.validate(any(),same(p),same(b))).thenAnswer(c->{var result=checking.validate(c.getArgument(0),p,b);advance(finish);return result;});
        var adapter=new BffQueryAdapter(factory,publisher,registry,context,new ResponseSchema(),properties);adapter.configurarPruebas(delayed);
        var invoker=mock(QueryInvoker.class);when(invoker.operacion()).thenReturn("usuario.consultar-actual.v1");adapter.registrar(Domain.USUARIOS,invoker);
        if(finish==3749) assertThat(adapter.ejecutar(Domain.USUARIOS,f.json.createObjectNode(),f.token(f.now.plusSeconds(10)),b).payload().size()).isEqualTo(8);
        else assertThatThrownBy(()->adapter.ejecutar(Domain.USUARIOS,f.json.createObjectNode(),f.token(f.now.plusSeconds(10)),b)).isInstanceOf(QueryTimeoutException.class);
        assertThat(registry.enVuelo()).isZero();verify(publisher,times(1)).publicarConMedicion(any(),anyString(),any(LongSupplier.class));
    }
    static MessagingProperties properties() {
        return new MessagingProperties(RelayMode.ACTIVE,RelayMode.Role.BFF,
                new MessagingProperties.Exchanges("p360.queries","p360.retry","p360.dlx"),new MessagingProperties.Queues("p360.bff.respuestas.q"),
                new MessagingProperties.Naming("p360.",".consultas.q",".consultas.retry.1s.q",".consultas.dlq",".retry.1s",".failed"),
                new MessagingProperties.DomainRouting("usuario.consultar-actual.v1","restaurante.listar.v1","producto.listar.v1","pago.consultar.v1","usuario.consultar-actual","restaurante.listar","producto.listar","pago.consultar"),
                Duration.ofSeconds(5),Duration.ofSeconds(4),Duration.ofSeconds(1),Duration.ofSeconds(3),Duration.ofMillis(500),false,1024,262144);
    }
}
