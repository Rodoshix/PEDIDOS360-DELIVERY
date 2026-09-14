package cl.duoc.pedidos360.carrito;

import java.util.Map;

import cl.duoc.pedidos360.carrito.entity.Carrito;
import cl.duoc.pedidos360.carrito.exception.ApiException;
import cl.duoc.pedidos360.carrito.service.CatalogoProductos;
import cl.duoc.pedidos360.carrito.service.CatalogoProductos.Producto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CarritoCatalogoApiTests extends CarritoApiTestBase {
    @MockitoBean(enforceOverride = true) CatalogoProductos catalogo;

    private void valido() {
        when(catalogo.obtener(101L)).thenReturn(new Producto(101L, 20L, "Plato", 6990L, true));
    }

    @Test
    void catalogoConDatosInvalidosDevuelve503SinCrearCarrito() throws Exception {
        for (var producto : new Producto[] { null,
                new Producto(102L, 20L, "Plato", 1L, true), new Producto(null, 20L, "Plato", 1L, true),
                new Producto(101L, null, "Plato", 1L, true), new Producto(101L, -1L, "Plato", 1L, true),
                new Producto(101L, 20L, null, 1L, true), new Producto(101L, 20L, " ", 1L, true),
                new Producto(101L, 20L, "x".repeat(201), 1L, true),
                new Producto(101L, 20L, "Plato", -1L, true), new Producto(101L, 20L, "Plato", Long.MAX_VALUE, true) }) {
            when(catalogo.obtener(101L)).thenReturn(producto);
            assertThat(agregar(101, 1).statusCode()).isEqualTo(503);
        }
        assertThat(carritos.count()).isZero();
    }

    @Test
    void falloDelCatalogoSeSimplificaSinReintentoNiCambios() throws Exception {
        valido();
        var anterior = json(agregar(101, 1));
        clearInvocations(catalogo);
        when(catalogo.obtener(101L)).thenThrow(new IllegalStateException("FAKE_SECRET_INTERNAL_URL"));
        var response = llamar("PUT", "/carrito/items/101", Map.of("cantidad", 2));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.body()).doesNotContain("FAKE_SECRET_INTERNAL_URL", "IllegalStateException");
        verify(catalogo).obtener(101L);
        verifyNoMoreInteractions(catalogo);
        assertThat(json(llamar("GET", "/carrito", null))).isEqualTo(anterior);
    }

    @Test
    void erroresDeCatalogoNoFiltranSuMensajeInterno() throws Exception {
        when(catalogo.obtener(101L)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "FAKE_PRIVATE_CONTENT"));
        var response = agregar(101, 1);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("FAKE_PRIVATE_CONTENT");
        assertThat(carritos.count()).isZero();
    }

    @Test
    void productoRetiradoONuevoRestauranteNoModificanLaLinea() throws Exception {
        valido();
        var anterior = json(agregar(101, 1));
        when(catalogo.obtener(101L)).thenReturn(new Producto(101L, 20L, "Plato", 8000L, false));
        assertThat(llamar("PUT", "/carrito/items/101", Map.of("cantidad", 2)).statusCode()).isEqualTo(409);
        when(catalogo.obtener(101L)).thenReturn(new Producto(101L, 21L, "Plato", 8000L, true));
        assertThat(llamar("PUT", "/carrito/items/101", Map.of("cantidad", 2)).statusCode()).isEqualTo(409);
        assertThat(json(llamar("GET", "/carrito", null))).isEqualTo(anterior);
    }

    @Test
    void puedeQuitarYVaciarAunqueCatalogoEsteCaido() throws Exception {
        valido();
        agregar(101, 1);
        reset(catalogo);
        assertThat(llamar("DELETE", "/carrito/items/101", null).statusCode()).isEqualTo(204);
        assertThat(llamar("DELETE", "/carrito", null).statusCode()).isEqualTo(204);
        verifyNoInteractions(catalogo);
    }

    @Test
    void cantidadConservaPrecioPeroReagregarActualizaReferencia() throws Exception {
        valido();
        agregar(101, 1);
        when(catalogo.obtener(101L)).thenReturn(new Producto(101L, 20L, "Plato actualizado", 8000L, true));
        var cantidad = llamar("PUT", "/carrito/items/101", Map.of("cantidad", 2));
        assertThat(json(cantidad).get("total").asLong()).isEqualTo(13980L);
        var agregado = agregar(101, 1);
        assertThat(json(agregado).get("total").asLong()).isEqualTo(24000L);
        assertThat(json(agregado).get("items").get(0).get("nombre").asString()).isEqualTo("Plato actualizado");
    }

    @Test
    void limiteDeLineasDevuelve409SinInsertarParcialmente() throws Exception {
        var lleno = new Carrito(TENANT, OBJECT);
        for (long id = 1; id <= 50; id++) lleno.agregarProducto(id, 20L, "Plato", 100L, 1);
        carritos.saveAndFlush(lleno);
        var anterior = json(llamar("GET", "/carrito", null));
        when(catalogo.obtener(101L)).thenReturn(new Producto(101L, 20L, "Extra", 100L, true));
        assertThat(agregar(101, 1).statusCode()).isEqualTo(409);
        assertThat(json(llamar("GET", "/carrito", null))).isEqualTo(anterior);
    }
}
