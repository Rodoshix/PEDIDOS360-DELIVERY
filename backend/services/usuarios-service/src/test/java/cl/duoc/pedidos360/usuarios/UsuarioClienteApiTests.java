package cl.duoc.pedidos360.usuarios;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "usuarios.identidad-local.roles=CLIENTE")
class UsuarioClienteApiTests extends UsuarioApiTestBase {

    @Test
    void creaConsultaActualizaYDesactivaSuPerfil() throws Exception {
        assertThat(llamar("GET", "/usuarios/me", null).statusCode()).isEqualTo(404);
        var creado = llamar("POST", "/usuarios", PERFIL);
        assertThat(creado.statusCode()).isEqualTo(201);
        long id = json(creado).get("id").asLong();
        assertThat(creado.headers().firstValue("Location")).contains("/usuarios/" + id);
        assertThat(json(creado).has("entraObjectId")).isFalse();
        assertThat(llamar("POST", "/usuarios", PERFIL).statusCode()).isEqualTo(409);
        assertThat(json(llamar("GET", "/usuarios/me", null)).get("id").asLong()).isEqualTo(id);
        assertThat(llamar("GET", "/usuarios/" + id, null).statusCode()).isEqualTo(200);

        var actualizado = llamar("PUT", "/usuarios/" + id,
                Map.of("nombre", "Ana María", "apellido", "Pérez", "email", " NUEVO@EXAMPLE.TEST "));
        assertThat(actualizado.statusCode()).isEqualTo(200);
        assertThat(json(actualizado).get("email").asString()).isEqualTo("nuevo@example.test");
        assertThat(llamar("DELETE", "/usuarios/" + id, null).statusCode()).isEqualTo(204);
        assertThat(usuarios.findById(id).orElseThrow().isActivo()).isFalse();
        assertThat(llamar("GET", "/usuarios/me", null).statusCode()).isEqualTo(403);
        assertThat(llamar("POST", "/usuarios", PERFIL).statusCode()).isEqualTo(403);
    }

    @Test
    void rechazaCamposInvalidosEIdentidadORolesEnElJson() throws Exception {
        var invalido = llamar("POST", "/usuarios",
                Map.of("nombre", " ", "apellido", "Pérez", "email", "no-es-email"));
        assertThat(invalido.statusCode()).isEqualTo(400);
        assertThat(invalido.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(json(invalido).get("errores").has("nombre")).isTrue();
        for (String campo : new String[]{"rol", "roles", "tenantId", "entraObjectId", "activo", "id"}) {
            var payload = new HashMap<String, Object>(PERFIL);
            payload.put(campo, "ADMIN");
            assertThat(llamar("POST", "/usuarios", payload).statusCode()).as(campo).isEqualTo(400);
        }
        assertThat(usuarios.count()).isZero();
    }

    @Test
    void noAccedeAListaNiPerfilesAjenosAunqueFalsifiqueHeaders() throws Exception {
        var otro = guardar(TENANT, UUID.randomUUID());
        var headers = Map.of("X-User-Id", otro.getId().toString(),
                "X-Roles", "ADMIN", "Authorization", "Bearer token-falso");
        assertThat(llamar("GET", "/usuarios", null, headers).statusCode()).isEqualTo(403);
        assertThat(llamar("GET", "/usuarios/" + otro.getId(), null, headers).statusCode()).isEqualTo(403);
        assertThat(llamar("PUT", "/usuarios/" + otro.getId(), PERFIL, headers).statusCode()).isEqualTo(403);
        assertThat(llamar("DELETE", "/usuarios/" + otro.getId(), null, headers).statusCode()).isEqualTo(403);
        assertThat(usuarios.findById(otro.getId()).orElseThrow().isActivo()).isTrue();
    }

    @Test
    void rechazaIdentificadorYParametrosInvalidos() throws Exception {
        assertThat(llamar("GET", "/usuarios/no-numero", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/usuarios/0", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/usuarios?pagina=-1", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/usuarios?tamanio=101", null).statusCode()).isEqualTo(400);
    }
}
