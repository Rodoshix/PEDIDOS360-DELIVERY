package cl.duoc.pedidos360.pedidos;

import java.util.List;
import java.util.Set;

import cl.duoc.pedidos360.pedidos.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.pedidos.dto.LineaPedidoRequest;
import cl.duoc.pedidos360.pedidos.entity.EstadoPedido;
import cl.duoc.pedidos360.pedidos.exception.PedidoException;
import cl.duoc.pedidos360.pedidos.exception.PedidoNoEncontradoException;
import cl.duoc.pedidos360.pedidos.repository.PedidoRepository;
import cl.duoc.pedidos360.pedidos.security.IdentidadUsuario;
import cl.duoc.pedidos360.pedidos.security.IdentidadUsuario.Rol;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Semántica de la confirmación de pago por el endpoint interno (issue #47). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration.class)
class PedidoConfirmacionPagoTests {

    private static final IdentidadUsuario CLIENTE = new IdentidadUsuario(10L, Set.of(Rol.CLIENTE));

    @Autowired
    private PedidoService pedidos;

    @Autowired
    private PedidoRepository repositorio;

    @BeforeEach
    void limpiar() {
        repositorio.deleteAll();
    }

    @Test
    void confirmaUnPedidoCreado() {
        var pedido = pedidos.crear(CLIENTE, request());

        pedidos.confirmarPorPago(pedido.pedidoId());

        assertThat(repositorio.findById(pedido.pedidoId()).orElseThrow().getEstado())
                .isEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void esIdempotenteSiYaEstaConfirmadoOEnEstadoPosterior() {
        var pedido = pedidos.crear(CLIENTE, request());
        pedidos.confirmarPorPago(pedido.pedidoId());

        // Repetir no falla y no cambia el estado.
        assertThatCode(() -> pedidos.confirmarPorPago(pedido.pedidoId())).doesNotThrowAnyException();
        assertThat(repositorio.findById(pedido.pedidoId()).orElseThrow().getEstado())
                .isEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void pedidoCanceladoDevuelveConflictoYNoSeConfirma() {
        var pedido = pedidos.crear(CLIENTE, request());
        // ADMIN cancela (única vía de cambio de estado).
        pedidos.cambiarEstado(new IdentidadUsuario(1L, Set.of(Rol.ADMIN)), pedido.pedidoId(), EstadoPedido.CANCELADO);

        assertThatThrownBy(() -> pedidos.confirmarPorPago(pedido.pedidoId()))
                .isInstanceOf(PedidoException.class)
                .satisfies(error -> assertThat(((PedidoException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(repositorio.findById(pedido.pedidoId()).orElseThrow().getEstado())
                .isEqualTo(EstadoPedido.CANCELADO);
    }

    @Test
    void pedidoInexistenteSeReporta() {
        assertThatThrownBy(() -> pedidos.confirmarPorPago(9999L))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }

    private CrearPedidoRequest request() {
        return new CrearPedidoRequest(20L, "Av. Ejemplo 123",
                List.of(new LineaPedidoRequest(101L, 1)));
    }
}
