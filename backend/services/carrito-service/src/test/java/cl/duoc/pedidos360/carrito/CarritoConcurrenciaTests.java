package cl.duoc.pedidos360.carrito;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cl.duoc.pedidos360.carrito.entity.Carrito;
import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration.class)
class CarritoConcurrenciaTests {
    @Autowired private CarritoRepository carritos;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private void esperar(CyclicBarrier barrera) {
        try { barrera.await(5, TimeUnit.SECONDS); }
        catch (Exception error) { throw new IllegalStateException("No se sincronizaron las transacciones de prueba", error); }
    }

    private String ejecutar(Runnable cambio) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '5s'");
                cambio.run();
            });
            return "guardado";
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException error) {
            return "conflicto";
        }
    }

    @Test
    void dosAltasSimultaneasSoloCreanUnCarritoSinLineasHuerfanas() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        var barrera = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primera = executor.submit(() -> crear(tenant, object, 101L, barrera));
            var segunda = executor.submit(() -> crear(tenant, object, 102L, barrera));
            assertThat(java.util.List.of(primera.get(15, TimeUnit.SECONDS), segunda.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("guardado", "conflicto");
            var carrito = carritos.findByTenantIdAndEntraObjectId(tenant, object).orElseThrow();
            assertThat(carrito.getLineas()).hasSize(1);
            assertThat(carrito.getTotal()).isEqualTo(100L);
        } finally {
            carritos.findByTenantIdAndEntraObjectId(tenant, object).ifPresent(carritos::delete);
        }
    }

    private String crear(UUID tenant, UUID object, Long producto, CyclicBarrier barrera) {
        return ejecutar(() -> {
            assertThat(carritos.findByTenantIdAndEntraObjectId(tenant, object)).isEmpty();
            esperar(barrera);
            var carrito = new Carrito(tenant, object);
            carrito.agregarProducto(producto, 20L, "Prueba", 100L, 1);
            carritos.saveAndFlush(carrito);
        });
    }

    @Test
    void dosCambiosSimultaneosNoPierdenVersionNiDejanLineasDelPerdedor() throws Exception {
        var original = new Carrito(UUID.randomUUID(), UUID.randomUUID());
        original.agregarProducto(101L, 20L, "Original", 100L, 1);
        carritos.saveAndFlush(original);
        var barrera = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primera = executor.submit(() -> agregar(original, 102L, barrera));
            var segunda = executor.submit(() -> agregar(original, 103L, barrera));
            assertThat(java.util.List.of(primera.get(15, TimeUnit.SECONDS), segunda.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("guardado", "conflicto");
            var guardado = carritos.findByTenantIdAndEntraObjectId(original.getTenantId(), original.getEntraObjectId()).orElseThrow();
            assertThat(guardado.getVersion()).isEqualTo(original.getVersion() + 1);
            assertThat(guardado.getLineas()).hasSize(2);
            assertThat(guardado.getTotal()).isEqualTo(300L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM carrito.lineas_carrito WHERE carrito_id = ?", Integer.class, original.getId())).isEqualTo(2);
        } finally {
            carritos.deleteById(original.getId());
        }
    }

    private String agregar(Carrito original, Long producto, CyclicBarrier barrera) {
        return ejecutar(() -> {
            var carrito = carritos.findByTenantIdAndEntraObjectId(original.getTenantId(), original.getEntraObjectId()).orElseThrow();
            esperar(barrera);
            carrito.agregarProducto(producto, 20L, "Nuevo", 200L, 1);
            carritos.flush();
        });
    }
}
