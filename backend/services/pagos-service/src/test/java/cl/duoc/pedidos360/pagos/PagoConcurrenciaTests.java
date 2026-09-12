package cl.duoc.pedidos360.pagos;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
import cl.duoc.pedidos360.pagos.dto.PagoResponse;
import cl.duoc.pedidos360.pagos.entity.EstadoPago;
import cl.duoc.pedidos360.pagos.entity.MetodoPago;
import cl.duoc.pedidos360.pagos.entity.Pago;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario.Rol;
import cl.duoc.pedidos360.pagos.service.PagoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PostgresTestConfiguration.class, PedidosStubConfiguration.class})
class PagoConcurrenciaTests {

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
    void dosInsercionesDirectasConcurrentesSoloPermitenUnPagoActivo() throws Exception {
        var barrera = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Boolean> insertar = () -> {
            barrera.await();
            try {
                repositorio.saveAndFlush(new Pago(PedidosClientStub.PEDIDO_EXISTENTE, 10L, 13980L, "CLP",
                        MetodoPago.TARJETA, EstadoPago.APROBADO, "clave-" + UUID.randomUUID()));
                return true;
            } catch (DataIntegrityViolationException error) {
                return false;
            }
        };

        var primero = executor.submit(insertar);
        var segundo = executor.submit(insertar);
        boolean resultado1 = primero.get();
        boolean resultado2 = segundo.get();
        executor.shutdown();

        assertThat(List.of(resultado1, resultado2)).containsExactlyInAnyOrder(true, false);
        assertThat(activos()).isEqualTo(1);
    }

    @Test
    void dosRegistrosConcurrentesDejanUnSoloPagoActivo() throws Exception {
        var barrera = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Object> registrar = () -> {
            barrera.await();
            try {
                return pagos.registrar(USUARIO, "clave-" + UUID.randomUUID(),
                        new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));
            } catch (RuntimeException error) {
                return error;
            }
        };

        var primero = executor.submit(registrar);
        var segundo = executor.submit(registrar);
        Object resultado1 = primero.get();
        Object resultado2 = segundo.get();
        executor.shutdown();

        long exitos = List.of(resultado1, resultado2).stream()
                .filter(resultado -> resultado instanceof PagoResponse).count();
        assertThat(exitos).isEqualTo(1);
        assertThat(activos()).isEqualTo(1);
    }

    @Test
    void reintentoConcurrenteConLaMismaClaveNoDuplica() throws Exception {
        var barrera = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Object> registrar = () -> {
            barrera.await();
            try {
                return pagos.registrar(USUARIO, "clave-unica",
                        new CrearPagoRequest(PedidosClientStub.PEDIDO_EXISTENTE, MetodoPago.TARJETA));
            } catch (RuntimeException error) {
                return error;
            }
        };

        var primero = executor.submit(registrar);
        var segundo = executor.submit(registrar);
        Object resultado1 = primero.get();
        Object resultado2 = segundo.get();
        executor.shutdown();

        assertThat(resultado1).isInstanceOf(PagoResponse.class);
        assertThat(resultado2).isInstanceOf(PagoResponse.class);
        assertThat(((PagoResponse) resultado1).pagoId()).isEqualTo(((PagoResponse) resultado2).pagoId());
        assertThat(repositorio.count()).isEqualTo(1);
    }

    private long activos() {
        return repositorio.findByPedidoId(PedidosClientStub.PEDIDO_EXISTENTE).stream()
                .filter(Pago::estaActivo)
                .count();
    }
}
