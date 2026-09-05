package cl.duoc.pedidos360.repartidores;

import java.util.Map;
import java.util.UUID;

import cl.duoc.pedidos360.repartidores.entity.EstadoDisponibilidad;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "repartidores.identidad-local.roles=ADMIN")
class RepartidorAdminApiTests extends RepartidorApiTestBase {

    @Test
    void listaSoloSuDirectorioYRespetaLaPaginacion() throws Exception {
        guardar(TENANT, UUID.randomUUID());
        guardar(TENANT, UUID.randomUUID());
        var externo = guardar(UUID.randomUUID(), UUID.randomUUID());

        var lista = llamar("GET", "/repartidores?pagina=0&tamanio=1", null);
        assertThat(lista.statusCode()).isEqualTo(200);
        assertThat(json(lista).get("contenido").size()).isEqualTo(1);
        assertThat(json(lista).get("totalElementos").asLong()).isEqualTo(2);
        assertThat(json(lista).get("totalPaginas").asInt()).isEqualTo(2);
        assertThat(llamar("GET", "/repartidores/" + externo.getId(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void administraPerfilDelMismoDirectorio() throws Exception {
        var otro = guardar(TENANT, UUID.randomUUID());

        var actualizado = llamar("PUT", "/repartidores/" + otro.getId(),
                Map.of("nombre", "Repartidor Admin", "vehiculo", "AUTO", "zona", "Centro"));
        assertThat(actualizado.statusCode()).isEqualTo(200);
        assertThat(json(actualizado).get("nombre").asString()).isEqualTo("Repartidor Admin");

        var disp = llamar("PUT", "/repartidores/" + otro.getId() + "/disponibilidad",
                Map.of("estado", "OCUPADO"));
        assertThat(disp.statusCode()).isEqualTo(200);
        assertThat(json(disp).get("estadoDisponibilidad").asString()).isEqualTo("OCUPADO");

        assertThat(llamar("DELETE", "/repartidores/" + otro.getId(), null).statusCode()).isEqualTo(204);
        assertThat(repartidores.findById(otro.getId()).orElseThrow()
                .getEstadoDisponibilidad()).isEqualTo(EstadoDisponibilidad.INACTIVO);
    }

    @Test
    void asignaPedidoYCambiaSuEstado() throws Exception {
        var repartidor = guardar(TENANT, UUID.randomUUID());

        var creada = llamar("POST", "/repartidores/" + repartidor.getId() + "/asignaciones",
                Map.of("pedidoId", 100L, "nota", "Primer pedido"));
        assertThat(creada.statusCode()).isEqualTo(201);
        assertThat(json(creada).get("estado").asString()).isEqualTo("ASIGNADA");

        var estado = llamar("PUT", "/repartidores/" + repartidor.getId() + "/asignaciones/100/estado",
                Map.of("estado", "EN_CAMINO"));
        assertThat(estado.statusCode()).isEqualTo(200);
        assertThat(json(estado).get("estado").asString()).isEqualTo("EN_CAMINO");

        var lista = llamar("GET", "/repartidores/" + repartidor.getId() + "/asignaciones", null);
        assertThat(lista.statusCode()).isEqualTo(200);
        assertThat(json(lista).size()).isEqualTo(1);
    }

    @Test
    void devuelve404ParaRepartidorInexistenteYPedidoSinAsignar() throws Exception {
        assertThat(llamar("GET", "/repartidores/999", null).statusCode()).isEqualTo(404);
        assertThat(llamar("PUT", "/repartidores/999/disponibilidad",
                Map.of("estado", "OCUPADO")).statusCode()).isEqualTo(404);
    }

    @Test
    void rechazaAsignacionDuplicadaYEstadoIgual() throws Exception {
        var repartidor = guardar(TENANT, UUID.randomUUID());
        assertThat(llamar("POST", "/repartidores/" + repartidor.getId() + "/asignaciones",
                Map.of("pedidoId", 200L)).statusCode()).isEqualTo(201);
        assertThat(llamar("POST", "/repartidores/" + repartidor.getId() + "/asignaciones",
                Map.of("pedidoId", 200L)).statusCode()).isEqualTo(409);
        assertThat(llamar("PUT", "/repartidores/" + repartidor.getId() + "/asignaciones/200/estado",
                Map.of("estado", "ASIGNADA")).statusCode()).isEqualTo(409);
    }

    @Test
    void rechazaCamposInvalidos() throws Exception {
        assertThat(llamar("POST", "/repartidores",
                Map.of("nombre", " ", "vehiculo", "MOTO")).statusCode()).isEqualTo(400);
        assertThat(llamar("POST", "/repartidores",
                Map.of("nombre", "X", "vehiculo", "TRACTOR")).statusCode()).isEqualTo(400);
        assertThat(llamar("POST", "/repartidores",
                Map.of("vehiculo", "MOTO")).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/repartidores/no-numero", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/repartidores/-1", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/repartidores?pagina=-1", null).statusCode()).isEqualTo(400);
        assertThat(llamar("GET", "/repartidores?tamanio=101", null).statusCode()).isEqualTo(400);
    }
}
