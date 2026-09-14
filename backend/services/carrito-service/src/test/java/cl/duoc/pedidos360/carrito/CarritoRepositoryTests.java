package cl.duoc.pedidos360.carrito;

import java.util.UUID;

import cl.duoc.pedidos360.carrito.entity.Carrito;
import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration.class)
@Transactional
class CarritoRepositoryTests {

    @Autowired private CarritoRepository carritos;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Carrito nuevo() { return new Carrito(UUID.randomUUID(), UUID.randomUUID()); }

    private Carrito conProducto() {
        var carrito = nuevo();
        carrito.agregarProducto(101L, 20L, "Hamburguesa", 6990L, 2);
        return carritos.saveAndFlush(carrito);
    }

    @Test
    void guardaAgregadoYRecuperaSusLineasOrdenadasPorIdentidad() {
        var carrito = nuevo();
        carrito.agregarProducto(102L, 20L, "Bebida", 1500L, 1);
        carrito.agregarProducto(101L, 20L, "Hamburguesa", 6990L, 2);
        carritos.saveAndFlush(carrito);
        entityManager.clear();
        var guardado = carritos.findByTenantIdAndEntraObjectId(carrito.getTenantId(), carrito.getEntraObjectId()).orElseThrow();
        assertThat(guardado.getId()).isPositive();
        assertThat(guardado.getTotal()).isEqualTo(15480L);
        assertThat(guardado.getLineas()).extracting("productoId").containsExactly(101L, 102L);
        assertThat(guardado.getLineas().getFirst().getId()).isPositive();
        assertThat(guardado.getCreadoEn()).isNotNull();
        assertThat(guardado.getActualizadoEn()).isEqualTo(guardado.getCreadoEn());
        assertThat(guardado.getVersion()).isZero();
        assertThat(guardado.getMoneda()).isEqualTo("CLP");
    }

    @Test
    void admiteCarritoVacioSinRestaurante() {
        var carrito = carritos.saveAndFlush(nuevo());
        entityManager.clear();
        var guardado = carritos.findById(carrito.getId()).orElseThrow();
        assertThat(guardado.getRestauranteId()).isNull();
        assertThat(guardado.getLineas()).isEmpty();
        assertThat(guardado.getTotal()).isZero();
    }

    @Test
    void identidadUnicaYConsultaAisladaPorDirectorioYPropietario() {
        var primero = conProducto();
        var segundo = carritos.saveAndFlush(new Carrito(UUID.randomUUID(), primero.getEntraObjectId()));
        assertThat(segundo.getId()).isNotEqualTo(primero.getId());
        assertThat(carritos.findByTenantIdAndEntraObjectId(primero.getTenantId(), UUID.randomUUID())).isEmpty();
        assertThat(carritos.findByTenantIdAndEntraObjectId(UUID.randomUUID(), primero.getEntraObjectId())).isEmpty();
        assertThatThrownBy(() -> carritos.saveAndFlush(new Carrito(primero.getTenantId(), primero.getEntraObjectId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cambiarSoloCantidadVersionaLaRaizYConservaIdentidad() {
        var carrito = conProducto();
        entityManager.refresh(carrito);
        Long versionAnterior = carrito.getVersion();
        var creacion = carrito.getCreadoEn();
        carrito.cambiarCantidad(101L, 3);
        carritos.flush();
        entityManager.clear();
        var guardado = carritos.findByTenantIdAndEntraObjectId(carrito.getTenantId(), carrito.getEntraObjectId()).orElseThrow();
        assertThat(guardado.getTotal()).isEqualTo(20970L);
        assertThat(guardado.getVersion()).isGreaterThan(versionAnterior);
        assertThat(guardado.getCreadoEn()).isEqualTo(creacion);
        assertThat(guardado.getActualizadoEn()).isAfterOrEqualTo(guardado.getCreadoEn());
    }

    @Test
    void vaciarEliminaHuerfanasPeroConservaCarritoYPropietario() {
        var carrito = conProducto();
        carrito.agregarProducto(102L, 20L, "Bebida", 1000L, 1);
        carritos.flush();
        carrito.quitarProducto(101L);
        carritos.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carrito.lineas_carrito WHERE carrito_id = ?", Integer.class, carrito.getId())).isEqualTo(1);
        carrito.vaciar();
        carritos.flush();
        entityManager.clear();
        var guardado = carritos.findById(carrito.getId()).orElseThrow();
        assertThat(guardado.getRestauranteId()).isNull();
        assertThat(guardado.getLineas()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carrito.lineas_carrito WHERE carrito_id = ?", Integer.class, carrito.getId())).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void copiaObsoletaNoSobrescribeCambiosDeOtraCopia() {
        var primera = conProducto();
        try {
            var segunda = carritos.findByTenantIdAndEntraObjectId(primera.getTenantId(), primera.getEntraObjectId()).orElseThrow();
            primera.cambiarCantidad(101L, 3);
            var actualizada = carritos.saveAndFlush(primera);
            assertThat(actualizada.getVersion()).isGreaterThan(segunda.getVersion());
            segunda.cambiarCantidad(101L, 4);
            assertThatThrownBy(() -> carritos.saveAndFlush(segunda)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            var guardado = carritos.findByTenantIdAndEntraObjectId(primera.getTenantId(), primera.getEntraObjectId()).orElseThrow();
            assertThat(guardado.getLineas().getFirst().getCantidad()).isEqualTo(3);
        } finally {
            carritos.deleteById(primera.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackRevierteRaizYLineasInclusoDespuesDelFlush() {
        var original = conProducto();
        try {
            var transaction = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                var carrito = carritos.findByTenantIdAndEntraObjectId(original.getTenantId(), original.getEntraObjectId()).orElseThrow();
                carrito.cambiarCantidad(101L, 5);
                carrito.agregarProducto(102L, 20L, "Bebida", 1500L, 1);
                carritos.flush();
                throw new IllegalStateException("Fallo de prueba después del flush");
            })).isInstanceOf(IllegalStateException.class);
            var guardado = carritos.findByTenantIdAndEntraObjectId(original.getTenantId(), original.getEntraObjectId()).orElseThrow();
            assertThat(guardado.getVersion()).isEqualTo(original.getVersion());
            assertThat(guardado.getLineas()).hasSize(1);
            assertThat(guardado.getLineas().getFirst().getCantidad()).isEqualTo(2);
            assertThat(guardado.getTotal()).isEqualTo(13980L);
        } finally {
            carritos.deleteById(original.getId());
        }
    }

    @Test
    void flywayAplicaVersionUnoEnEsquemaPropio() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carrito.flyway_schema_history WHERE version = '1' AND success", Integer.class)).isEqualTo(1);
    }

    @Test
    void baseRechazaProductoDuplicadoAunqueSeOmitaElModelo() {
        var carrito = conProducto();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO carrito.lineas_carrito (carrito_id, producto_id, nombre_producto, precio_unitario, cantidad) VALUES (?, 101, 'Duplicado', 100, 1)", carrito.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void baseRechazaCantidadInvalidaAunqueSeOmitaElModelo() {
        var carrito = conProducto();
        assertThatThrownBy(() -> jdbc.update("UPDATE carrito.lineas_carrito SET cantidad = 0 WHERE carrito_id = ?", carrito.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void baseRechazaPrecioInvalidoAunqueSeOmitaElModelo() {
        var carrito = conProducto();
        assertThatThrownBy(() -> jdbc.update("UPDATE carrito.lineas_carrito SET precio_unitario = -1 WHERE carrito_id = ?", carrito.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
