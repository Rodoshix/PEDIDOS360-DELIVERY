package cl.duoc.pedidos360.pedidos;

import cl.duoc.pedidos360.pedidos.entity.EstadoPedido;
import cl.duoc.pedidos360.pedidos.entity.LineaPedido;
import cl.duoc.pedidos360.pedidos.entity.Pedido;
import cl.duoc.pedidos360.pedidos.exception.TransicionInvalidaException;
import cl.duoc.pedidos360.pedidos.repository.PedidoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration.class)
@Transactional
class PedidoRepositoryTests {

    @Autowired
    private PedidoRepository pedidos;

    @Test
    void persistePedidoConLineasYTotal() {
        Pedido pedido = nuevoPedido();
        pedido.agregarLinea(new LineaPedido(101L, 2, 6990L));

        var guardado = pedidos.saveAndFlush(pedido);

        assertThat(guardado.getId()).isPositive();
        assertThat(guardado.getUsuarioId()).isEqualTo(10L);
        assertThat(guardado.getRestauranteId()).isEqualTo(20L);
        assertThat(guardado.getEstado()).isEqualTo(EstadoPedido.CREADO);
        assertThat(guardado.getTotal()).isEqualTo(13980L);
        assertThat(guardado.getLineas()).hasSize(1);
        assertThat(guardado.getCreadoEn()).isNotNull();
        assertThat(guardado.getActualizadoEn()).isNotNull();
    }

    @Test
    void rechazaTransicionInvalida() {
        Pedido pedido = nuevoPedido();

        assertThatThrownBy(() -> pedido.transicionarA(EstadoPedido.ENTREGADO))
                .isInstanceOf(TransicionInvalidaException.class);
    }

    @Test
    void permiteTransicionValida() {
        Pedido pedido = nuevoPedido();

        pedido.transicionarA(EstadoPedido.CONFIRMADO);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.CONFIRMADO);
    }

    private Pedido nuevoPedido() {
        return new Pedido(10L, 20L, "Av. Ejemplo 123", "CLP");
    }
}
