package cl.duoc.pedidos360.carrito;

import java.util.NoSuchElementException;
import java.util.UUID;

import cl.duoc.pedidos360.carrito.entity.Carrito;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarritoTests {

    private Carrito nuevo() { return new Carrito(UUID.randomUUID(), UUID.randomUUID()); }

    @Test
    void vacioTieneTotalCeroSinRestaurante() {
        var carrito = nuevo();
        assertThat(carrito.getLineas()).isEmpty();
        assertThat(carrito.getRestauranteId()).isNull();
        assertThat(carrito.getTotal()).isZero();
        assertThat(carrito.getMoneda()).isEqualTo("CLP");
    }

    @Test
    void sumaProductosYActualizaReferenciaSinDuplicarLinea() {
        var carrito = nuevo();
        carrito.agregarProducto(101L, 20L, " Hamburguesa ", 6990L, 2);
        carrito.agregarProducto(101L, 20L, "Hamburguesa nueva", 7000L, 1);
        assertThat(carrito.getLineas()).hasSize(1);
        assertThat(carrito.getLineas().getFirst().getCantidad()).isEqualTo(3);
        assertThat(carrito.getLineas().getFirst().getNombreProducto()).isEqualTo("Hamburguesa nueva");
        assertThat(carrito.getTotal()).isEqualTo(21000L);
    }

    @Test
    void noMezclaRestaurantesNiModificaElContenidoAlRechazar() {
        var carrito = nuevo();
        carrito.agregarProducto(101L, 20L, "Plato", 6990L, 1);
        assertThatThrownBy(() -> carrito.agregarProducto(102L, 21L, "Otro", 8000L, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(carrito.getLineas()).hasSize(1);
        assertThat(carrito.getRestauranteId()).isEqualTo(20L);
        assertThat(carrito.getTotal()).isEqualTo(6990L);
    }

    @Test
    void validaLimitesAntesDeModificarLineaExistente() {
        var carrito = nuevo();
        carrito.agregarProducto(101L, 20L, "Plato", 6990L, 99);
        assertThatThrownBy(() -> carrito.agregarProducto(101L, 20L, "Cambio", 1L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        for (int cantidad : new int[] { 0, -1, 100, Integer.MAX_VALUE }) {
            assertThatThrownBy(() -> carrito.cambiarCantidad(101L, cantidad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(carrito.getLineas().getFirst().getCantidad()).isEqualTo(99);
        assertThat(carrito.getLineas().getFirst().getNombreProducto()).isEqualTo("Plato");
        assertThat(carrito.getTotal()).isEqualTo(6990L * 99);
    }

    @Test
    void limitaLineasYCalculaElMaximoSinDesbordamiento() {
        var carrito = nuevo();
        for (long id = 1; id <= Carrito.MAX_LINEAS; id++) {
            carrito.agregarProducto(id, 20L, "Plato", Carrito.MAX_PRECIO, Carrito.MAX_CANTIDAD);
        }
        assertThat(carrito.getTotal()).isEqualTo(4_950_000_000_000L);
        assertThatThrownBy(() -> carrito.agregarProducto(51L, 20L, "Extra", 1L, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(carrito.getLineas()).hasSize(50);
    }

    @Test
    void rechazaProductosInvalidosSinAsignarRestaurante() {
        var carrito = nuevo();
        assertThatThrownBy(() -> carrito.agregarProducto(0L, 20L, "Plato", 1L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> carrito.agregarProducto(1L, null, "Plato", 1L, 1)).isInstanceOf(IllegalArgumentException.class);
        for (long precio : new long[] { -1, Carrito.MAX_PRECIO + 1, Long.MAX_VALUE }) {
            assertThatThrownBy(() -> carrito.agregarProducto(1L, 20L, "Plato", precio, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String nombre : new String[] { null, " ", "x".repeat(201) }) {
            assertThatThrownBy(() -> carrito.agregarProducto(1L, 20L, nombre, 1L, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(carrito.getRestauranteId()).isNull();
        assertThat(carrito.getLineas()).isEmpty();
    }

    @Test
    void quitarUltimaLineaPermiteOtroRestauranteYVaciarEsIdempotente() {
        var carrito = nuevo();
        carrito.agregarProducto(1L, 20L, "Plato", 6990L, 1);
        carrito.quitarProducto(1L);
        assertThat(carrito.getRestauranteId()).isNull();
        carrito.agregarProducto(2L, 21L, "Otro", 0L, 2);
        assertThat(carrito.getRestauranteId()).isEqualTo(21L);
        carrito.vaciar();
        carrito.vaciar();
        assertThat(carrito.getLineas()).isEmpty();
        assertThat(carrito.getTotal()).isZero();
        assertThat(carrito.getRestauranteId()).isNull();
    }

    @Test
    void noPermiteModificarListaFueraDelAgregadoNiLineasInexistentes() {
        var carrito = nuevo();
        assertThatThrownBy(() -> carrito.getLineas().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> carrito.cambiarCantidad(1L, 2)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> carrito.quitarProducto(1L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> new Carrito(null, UUID.randomUUID())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Carrito(UUID.randomUUID(), null)).isInstanceOf(NullPointerException.class);
    }
}
