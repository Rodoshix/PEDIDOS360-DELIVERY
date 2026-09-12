package cl.duoc.pedidos360.pagos;

import java.util.List;
import java.util.Set;

import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
import cl.duoc.pedidos360.pagos.entity.EstadoPago;
import cl.duoc.pedidos360.pagos.entity.MetodoPago;
import cl.duoc.pedidos360.pagos.exception.PagoException;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario.Rol;
import cl.duoc.pedidos360.pagos.service.PagoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PostgresTestConfiguration.class, PedidosStubConfiguration.class})
class PagoServiceTests {

    private static final IdentidadUsuario CLIENTE = new IdentidadUsuario(10L, Set.of(Rol.CLIENTE));
    private static final IdentidadUsuario REPARTIDOR = new IdentidadUsuario(30L, Set.of(Rol.REPARTIDOR));

    @Autowired
    private PagoService pagos;

    @Autowired
    private PagoRepository repositorio;

    @Autowired
    private PedidosClientStub pedidosStub;

    @BeforeEach
    void limpiar() {
        repositorio.deleteAll();
        pedidosStub.reiniciar();
    }

    @Test
    void registraPagoTarjetaAprobadoYConfirmaElPedido() {
        var pago = pagos.registrar(CLIENTE, "clave-tarjeta",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThat(pago.pagoId()).isPositive();
        assertThat(pago.estado()).isEqualTo(EstadoPago.APROBADO.name());
        assertThat(pago.monto()).isEqualTo(13980L);
        assertThat(pedidosStub.confirmaciones()).isEqualTo(1);
        assertThat(repositorio.findById(pago.pagoId()).orElseThrow().isPedidoConfirmado()).isTrue();
    }

    @Test
    void efectivoQuedaPendientePeroConfirmaElPedido() {
        var pago = pagos.registrar(CLIENTE, "clave-efectivo",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.EFECTIVO));

        assertThat(pago.estado()).isEqualTo(EstadoPago.PENDIENTE.name());
        assertThat(pedidosStub.confirmaciones()).isEqualTo(1);
    }

    @Test
    void segundoPagoActivoParaElMismoPedidoDevuelveConflicto() {
        pagos.registrar(CLIENTE, "clave-a",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThatThrownBy(() -> pagos.registrar(CLIENTE, "clave-b",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA)))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void pedidoInexistenteDevuelveNoEncontrado() {
        assertThatThrownBy(() -> pagos.registrar(CLIENTE, "clave-x",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_INEXISTENTE, MetodoPago.TARJETA)))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aprobarCobraUnPagoPendiente() {
        var pendiente = pagos.registrar(CLIENTE, "clave-efectivo-2",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.EFECTIVO));

        var aprobado = pagos.aprobar(REPARTIDOR, pendiente.pagoId());

        assertThat(aprobado.estado()).isEqualTo(EstadoPago.APROBADO.name());
    }

    @Test
    void listarPorPedidoDevuelveLosPagosDelPedido() {
        pagos.registrar(CLIENTE, "clave-listar",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        List<?> lista = pagos.listarPorPedido(CLIENTE, PedidosClientStub.PEDIDO_EXISTENTE);

        assertThat(lista).hasSize(1);
    }
}
