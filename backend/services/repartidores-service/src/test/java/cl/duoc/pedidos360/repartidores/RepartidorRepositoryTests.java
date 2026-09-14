package cl.duoc.pedidos360.repartidores;

import java.util.UUID;

import cl.duoc.pedidos360.repartidores.entity.AsignacionRepartidor;
import cl.duoc.pedidos360.repartidores.entity.EstadoDisponibilidad;
import cl.duoc.pedidos360.repartidores.entity.EstadoAsignacion;
import cl.duoc.pedidos360.repartidores.entity.Repartidor;
import cl.duoc.pedidos360.repartidores.entity.Vehiculo;
import cl.duoc.pedidos360.repartidores.repository.AsignacionRepartidorRepository;
import cl.duoc.pedidos360.repartidores.repository.RepartidorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration.class)
@Transactional
class RepartidorRepositoryTests {

    @Autowired
    private RepartidorRepository repartidores;

    @Autowired
    private AsignacionRepartidorRepository asignaciones;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void guardaYRecuperaRepartidorPorIdentidadExterna() {
        var repartidor = repartidores.saveAndFlush(
                nuevoRepartidor(UUID.randomUUID(), UUID.randomUUID()));
        entityManager.clear();

        var recuperado = repartidores.findByTenantIdAndEntraObjectId(
                repartidor.getTenantId(), repartidor.getEntraObjectId()).orElseThrow();

        assertThat(recuperado.getId()).isPositive();
        assertThat(recuperado.getNombre()).isEqualTo("Repartidor Uno");
        assertThat(recuperado.getVehiculo()).isEqualTo(Vehiculo.MOTO);
        assertThat(recuperado.getEstadoDisponibilidad()).isEqualTo(EstadoDisponibilidad.DISPONIBLE);
        assertThat(recuperado.getCreadoEn()).isNotNull();
        assertThat(recuperado.getActualizadoEn()).isNotNull();
        assertThat(recuperado.getVersion()).isZero();
    }

    @Test
    void rechazaDosRepartidoresParaLaMismaIdentidad() {
        UUID tenant = UUID.randomUUID();
        UUID objectId = UUID.randomUUID();
        repartidores.saveAndFlush(nuevoRepartidor(tenant, objectId));

        assertThatThrownBy(() -> repartidores.saveAndFlush(nuevoRepartidor(tenant, objectId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void actualizaPerfilSinCambiarIdentidadNiEliminarRegistro() {
        var repartidor = repartidores.saveAndFlush(
                nuevoRepartidor(UUID.randomUUID(), UUID.randomUUID()));
        Long versionOriginal = repartidor.getVersion();
        UUID tenantOriginal = repartidor.getTenantId();
        UUID objectOriginal = repartidor.getEntraObjectId();

        repartidor.actualizarPerfil("Repartidor Dos", " +56987654321 ",
                Vehiculo.AUTO, " Centro ");
        repartidor.cambiarDisponibilidad(EstadoDisponibilidad.OCUPADO);
        repartidores.flush();
        entityManager.clear();
        var guardado = repartidores.findById(repartidor.getId()).orElseThrow();

        assertThat(guardado.getNombre()).isEqualTo("Repartidor Dos");
        assertThat(guardado.getTelefono()).isEqualTo("+56987654321");
        assertThat(guardado.getVehiculo()).isEqualTo(Vehiculo.AUTO);
        assertThat(guardado.getZona()).isEqualTo("Centro");
        assertThat(guardado.getEstadoDisponibilidad()).isEqualTo(EstadoDisponibilidad.OCUPADO);
        assertThat(guardado.getTenantId()).isEqualTo(tenantOriginal);
        assertThat(guardado.getEntraObjectId()).isEqualTo(objectOriginal);
        assertThat(guardado.getVersion()).isGreaterThan(versionOriginal);
    }

    @Test
    void guardaYOrdenaAsignacionesYRechazaPedidoDuplicado() {
        var repartidor = repartidores.saveAndFlush(
                nuevoRepartidor(UUID.randomUUID(), UUID.randomUUID()));

        asignaciones.saveAndFlush(new AsignacionRepartidor(
                repartidor.getId(), 100L, "Primer pedido"));
        asignaciones.saveAndFlush(new AsignacionRepartidor(
                repartidor.getId(), 101L, null));
        entityManager.clear();

        var lista = asignaciones.findByRepartidorIdOrderByAsignadaEnAsc(repartidor.getId());
        assertThat(lista).hasSize(2);
        assertThat(lista.get(0).getEstado()).isEqualTo(EstadoAsignacion.ASIGNADA);
        assertThat(lista.get(1).getNota()).isNull();
        assertThat(lista.get(0).getAsignadaEn()).isNotNull();

        assertThatThrownBy(() -> asignaciones.saveAndFlush(
                new AsignacionRepartidor(repartidor.getId(), 100L, "Duplicado")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cambiaEstadoDeAsignacionSinEliminar() {
        var repartidor = repartidores.saveAndFlush(
                nuevoRepartidor(UUID.randomUUID(), UUID.randomUUID()));
        var asignacion = asignaciones.saveAndFlush(
                new AsignacionRepartidor(repartidor.getId(), 200L, null));

        asignacion.cambiarEstado(EstadoAsignacion.EN_CAMINO);
        asignaciones.flush();
        entityManager.clear();
        var guardada = asignaciones.findById(asignacion.getId()).orElseThrow();

        assertThat(guardada.getEstado()).isEqualTo(EstadoAsignacion.EN_CAMINO);
        assertThat(guardada.getRepartidorId()).isEqualTo(repartidor.getId());
        assertThat(guardada.getPedidoId()).isEqualTo(200L);
    }

    @Test
    void ejecutaMigracionInicial() {
        Integer tablas = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_name IN ('repartidores', 'repartidor_asignaciones')",
                Integer.class);
        Integer migraciones = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                Integer.class);

        assertThat(tablas).isEqualTo(2);
        assertThat(migraciones).isEqualTo(1);
    }

    @Test
    void unaEdicionAntiguaNoSobrescribeUnaVersionMasReciente() {
        var primeraCopia = repartidores.saveAndFlush(
                nuevoRepartidor(UUID.randomUUID(), UUID.randomUUID()));
        entityManager.detach(primeraCopia);
        var segundaCopia = repartidores.findById(primeraCopia.getId()).orElseThrow();
        entityManager.detach(segundaCopia);

        primeraCopia.actualizarPerfil("Actualizado", "+561", Vehiculo.BICICLETA, null);
        var guardado = repartidores.saveAndFlush(primeraCopia);
        assertThat(guardado.getVersion()).isGreaterThan(segundaCopia.getVersion());

        segundaCopia.actualizarPerfil("Obsoleto", "+569", Vehiculo.AUTO, "Norte");
        assertThatThrownBy(() -> repartidores.saveAndFlush(segundaCopia))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private Repartidor nuevoRepartidor(UUID tenant, UUID objectId) {
        return new Repartidor(tenant, objectId, " Repartidor Uno ",
                "+56912345678", Vehiculo.MOTO, " Norte ");
    }
}
