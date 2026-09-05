package cl.duoc.pedidos360.pedidos;

import cl.duoc.pedidos360.pedidos.entity.EstadoPedido;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EstadoPedidoTests {

    @Test
    void permiteElRecorridoValido() {
        assertThat(EstadoPedido.CREADO.puedeTransicionarA(EstadoPedido.CONFIRMADO)).isTrue();
        assertThat(EstadoPedido.CONFIRMADO.puedeTransicionarA(EstadoPedido.PREPARANDO)).isTrue();
        assertThat(EstadoPedido.PREPARANDO.puedeTransicionarA(EstadoPedido.LISTO)).isTrue();
        assertThat(EstadoPedido.LISTO.puedeTransicionarA(EstadoPedido.EN_REPARTO)).isTrue();
        assertThat(EstadoPedido.EN_REPARTO.puedeTransicionarA(EstadoPedido.ENTREGADO)).isTrue();
    }

    @Test
    void permiteCancelarDesdeEstadosValidos() {
        assertThat(EstadoPedido.CREADO.puedeTransicionarA(EstadoPedido.CANCELADO)).isTrue();
        assertThat(EstadoPedido.CONFIRMADO.puedeTransicionarA(EstadoPedido.CANCELADO)).isTrue();
        assertThat(EstadoPedido.PREPARANDO.puedeTransicionarA(EstadoPedido.CANCELADO)).isTrue();
    }

    @Test
    void rechazaSaltosYEstadosTerminales() {
        assertThat(EstadoPedido.CREADO.puedeTransicionarA(EstadoPedido.ENTREGADO)).isFalse();
        assertThat(EstadoPedido.PREPARANDO.puedeTransicionarA(EstadoPedido.ENTREGADO)).isFalse();
        assertThat(EstadoPedido.ENTREGADO.puedeTransicionarA(EstadoPedido.CANCELADO)).isFalse();
        assertThat(EstadoPedido.CANCELADO.puedeTransicionarA(EstadoPedido.CONFIRMADO)).isFalse();
    }
}
