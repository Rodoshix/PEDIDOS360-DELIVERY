package cl.duoc.pedidos360.pagos;

import java.util.Set;

import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
import cl.duoc.pedidos360.pagos.entity.EstadoPago;
import cl.duoc.pedidos360.pagos.entity.MetodoPago;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario.Rol;
import cl.duoc.pedidos360.pagos.service.PagoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PostgresTestConfiguration.class, PedidosStubConfiguration.class})
class PagoRecuperacionTests {

    private static final IdentidadUsuario USUARIO = new IdentidadUsuario(10L, Set.of(Rol.CLIENTE));

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
    void confirmacionAplicadaConRespuestaPerdidaSeRecuperaSinDuplicar() {
        pedidosStub.simularPerdidaDeRespuesta(1);

        var pago = pagos.registrar(USUARIO, "clave-rec",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        // El pago queda persistido aunque la confirmación reportó fallo; Pedidos sí lo aplicó.
        var persistido = repositorio.findById(pago.pagoId()).orElseThrow();
        assertThat(pago.estado()).isEqualTo(EstadoPago.APROBADO.name());
        assertThat(persistido.isPedidoConfirmado()).isFalse();
        assertThat(pedidosStub.estaConfirmado(PedidosClientStub.PEDIDO_EXISTENTE)).isTrue();
        assertThat(repositorio.count()).isEqualTo(1);

        // La reconciliación recupera la confirmación de forma idempotente.
        int recuperados = pagos.reconciliarConfirmacionesPendientes();

        assertThat(recuperados).isEqualTo(1);
        assertThat(repositorio.findById(pago.pagoId()).orElseThrow().isPedidoConfirmado()).isTrue();
        assertThat(repositorio.count()).isEqualTo(1);
    }

    @Test
    void reconciliacionEsIdempotenteYNoDuplica() {
        pedidosStub.simularPerdidaDeRespuesta(1);
        var pago = pagos.registrar(USUARIO, "clave-rec-2",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThat(pagos.reconciliarConfirmacionesPendientes()).isEqualTo(1);
        assertThat(pagos.reconciliarConfirmacionesPendientes()).isZero();

        // El reintento con la misma clave devuelve el mismo pago y no se bloquea por una segunda confirmación.
        var reintento = pagos.registrar(USUARIO, "clave-rec-2",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThat(reintento.pagoId()).isEqualTo(pago.pagoId());
        assertThat(repositorio.count()).isEqualTo(1);
    }

    @Test
    void pedidoCanceladoNoQuedaMarcadoComoConfirmado() {
        // El pedido existe pero está CANCELADO: la confirmación es una transición inválida.
        pedidosStub.registrarPedido(PedidosClientStub.PEDIDO_EXISTENTE, 10L, "CANCELADO", 13980L, "CLP");

        var pago = pagos.registrar(USUARIO, "clave-cancelado",
                new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        // El pago se persiste, pero la coordinación queda pendiente (no confirmada).
        var persistido = repositorio.findById(pago.pagoId()).orElseThrow();
        assertThat(persistido.isPedidoConfirmado()).isFalse();

        // La reconciliación no debe marcar como confirmado un pedido cancelado.
        assertThat(pagos.reconciliarConfirmacionesPendientes()).isZero();
        assertThat(repositorio.findById(pago.pagoId()).orElseThrow().isPedidoConfirmado()).isFalse();
        assertThat(pedidosStub.estaConfirmado(PedidosClientStub.PEDIDO_EXISTENTE)).isFalse();
    }
}
