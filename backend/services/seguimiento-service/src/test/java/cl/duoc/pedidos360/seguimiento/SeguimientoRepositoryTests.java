package cl.duoc.pedidos360.seguimiento;

import cl.duoc.pedidos360.seguimiento.entity.EstadoSeguimiento;
import cl.duoc.pedidos360.seguimiento.entity.Seguimiento;
import cl.duoc.pedidos360.seguimiento.entity.SeguimientoEvento;
import cl.duoc.pedidos360.seguimiento.repository.SeguimientoEventoRepository;
import cl.duoc.pedidos360.seguimiento.repository.SeguimientoRepository;
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
class SeguimientoRepositoryTests {

    @Autowired
    private SeguimientoRepository seguimientos;

    @Autowired
    private SeguimientoEventoRepository eventos;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void guardaYRecuperaSeguimientoPorPedido() {
        var seguimiento = seguimientos.saveAndFlush(
                new Seguimiento(100L, EstadoSeguimiento.RECIBIDO));
        entityManager.clear();

        var recuperado = seguimientos.findByPedidoId(100L).orElseThrow();

        assertThat(recuperado.getId()).isPositive();
        assertThat(recuperado.getPedidoId()).isEqualTo(100L);
        assertThat(recuperado.getEstadoActual()).isEqualTo(EstadoSeguimiento.RECIBIDO);
        assertThat(recuperado.getCreadoEn()).isNotNull();
        assertThat(recuperado.getActualizadoEn()).isNotNull();
        assertThat(recuperado.getVersion()).isZero();
    }

    @Test
    void rechazaDosSeguimientosParaElMismoPedido() {
        seguimientos.saveAndFlush(new Seguimiento(200L, EstadoSeguimiento.RECIBIDO));

        assertThatThrownBy(() -> seguimientos.saveAndFlush(
                new Seguimiento(200L, EstadoSeguimiento.EN_PREPARACION)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cambiaEstadoSinCambiarPedidoNiEliminarRegistro() {
        var seguimiento = seguimientos.saveAndFlush(
                new Seguimiento(300L, EstadoSeguimiento.RECIBIDO));
        Long versionOriginal = seguimiento.getVersion();
        Long pedidoOriginal = seguimiento.getPedidoId();

        seguimiento.cambiarEstado(EstadoSeguimiento.ENTREGADO);
        seguimientos.flush();
        entityManager.clear();
        var guardado = seguimientos.findById(seguimiento.getId()).orElseThrow();

        assertThat(guardado.getEstadoActual()).isEqualTo(EstadoSeguimiento.ENTREGADO);
        assertThat(guardado.getPedidoId()).isEqualTo(pedidoOriginal);
        assertThat(guardado.getVersion()).isGreaterThan(versionOriginal);
    }

    @Test
    void guardaYOrdenaHistorialDeEventos() {
        var seguimiento = seguimientos.saveAndFlush(
                new Seguimiento(400L, EstadoSeguimiento.RECIBIDO));

        eventos.saveAndFlush(new SeguimientoEvento(
                seguimiento.getId(), EstadoSeguimiento.RECIBIDO, "Pedido recibido"));
        eventos.saveAndFlush(new SeguimientoEvento(
                seguimiento.getId(), EstadoSeguimiento.EN_CAMINO, null));
        entityManager.clear();

        var historial = eventos.findBySeguimientoIdOrderByOcurridoEnAsc(seguimiento.getId());

        assertThat(historial).hasSize(2);
        assertThat(historial.get(0).getEstado()).isEqualTo(EstadoSeguimiento.RECIBIDO);
        assertThat(historial.get(1).getEstado()).isEqualTo(EstadoSeguimiento.EN_CAMINO);
        assertThat(historial.get(1).getNota()).isNull();
        assertThat(historial.get(0).getOcurridoEn()).isNotNull();
    }

    @Test
    void ejecutaMigracionInicial() {
        Integer tablas = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_name IN ('seguimientos', 'seguimiento_eventos')",
                Integer.class);
        Integer migraciones = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                Integer.class);

        assertThat(tablas).isEqualTo(2);
        assertThat(migraciones).isEqualTo(1);
    }

    @Test
    void unaEdicionAntiguaNoSobrescribeUnaVersionMasReciente() {
        var primeraCopia = seguimientos.saveAndFlush(
                new Seguimiento(500L, EstadoSeguimiento.RECIBIDO));
        entityManager.detach(primeraCopia);
        var segundaCopia = seguimientos.findById(primeraCopia.getId()).orElseThrow();
        entityManager.detach(segundaCopia);

        primeraCopia.cambiarEstado(EstadoSeguimiento.EN_PREPARACION);
        var guardado = seguimientos.saveAndFlush(primeraCopia);
        assertThat(guardado.getVersion()).isGreaterThan(segundaCopia.getVersion());

        segundaCopia.cambiarEstado(EstadoSeguimiento.CANCELADO);
        assertThatThrownBy(() -> seguimientos.saveAndFlush(segundaCopia))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
