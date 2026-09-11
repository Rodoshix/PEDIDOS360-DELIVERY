package cl.duoc.pedidos360.pagos;

import java.util.List;

import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
import cl.duoc.pedidos360.pagos.entity.EstadoPago;
import cl.duoc.pedidos360.pagos.entity.MetodoPago;
import cl.duoc.pedidos360.pagos.exception.PagoException;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import cl.duoc.pedidos360.pagos.service.PagoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PostgresTestConfiguration.class, PagoServiceTests.StubConfiguration.class})
@Transactional
class PagoServiceTests {

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
        var pago = pagos.registrar(10L, "clave-tarjeta",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThat(pago.pagoId()).isPositive();
        assertThat(pago.estado()).isEqualTo(EstadoPago.APROBADO.name());
        assertThat(pago.monto()).isEqualTo(13980L);
        assertThat(pedidosStub.confirmaciones()).isEqualTo(1);
    }

    @Test
    void efectivoQuedaPendientePeroConfirmaElPedido() {
        var pago = pagos.registrar(10L, "clave-efectivo",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.EFECTIVO));

        assertThat(pago.estado()).isEqualTo(EstadoPago.PENDIENTE.name());
        assertThat(pedidosStub.confirmaciones()).isEqualTo(1);
    }

    @Test
    void mismoClaveIdempotenciaNoDuplicaElPago() {
        var primero = pagos.registrar(10L, "clave-repetida",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));
        var segundo = pagos.registrar(10L, "clave-repetida",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThat(segundo.pagoId()).isEqualTo(primero.pagoId());
        assertThat(repositorio.count()).isEqualTo(1);
    }

    @Test
    void segundoPagoActivoParaElMismoPedidoDevuelveConflicto() {
        pagos.registrar(10L, "clave-a",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThatThrownBy(() -> pagos.registrar(10L, "clave-b",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA)))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void pedidoInexistenteDevuelveNoEncontrado() {
        assertThatThrownBy(() -> pagos.registrar(10L, "clave-x",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_INEXISTENTE, MetodoPago.TARJETA)))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aprobarCobraUnPagoPendiente() {
        var pendiente = pagos.registrar(10L, "clave-efectivo-2",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.EFECTIVO));

        var aprobado = pagos.aprobar(pendiente.pagoId());

        assertThat(aprobado.estado()).isEqualTo(EstadoPago.APROBADO.name());
    }

    @Test
    void listarPorPedidoDevuelveLosPagosDelPedido() {
        pagos.registrar(10L, "clave-listar",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        List<?> lista = pagos.listarPorPedido(PedidosClientStub.PEDIDO_EXISTENTE);

        assertThat(lista).hasSize(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {
        @Bean
        @Primary
        PedidosClientStub pedidosClientStub() {
            return new PedidosClientStub();
        }
    }
}
