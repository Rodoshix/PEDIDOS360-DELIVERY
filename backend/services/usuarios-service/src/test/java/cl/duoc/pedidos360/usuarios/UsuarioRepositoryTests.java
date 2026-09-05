package cl.duoc.pedidos360.usuarios;

import java.util.UUID;

import cl.duoc.pedidos360.usuarios.entity.Usuario;
import cl.duoc.pedidos360.usuarios.repository.UsuarioRepository;
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
class UsuarioRepositoryTests {

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void guardaYRecuperaPerfilPorIdentidadExterna() {
        var usuario = nuevoUsuario(UUID.randomUUID(), UUID.randomUUID());
        usuarios.saveAndFlush(usuario);
        entityManager.clear();

        var recuperado = usuarios.findByTenantIdAndEntraObjectId(
                usuario.getTenantId(), usuario.getEntraObjectId()).orElseThrow();

        assertThat(recuperado.getId()).isPositive();
        assertThat(recuperado.getNombre()).isEqualTo("Ana");
        assertThat(recuperado.getEmail()).isEqualTo("ana@example.test");
        assertThat(recuperado.isActivo()).isTrue();
        assertThat(recuperado.getCreadoEn()).isNotNull();
        assertThat(recuperado.getActualizadoEn()).isNotNull();
        assertThat(recuperado.getVersion()).isZero();
    }

    @Test
    void rechazaDosPerfilesParaLaMismaIdentidad() {
        UUID tenant = UUID.randomUUID();
        UUID objectId = UUID.randomUUID();
        usuarios.saveAndFlush(nuevoUsuario(tenant, objectId));

        assertThatThrownBy(() -> usuarios.saveAndFlush(nuevoUsuario(tenant, objectId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void distingueDirectoriosAunqueCoincidanObjectIdYEmail() {
        UUID objectId = UUID.randomUUID();
        var primero = usuarios.saveAndFlush(nuevoUsuario(UUID.randomUUID(), objectId));
        var segundo = usuarios.saveAndFlush(nuevoUsuario(UUID.randomUUID(), objectId));
        entityManager.clear();

        assertThat(primero.getId()).isNotEqualTo(segundo.getId());
        assertThat(usuarios.findByTenantIdAndEntraObjectId(
                primero.getTenantId(), objectId)).isPresent();
        assertThat(usuarios.findByTenantIdAndEntraObjectId(
                segundo.getTenantId(), objectId)).isPresent();
    }

    @Test
    void actualizaYDesactivaSinCambiarIdentidadNiEliminarPerfil() {
        var usuario = usuarios.saveAndFlush(nuevoUsuario(UUID.randomUUID(), UUID.randomUUID()));
        Long versionOriginal = usuario.getVersion();
        UUID tenantOriginal = usuario.getTenantId();
        UUID objetoOriginal = usuario.getEntraObjectId();

        usuario.actualizarPerfil("Ana María", "Pérez", "nuevo@example.test", " ");
        usuario.desactivar();
        usuarios.flush();
        entityManager.clear();
        var guardado = usuarios.findById(usuario.getId()).orElseThrow();

        assertThat(guardado.getNombre()).isEqualTo("Ana María");
        assertThat(guardado.getEmail()).isEqualTo("nuevo@example.test");
        assertThat(guardado.getTelefono()).isNull();
        assertThat(guardado.isActivo()).isFalse();
        assertThat(guardado.getTenantId()).isEqualTo(tenantOriginal);
        assertThat(guardado.getEntraObjectId()).isEqualTo(objetoOriginal);
        assertThat(guardado.getVersion()).isGreaterThan(versionOriginal);
    }

    @Test
    void ejecutaMigracionEnEsquemaPropio() {
        Integer migraciones = jdbc.queryForObject(
                "SELECT count(*) FROM usuarios.flyway_schema_history WHERE version = '1' AND success",
                Integer.class);
        assertThat(migraciones).isEqualTo(1);
        assertThat(usuarios.findByTenantIdAndEntraObjectId(
                UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void unaEdicionAntiguaNoSobrescribeUnaVersionMasReciente() {
        var primeraCopia = usuarios.saveAndFlush(nuevoUsuario(UUID.randomUUID(), UUID.randomUUID()));
        entityManager.detach(primeraCopia);
        var segundaCopia = usuarios.findById(primeraCopia.getId()).orElseThrow();
        entityManager.detach(segundaCopia);

        primeraCopia.actualizarPerfil("Actualizado", "Pérez", "primero@example.test", null);
        var guardado = usuarios.saveAndFlush(primeraCopia);
        assertThat(guardado.getVersion()).isGreaterThan(segundaCopia.getVersion());
        segundaCopia.actualizarPerfil("Obsoleto", "Pérez", "segundo@example.test", null);

        assertThatThrownBy(() -> usuarios.saveAndFlush(segundaCopia))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private Usuario nuevoUsuario(UUID tenant, UUID objectId) {
        return new Usuario(tenant, objectId, " Ana ", "Pérez", " ANA@EXAMPLE.TEST ", "+56912345678");
    }
}
