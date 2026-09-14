package cl.duoc.pedidos360.repartidores;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "repartidores.identidad-local.roles=CLIENTE")
class RepartidorClienteApiTests extends RepartidorApiTestBase {

    @Test
    void creaConsultaActualizaYDesactivaSuPropioPerfil() throws Exception {
        assertThat(llamar("GET", "/repartidores/me", null).statusCode()).isEqualTo(404);

        var creado = llamar("POST", "/repartidores", PERFIL);
        assertThat(creado.statusCode()).isEqualTo(201);
        long id = json(creado).get("id").asLong();
        assertThat(creado.headers().firstValue("Location")).contains("/repartidores/" + id);
        assertThat(json(creado).has("tenantId")).isFalse();
        assertThat(json(creado).has("entraObjectId")).isFalse();

        assertThat(llamar("POST", "/repartidores", PERFIL).statusCode()).isEqualTo(409);
        assertThat(json(llamar("GET", "/repartidores/me", null)).get("id").asLong()).isEqualTo(id);
        assertThat(llamar("GET", "/repartidores/" + id, null).statusCode()).isEqualTo(200);

        var actualizado = llamar("PUT", "/repartidores/" + id,
                Map.of("nombre", "Repartidor Ana", "vehiculo", "BICICLETA"));
        assertThat(actualizado.statusCode()).isEqualTo(200);
        assertThat(json(actualizado).get("nombre").asString()).isEqualTo("Repartidor Ana");

        var disp = llamar("PUT", "/repartidores/" + id + "/disponibilidad",
                Map.of("estado", "EN_PAUSA"));
        assertThat(disp.statusCode()).isEqualTo(200);
        assertThat(json(disp).get("estadoDisponibilidad").asString()).isEqualTo("EN_PAUSA");

        assertThat(llamar("DELETE", "/repartidores/" + id, null).statusCode()).isEqualTo(204);
        assertThat(repartidores.findById(id).orElseThrow().getEstadoDisponibilidad().name())
                .isEqualTo("INACTIVO");
    }

    @Test
    void noPuedeListarNiAccederAPerfilesAjenos() throws Exception {
        var otro = guardar(TENANT, UUID.randomUUID());
        var headers = Map.of("X-Roles", "ADMIN", "Authorization", "Bearer token-falso");

        assertThat(llamar("GET", "/repartidores", null).statusCode()).isEqualTo(403);
        assertThat(llamar("GET", "/repartidores/" + otro.getId(), null).statusCode()).isEqualTo(403);
        assertThat(llamar("PUT", "/repartidores/" + otro.getId(), PERFIL).statusCode()).isEqualTo(403);
        assertThat(llamar("GET", "/repartidores", null, headers).statusCode()).isEqualTo(403);
        assertThat(repartidores.findById(otro.getId()).orElseThrow().getNombre()).isEqualTo("Otra");
    }

    @Test
    void rechazaCamposNoPermitidosEnElJson() throws Exception {
        var payload = new HashMap<String, Object>(PERFIL);
        payload.put("id", 99);
        payload.put("tenantId", "00000000-0000-0000-0000-000000000000");
        payload.put("rol", "ADMIN");
        assertThat(llamar("POST", "/repartidores", payload).statusCode()).isEqualTo(400);
        assertThat(repartidores.count()).isZero();
    }

    @Test
    void rechazaJsonRotoTiposIncorrectosYCuerpoVacio() throws Exception {
        for (String body : new String[]{"{", "", "null", "[]", "{\"nombre\":{\"valor\":\"Ana\"}}"}) {
            var respuesta = enviarTexto("POST", "/repartidores", body, "application/json", Map.of());
            assertThat(respuesta.statusCode()).as(body).isEqualTo(400);
        }
    }
}
