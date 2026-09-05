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

    @Test
    void actualizacionRechazadaConservaPerfilEIdentidad() throws Exception {
        var usuario = guardar(TENANT, OBJECT_ID);
        for (String campo : new String[]{"rol", "roles", "tenantId", "entraObjectId", "activo", "id"}) {
            var payload = new HashMap<String, Object>(PERFIL);
            payload.put(campo, "ADMIN");
            assertThat(llamar("PUT", "/usuarios/" + usuario.getId(), payload).statusCode())
                    .as(campo).isEqualTo(400);
        }
        assertThat(llamar("PUT", "/usuarios/" + usuario.getId(), Map.of("nombre", "Cambio"))
                .statusCode()).isEqualTo(400);
        var guardado = usuarios.findById(usuario.getId()).orElseThrow();
        assertThat(guardado.getNombre()).isEqualTo(usuario.getNombre());
        assertThat(guardado.getEmail()).isEqualTo(usuario.getEmail());
        assertThat(guardado.getTenantId()).isEqualTo(TENANT);
        assertThat(guardado.getEntraObjectId()).isEqualTo(OBJECT_ID);
        assertThat(guardado.getVersion()).isEqualTo(usuario.getVersion());
        assertThat(llamar("GET", "/usuarios", null).statusCode()).isEqualTo(403);
    }

    @Test
    void respetaLongitudesMaximasYNoPersisteUnPerfilInvalido() throws Exception {
        var limites = new HashMap<String, String>(PERFIL);
        limites.put("nombre", "a".repeat(100));
        limites.put("apellido", "b".repeat(100));
        limites.put("telefono", "1".repeat(30));
        var creado = llamar("POST", "/usuarios", limites);
        assertThat(creado.statusCode()).isEqualTo(201);
        long id = json(creado).get("id").asLong();

        for (var exceso : Map.of("nombre", "a".repeat(101), "apellido", "b".repeat(101),
                "telefono", "1".repeat(31), "email", "a".repeat(255) + "@example.test").entrySet()) {
            var payload = new HashMap<>(limites);
            payload.put(exceso.getKey(), exceso.getValue());
            var respuesta = llamar("PUT", "/usuarios/" + id, payload);
            assertThat(respuesta.statusCode()).as(exceso.getKey()).isEqualTo(400);
            assertThat(json(respuesta).has("errores")).as(respuesta.body()).isTrue();
            assertThat(json(respuesta).get("errores").has(exceso.getKey())).isTrue();
        }
        var guardado = usuarios.findById(id).orElseThrow();
        assertThat(guardado.getNombre()).hasSize(100);
        assertThat(guardado.getApellido()).hasSize(100);
        assertThat(guardado.getTelefono()).hasSize(30);
        assertThat(guardado.getVersion()).isZero();
    }

    @Test
    void rechazaJsonRotoTiposIncorrectosYCuerpoVacio() throws Exception {
        for (String body : new String[]{"{", "", "null", "[]", "{\"nombre\":{\"valor\":\"Ana\"}}"}) {
            var respuesta = enviarTexto("POST", "/usuarios", body, "application/json", Map.of());
            assertThat(respuesta.statusCode()).as(body).isEqualTo(400);
            assertThat(json(respuesta).get("status").asInt()).isEqualTo(400);
            assertThat(json(respuesta).get("instance").asString()).isEqualTo("/usuarios");
        }
        assertThat(enviarTexto("POST", "/usuarios", mapper.writeValueAsString(PERFIL),
                "text/plain", Map.of()).statusCode()).isEqualTo(415);
        assertThat(usuarios.count()).isZero();
    }

    @Test
    void conflictoNoFiltraDetallesDeLaBaseNiCreaDuplicado() throws Exception {
        guardar(TENANT, OBJECT_ID);
        var respuesta = llamar("POST", "/usuarios", PERFIL);
        assertThat(respuesta.statusCode()).isEqualTo(409);
        assertThat(json(respuesta).get("status").asInt()).isEqualTo(409);
        assertThat(json(respuesta).get("detail").asString()).isNotBlank();
        assertThat(respuesta.body()).doesNotContain("INSERT", "SELECT", "stackTrace", "uk_usuarios");
        assertThat(usuarios.count()).isEqualTo(1);
    }
}
