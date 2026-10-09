package cl.duoc.pedidos360.bff.messaging;

import cl.duoc.pedidos360.messaging.Domain;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.relay.*;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual shared parser and BFF, injected publisher/clock. Not a broker E2E. */
class BffCatalogResponseBindingTests {
    final BffTemporalBarrierTests f = new BffTemporalBarrierTests();
    void respond(String changedField, boolean late) {
        doAnswer(call->{
            RequestEnvelope request=call.getArgument(0); String corr=call.getArgument(1);
            var response=QueryResponse.exito(request,corr,f.json.createArrayNode(),f.clock.instant());
            var body=(tools.jackson.databind.node.ObjectNode)f.json.readTree(f.codec.escribirRespuesta(response));
            if(changedField!=null) body.put(changedField,changedField.equals("operacion")?"restaurante.listar.v1":UUID.randomUUID().toString());
            if(late)f.advance(4000);
            var bytes=f.json.writeValueAsBytes(body);
            assertThat(f.registry.completar(corr,bytes)).isTrue(); assertThat(f.registry.completar(corr,bytes)).isFalse();
            return 0L;
        }).when(f.publisher).publicarConMedicion(any(),anyString(),any(LongSupplier.class));
    }
    @ParameterizedTest @EnumSource(value=Domain.class,names={"RESTAURANTES","PRODUCTOS"})
    void boundCatalogArrayAcceptedAndDuplicateDiscarded(Domain domain) {
        respond(null,false);
        var result=f.adapter(domain).ejecutar(domain,f.json.createObjectNode(),f.token,f.budget);
        assertThat(result.payload().isArray()).isTrue(); assertThat(result.payload().isEmpty()).isTrue();
        assertThat(f.registry.enVuelo()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"operacion","messageId","correlationId"})
    void catalogShapeDoesNotAuthorizeDiscordantResponse(String field) {
        respond(field,false);
        assertThatThrownBy(()->f.adapter(Domain.PRODUCTOS).ejecutar(Domain.PRODUCTOS,f.json.createObjectNode(),f.token,f.budget))
                .isInstanceOf(QueryUnavailableException.class); assertThat(f.registry.enVuelo()).isZero();
    }
    @ParameterizedTest @EnumSource(value=Domain.class,names={"USUARIOS","PAGOS"})
    void objectQueriesRejectArrays(Domain domain) {
        respond(null,false);
        var adapter=domain==Domain.PAGOS?f.pagosAdapter():f.adapter(domain);
        var payload=domain==Domain.PAGOS?f.pagosPayload("valid"):f.json.createObjectNode();
        assertThatThrownBy(()->adapter.ejecutar(domain,payload,f.token,f.budget,
                domain==Domain.PAGOS?f.start.plusSeconds(3):f.budget.originalDeadline())).isInstanceOf(QueryUnavailableException.class);
    }
    @ParameterizedTest @EnumSource(value=Domain.class,names={"RESTAURANTES","PRODUCTOS"})
    void lateCatalogArrayDoesNotBecomeFunctionalResult(Domain domain) {
        respond(null,true);
        assertThatThrownBy(()->f.adapter(domain).ejecutar(domain,f.json.createObjectNode(),f.token,f.budget)).isInstanceOf(QueryTimeoutException.class);
        assertThat(f.registry.enVuelo()).isZero();
    }
}
