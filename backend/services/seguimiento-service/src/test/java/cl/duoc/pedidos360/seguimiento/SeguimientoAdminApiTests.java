package cl.duoc.pedidos360.seguimiento;

import java.util.Map;

import cl.duoc.pedidos360.seguimiento.entity.EstadoSeguimiento;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "seguimiento.identidad-local.roles=ADMIN")
class SeguimientoAdminApiTests extends SeguimientoApiTestBase {

    @Test
    void creaConsultaCambiaEstadoYRegistraHistorial() throws Exception {
        var creado = llamar("POST", "/seguimientos",
                Map.of("pedidoId", 100L, "estadoInicial", "RECIBIDO"));
        assertThat(creado.statusCode()).isEqualTo(201);
        assertThat(creado.headers().firstValue("Location")).contains("/seguimientos/100");
        assertThat(json(creado).get("estadoActual").asString()).isEqualTo("RECIBIDO");

        var estado = llamar("GET", "/seguimientos/100", null);
        assertThat(estado.statusCode()).isEqualTo(200);
        assertThat(json(estado).get("estadoActual").asString()).isEqualTo("RECIBIDO");

        var cambio = llamar("PUT", "/seguimientos/100/estado",
                Map.of("estado", "EN_CAMINO", "nota", "En camino"));
        assertThat(cambio.statusCode()).isEqualTo(200);
        assertThat(json(cambio).get("estadoActual").asString()).isEqualTo("EN_CAMINO");

        var historial = llamar("GET", "/seguimientos/100/historial", null);
        assertThat(historial.statusCode()).isEqualTo(200);
        assertThat(json(historial).get("estadoActual").asString()).isEqualTo("EN_CAMINO");
        assertThat(json(historial).get("eventos").size()).isEqualTo(2);
        assertThat(json(historial).get("eventos").get(0).get("estado").asString())
                .isEqualTo("RECIBIDO");
        assertThat(json(historial).get("eventos").get(1).get("estado").asString())
                .isEqualTo("EN_CAMINO");
    }

    @Test
    void listaConPaginacionYSoloSuDirectorio() throws Exception {
        guardar(200L, EstadoSeguimiento.RECIBIDO);
        guardar(201L, EstadoSeguimiento.ENTREGADO);

        var lista = llamar("GET", "/seguimientos?pagina=0&tamanio=1", null);
        assertThat(lista.statusCode()).isEqualTo(200);
        assertThat(json(lista).get("contenido").size()).isEqualTo(1);
        assertThat(json(lista).get("totalElementos").asLong()).isEqualTo(2);
        assertThat(json(lista).get("totalPaginas").asInt()).isEqualTo(2);
    }

    @Test
    void devuelve404ParaPedidoSinSeguimiento() throws Exception {
        assertThat(llamar("GET", "/seguimientos/999", null).statusCode()).isEqualTo(404);
        assertThat(llamar("GET", "/seguimientos/999/historial", null).statusCode()).isEqualTo(404);
        assertThat(llamar("PUT", "/seguimientos/999/estado",
                Map.of("estado", "ENTREGADO")).statusCode()).isEqualTo(404);
    }

    @Test
    void rechazaSeguimientoDuplicadoYEstadoIgual() throws Exception {
        guardar(300L, EstadoSeguimiento.RECIBIDO);
        var duplicado = llamar("POST", "/seguimientos",
                Map.of("pedidoId", 300L, "estadoInicial", "RECIBIDO"));
        assertThat(duplicado.statusCode()).isEqualTo(409);
        assertThat(json(duplicado).get("status").asInt()).isEqualTo(409);

        var igual = llamar("PUT", "/seguimientos/300/estado",
                Map.of("estado", "RECIBIDO"));
        assertThat(igual.statusCode()).isEqualTo(409);
    }

    @Test
    void rechazaCamposInvalidos() throws Exception {
        assertThat(llamar("POST", "/seguimientos",
                Map.of("pedidoId", 0L, "estadoInicial", "RECIBIDO")).statusCode()).isEqualTo(400);
        assertThat(llamar("POST", "/seguimientos",
                Map.of("pedidoId", 400L)).statusCode()).isEqualTo(400);
        assertThat(llamar("POST", "/seguimientos",
                Map.of("pedidoId", 400L, "estadoInicial", "INEXISTENTE")).statusCode()).isEqualTo(400);
        assertThat(llamar("PUT", "/seguimientos/400/estado",
                Map.of("estado", "INEXISTENTE")).statusCode()).isEqualTo(400);
        assertThat(llamar("PUT", "/seguimientos/400/estado",
                Map.of("nota", "x")).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/seguimientos/no-numero", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/seguimientos/-1", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/seguimientos?pagina=-1", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/seguimientos?tamanio=101", null).statusCode()).isEqualTo(400);
    }
}
