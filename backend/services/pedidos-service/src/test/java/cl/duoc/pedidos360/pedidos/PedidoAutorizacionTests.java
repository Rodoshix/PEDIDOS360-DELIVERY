package cl.duoc.pedidos360.pedidos;

import java.util.List;
import java.util.Set;

import cl.duoc.pedidos360.pedidos.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.pedidos.dto.LineaPedidoRequest;
import cl.duoc.pedidos360.pedidos.entity.EstadoPedido;
import cl.duoc.pedidos360.pedidos.exception.PedidoException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas de autorización del contrato acordado con I1/I5 (issue #47). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration.class)
class PedidoAutorizacionTests {

    private static final IdentidadUsuario DUENIO = new IdentidadUsuario(10L, Set.of(Rol.CLIENTE));
    private static final IdentidadUsuario AJENO = new IdentidadUsuario(20L, Set.of(Rol.CLIENTE));
    private static final IdentidadUsuario ADMIN = new IdentidadUsuario(1L, Set.of(Rol.ADMIN));

    @Autowired
    private PedidoService pedidos;

    @Autowired
    private PedidoRepository repositorio;

    @BeforeEach
    void limpiar() {
        repositorio.deleteAll();
    }

    @Test
    void crearUsaLaIdentidadAutenticada() {
        var pedido = pedidos.crear(DUENIO, request());

        assertThat(pedido.usuarioId()).isEqualTo(10L);
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.CREADO.name());
    }

    @Test
    void listarPropiosDevuelveSoloLosDeLaIdentidad() {
        pedidos.crear(DUENIO, request());
        pedidos.crear(AJENO, request());

        List<?> propios = pedidos.listarPropios(DUENIO);

        assertThat(propios).hasSize(1);
        assertThat(((cl.duoc.pedidos360.pedidos.dto.PedidoResponse) propios.get(0)).usuarioId()).isEqualTo(10L);
    }

    @Test
    void obtenerPedidoAjenoSeRechaza() {
        var pedido = pedidos.crear(DUENIO, request());

        assertThatThrownBy(() -> pedidos.obtener(AJENO, pedido.pedidoId()))
                .isInstanceOf(PedidoException.class)
                .satisfies(error -> assertThat(((PedidoException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void adminPuedeObtenerPedidoAjeno() {
        var pedido = pedidos.crear(DUENIO, request());
        assertThat(pedidos.obtener(ADMIN, pedido.pedidoId()).pedidoId()).isEqualTo(pedido.pedidoId());
    }

    @Test
    void listarTodosSoloEsParaAdmin() {
        pedidos.crear(DUENIO, request());

        assertThatThrownBy(() -> pedidos.listar(DUENIO))
                .isInstanceOf(PedidoException.class)
                .satisfies(error -> assertThat(((PedidoException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(pedidos.listar(ADMIN)).hasSize(1);
    }

    @Test
    void cambiarEstadoSoloEsParaAdmin() {
        var pedido = pedidos.crear(DUENIO, request());

        assertThatThrownBy(() -> pedidos.cambiarEstado(DUENIO, pedido.pedidoId(), EstadoPedido.CONFIRMADO))
                .isInstanceOf(PedidoException.class)
                .satisfies(error -> assertThat(((PedidoException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(pedidos.cambiarEstado(ADMIN, pedido.pedidoId(), EstadoPedido.CONFIRMADO).estado())
                .isEqualTo(EstadoPedido.CONFIRMADO.name());
    }

    @Test
    void historialDeOtroUsuarioSeRechaza() {
        assertThatThrownBy(() -> pedidos.listarPorUsuario(AJENO, 10L))
                .isInstanceOf(PedidoException.class)
                .satisfies(error -> assertThat(((PedidoException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void laIdentidadLocalNoEsAdminPorDefecto() {
        assertThat(DUENIO.esAdmin()).isFalse();
        assertThat(DUENIO.puedeGestionarPedidos()).isFalse();
        assertThat(ADMIN.puedeGestionarPedidos()).isTrue();
    }

    private CrearPedidoRequest request() {
        return new CrearPedidoRequest(20L, "Av. Ejemplo 123",
                List.of(new LineaPedidoRequest(101L, 1)));
    }
}
