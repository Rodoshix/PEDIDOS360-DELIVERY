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
class PagoIdempotenciaTests {

    private static final long OTRO_PEDIDO = 501L;
    private static final long PEDIDO_DE_OTRO_USUARIO = 600L;

    private static final IdentidadUsuario USUARIO = new IdentidadUsuario(10L, Set.of(Rol.CLIENTE));
    private static final IdentidadUsuario OTRO_USUARIO = new IdentidadUsuario(20L, Set.of(Rol.CLIENTE));

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
        pedidosStub.registrarPedido(OTRO_PEDIDO, 10L, "CREADO", 5000L, "CLP");
        pedidosStub.registrarPedido(PEDIDO_DE_OTRO_USUARIO, 20L, "CREADO", 7777L, "CLP");
    }

    @Test
    void reintentoIdenticoDevuelveElMismoPago() {
        var primero = pagos.registrar(USUARIO, "clave-repetida", request(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));
        var segundo = pagos.registrar(USUARIO, "clave-repetida", request(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThat(segundo.pagoId()).isEqualTo(primero.pagoId());
        assertThat(repositorio.count()).isEqualTo(1);
    }

    @Test
    void mismaClaveConOtroPedidoEsConflicto() {
        pagos.registrar(USUARIO, "clave-pedido", request(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThatThrownBy(() -> pagos.registrar(USUARIO, "clave-pedido", request(OTRO_PEDIDO, MetodoPago.TARJETA)))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void mismaClaveConOtroMetodoEsConflicto() {
        pagos.registrar(USUARIO, "clave-metodo", request(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        assertThatThrownBy(() -> pagos.registrar(USUARIO, "clave-metodo", request(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.EFECTIVO)))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void otraIdentidadConLaMismaClaveNoAccedeAlPagoAjeno() {
        var pagoAjeno = pagos.registrar(USUARIO, "clave-compartida", request(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));

        // Otro usuario con la misma clave y un pedido ajeno: rechazado por pertenencia (no ve datos de otro).
        assertThatThrownBy(() -> pagos.registrar(OTRO_USUARIO, "clave-compartida",
                request(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA)))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        // Con su propio pedido, la misma clave crea su propio pago (alcance por identidad), sin reutilizar el ajeno.
        var pagoPropio = pagos.registrar(OTRO_USUARIO, "clave-compartida",
                request(PEDIDO_DE_OTRO_USUARIO, MetodoPago.TARJETA));

        assertThat(pagoPropio.usuarioId()).isEqualTo(20L);
        assertThat(pagoPropio.pagoId()).isNotEqualTo(pagoAjeno.pagoId());
    }

    private CrearPagoRequest request(long pedidoId, MetodoPago metodo) {
        return new CrearPagoRequest(pedidoId, metodo);
    }
}
