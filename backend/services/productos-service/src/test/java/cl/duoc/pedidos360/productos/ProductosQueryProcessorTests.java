package cl.duoc.pedidos360.productos;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.productos.dto.ProductoResponse;
import cl.duoc.pedidos360.productos.exception.ProductoNoEncontradoException;
import cl.duoc.pedidos360.productos.messaging.ProductosQueryProcessor;
import cl.duoc.pedidos360.productos.service.ProductoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ProductosQueryProcessorTests {
    final JsonMapper json = JsonMapper.builder().build();
    final ProductoService service = mock(ProductoService.class);
    final ProductosQueryProcessor processor = new ProductosQueryProcessor(service, json);

    RequestEnvelope request(String payload) {
        return RequestEnvelope.crear(UUID.randomUUID(), "producto.listar-disponibles.v1",
                json.readTree(payload), "actor-verificado-por-base", Instant.now(), Instant.now().plusSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"restauranteId\":0}", "{\"restauranteId\":-1}", "{}",
            "{\"restauranteId\":null}", "{\"restauranteId\":\"1\"}", "{\"restauranteId\":1.5}",
            "{\"restauranteId\":9223372036854775808}", "{\"restauranteId\":true}",
            "{\"restauranteId\":1,\"stock\":1}"})
    void invalidPayloadNeverCallsDomain(String payload) {
        assertThatThrownBy(() -> processor.procesar(null, request(payload))).isInstanceOf(EnvelopeException.class);
        verifyNoInteractions(service);
    }

    @Test void reusesExistingMethodAndDtoWithoutChangingFields() {
        var dto = new ProductoResponse(10L, 7L, "Pan", "Integral", new BigDecimal("1200.00"), "Comida", true);
        when(service.listarDisponiblesPorRestaurante(7L)).thenReturn(List.of(dto));
        assertThat(processor.procesar(null, request("{\"restauranteId\":7}")))
                .isEqualTo(json.valueToTree(List.of(dto)));
        verify(service).listarDisponiblesPorRestaurante(7L);
        verifyNoMoreInteractions(service);
    }

    @Test void restaurantWithoutProductsIsEmptySuccess() {
        when(service.listarDisponiblesPorRestaurante(77L)).thenReturn(List.of());
        assertThat(processor.procesar(null, request("{\"restauranteId\":77}")).isEmpty()).isTrue();
    }

    @Test void businessFailurePreservedForBaseHandler() {
        var expected = QueryBusinessException.conflicto("fixture-conflict");
        when(service.listarDisponiblesPorRestaurante(7L)).thenThrow(expected);
        assertThatThrownBy(() -> processor.procesar(null, request("{\"restauranteId\":7}")))
                .isSameAs(expected);
    }

    @Test void technicalFailureRemainsTechnicalForBaseClassifier() {
        var failure = new org.springframework.dao.DataAccessResourceFailureException("test-db-offline");
        when(service.listarDisponiblesPorRestaurante(7L)).thenThrow(failure);
        assertThatThrownBy(() -> processor.procesar(null, request("{\"restauranteId\":7}"))).isSameAs(failure);
    }
}
