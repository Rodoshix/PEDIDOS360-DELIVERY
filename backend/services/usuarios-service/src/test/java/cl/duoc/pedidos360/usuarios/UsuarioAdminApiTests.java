package cl.duoc.pedidos360.usuarios;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "usuarios.identidad-local.roles=ADMIN")
class UsuarioAdminApiTests extends UsuarioApiTestBase {

    @Test
    void listaSoloSuDirectorioYRespetaLaPaginacion() throws Exception {
        guardar(TENANT, UUID.randomUUID());
        guardar(TENANT, UUID.randomUUID());
        var externo = guardar(UUID.randomUUID(), UUID.randomUUID());

        var lista = llamar("GET", "/usuarios?pagina=0&tamanio=1", null);
        assertThat(lista.statusCode()).isEqualTo(200);
        assertThat(json(lista).get("contenido").size()).isEqualTo(1);
        assertThat(json(lista).get("totalElementos").asLong()).isEqualTo(2);
        assertThat(json(lista).get("totalPaginas").asInt()).isEqualTo(2);
        assertThat(llamar("GET", "/usuarios/" + externo.getId(), null).statusCode()).isEqualTo(404);
        assertThat(llamar("PUT", "/usuarios/" + externo.getId(), PERFIL).statusCode()).isEqualTo(404);
        assertThat(llamar("DELETE", "/usuarios/" + externo.getId(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void administraPerfilDelMismoDirectorioYDevuelve404SiNoExiste() throws Exception {
        var otro = guardar(TENANT, UUID.randomUUID());
        assertThat(llamar("PUT", "/usuarios/" + otro.getId(), PERFIL).statusCode()).isEqualTo(200);
        assertThat(llamar("DELETE", "/usuarios/" + otro.getId(), null).statusCode()).isEqualTo(204);
        assertThat(llamar("DELETE", "/usuarios/" + otro.getId(), null).statusCode()).isEqualTo(204);
        assertThat(llamar("PUT", "/usuarios/" + otro.getId(), PERFIL).statusCode()).isEqualTo(409);
        assertThat(llamar("GET", "/usuarios/9223372036854775807", null).statusCode()).isEqualTo(404);
    }

    @Test
    void adminDesactivadoPierdeAccesoSinImportarSuRol() throws Exception {
        var admin = guardar(TENANT, OBJECT_ID);
        admin.desactivar();
        usuarios.saveAndFlush(admin);
        var otro = guardar(TENANT, UUID.randomUUID());

        assertThat(llamar("GET", "/usuarios", null).statusCode()).isEqualTo(403);
        assertThat(llamar("GET", "/usuarios/me", null).statusCode()).isEqualTo(403);
        assertThat(llamar("GET", "/usuarios/" + otro.getId(), null).statusCode()).isEqualTo(403);
        assertThat(llamar("PUT", "/usuarios/" + otro.getId(), PERFIL).statusCode()).isEqualTo(403);
        assertThat(llamar("DELETE", "/usuarios/" + otro.getId(), null).statusCode()).isEqualTo(403);
        assertThat(llamar("POST", "/usuarios", PERFIL).statusCode()).isEqualTo(403);
        assertThat(usuarios.findById(otro.getId()).orElseThrow().isActivo()).isTrue();
    }

    @Test
    void devuelvePaginaVaciaY404SinCambiarOtrosPerfiles() throws Exception {
        var vacia = llamar("GET", "/usuarios", null);
        assertThat(vacia.statusCode()).isEqualTo(200);
        assertThat(json(vacia).get("contenido").isEmpty()).isTrue();
        assertThat(json(vacia).get("totalElementos").asLong()).isZero();
        var existente = guardar(TENANT, UUID.randomUUID());
        String ausente = "/usuarios/9223372036854775807";
        assertThat(llamar("PUT", ausente, PERFIL).statusCode()).isEqualTo(404);
        assertThat(llamar("DELETE", ausente, null).statusCode()).isEqualTo(404);
        var fueraDeRango = llamar("GET", "/usuarios?pagina=100&tamanio=1", null);
        assertThat(fueraDeRango.statusCode()).isEqualTo(200);
        assertThat(json(fueraDeRango).get("contenido").isEmpty()).isTrue();
        assertThat(json(fueraDeRango).get("totalElementos").asLong()).isEqualTo(1);
        assertThat(usuarios.findById(existente.getId()).orElseThrow().isActivo()).isTrue();
    }
}
