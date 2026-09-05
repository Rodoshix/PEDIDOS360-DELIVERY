package cl.duoc.pedidos360.seguimiento;

import java.util.Map;

import cl.duoc.pedidos360.seguimiento.entity.EstadoSeguimiento;
import cl.duoc.pedidos360.seguimiento.entity.SeguimientoEvento;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "seguimiento.identidad-local.roles=CLIENTE")
class SeguimientoClienteApiTests extends SeguimientoApiTestBase {

    @Test
    void consultaYActualizaElSeguimientoDeSuPedido() throws Exception {
        var seguimiento = guardar(500L, EstadoSeguimiento.RECIBIDO);
        eventos.saveAndFlush(new SeguimientoEvento(
                seguimiento.getId(), EstadoSeguimiento.RECIBIDO, "Seguimiento iniciado."));

        assertThat(llamar("GET", "/seguimientos/500", null).statusCode()).isEqualTo(200);
        assertThat(json(llamar("GET", "/seguimientos/500", null)).get("estadoActual").asString())
                .isEqualTo("RECIBIDO");

        var cambio = llamar("PUT", "/seguimientos/500/estado",
                Map.of("estado", "ENTREGADO"));
        assertThat(cambio.statusCode()).isEqualTo(200);
        assertThat(json(cambio).get("estadoActual").asString()).isEqualTo("ENTREGADO");
        assertThat(json(llamar("GET", "/seguimientos/500/historial", null))
                .get("eventos").size()).isEqualTo(2);
    }

    @Test
    void noPuedeListarTodosLosSeguimientos() throws Exception {
        assertThat(llamar("GET", "/seguimientos", null).statusCode()).isEqualTo(403);
    }

    @Test
    void noBypassaLaSeguridadConHeadersFalsos() throws Exception {
        guardar(600L, EstadoSeguimiento.RECIBIDO);
        var headers = Map.of("X-Roles", "ADMIN", "Authorization", "Bearer token-falso");
        assertThat(llamar("GET", "/seguimientos", null, headers).statusCode()).isEqualTo(403);
        assertThat(llamar("GET", "/seguimientos/600", null, headers).statusCode()).isEqualTo(200);
    }

    @Test
    void rechazaJsonRotoTiposIncorrectosYCuerpoVacio() throws Exception {
        for (String body : new String[]{"{", "", "null", "[]", "{\"pedidoId\":{\"valor\":1}}"}) {
            var respuesta = enviarTexto("POST", "/seguimientos", body, "application/json", Map.of());
            assertThat(respuesta.statusCode()).as(body).isEqualTo(400);
        }
    }
}
