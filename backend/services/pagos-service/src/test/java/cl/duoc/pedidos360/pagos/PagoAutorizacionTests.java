package cl.duoc.pedidos360.pagos;

import java.util.Set;

import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
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
class PagoAutorizacionTests {

    private static final IdentidadUsuario DUENIO = new IdentidadUsuario(10L, Set.of(Rol.CLIENTE));
    private static final IdentidadUsuario AJENO = new IdentidadUsuario(99L, Set.of(Rol.CLIENTE));
    private static final IdentidadUsuario REPARTIDOR = new IdentidadUsuario(30L, Set.of(Rol.REPARTIDOR));
    private static final IdentidadUsuario ADMIN = new IdentidadUsuario(1L, Set.of(Rol.ADMIN));

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
    void registrarPedidoAjenoSeRechaza() {
        assertThatThrownBy(() -> pagos.registrar(AJENO, "k", request(MetodoPago.TARJETA)))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void adminPuedeRegistrarPedidoDeOtro() {
        var pago = pagos.registrar(ADMIN, "k-admin", request(MetodoPago.TARJETA));
        assertThat(pago.pagoId()).isPositive();
    }

    @Test
    void obtenerPagoAjenoSeRechaza() {
        var pago = pagos.registrar(DUENIO, "k-obtener", request(MetodoPago.TARJETA));

        assertThatThrownBy(() -> pagos.obtener(AJENO, pago.pagoId()))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void obtenerPropioPagoEsPermitido() {
        var pago = pagos.registrar(DUENIO, "k-obtener-2", request(MetodoPago.TARJETA));
        assertThat(pagos.obtener(DUENIO, pago.pagoId()).pagoId()).isEqualTo(pago.pagoId());
    }

    @Test
    void listarPagosDePedidoAjenoSeRechaza() {
        assertThatThrownBy(() -> pagos.listarPorPedido(AJENO, PedidosClientStub.PEDIDO_EXISTENTE))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void aprobarSinPermisoSeRechaza() {
        var pendiente = pagos.registrar(DUENIO, "k-cobro", request(MetodoPago.EFECTIVO));

        assertThatThrownBy(() -> pagos.aprobar(DUENIO, pendiente.pagoId()))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void aprobarConPermisoDeRepartidorSeAcepta() {
        var pendiente = pagos.registrar(DUENIO, "k-cobro-2", request(MetodoPago.EFECTIVO));
        assertThat(pagos.aprobar(REPARTIDOR, pendiente.pagoId()).estado()).isEqualTo("APROBADO");
    }

    private CrearPagoRequest request(MetodoPago metodo) {
        return new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, metodo);
    }

    private HttpStatus status(Throwable error) {
        return ((PagoException) error).getStatus();
    }
}
