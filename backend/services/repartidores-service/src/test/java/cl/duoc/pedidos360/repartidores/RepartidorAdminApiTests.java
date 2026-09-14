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
        assertThat(llamar("PUT", "/repartidores/1/disponibilidad",
                Map.of("estado", "INEXISTENTE")).statusCode()).isEqualTo(400);
        assertThat(llamar("PUT", "/repartidores/1/disponibilidad", Map.of()).statusCode()).isEqualTo(400);
    }

    @Test
    void recorreFlujoCompletoDeDisponibilidad() throws Exception {
        var repartidor = guardar(TENANT, UUID.randomUUID());
        for (String estado : new String[]{"OCUPADO", "EN_CAMINO", "EN_PAUSA",
                "DESCONECTADO", "INACTIVO", "SUSPENDIDO"}) {
            var cambio = llamar("PUT", "/repartidores/" + repartidor.getId() + "/disponibilidad",
                    Map.of("estado", estado));
            assertThat(cambio.statusCode()).as(estado).isEqualTo(200);
            assertThat(json(cambio).get("estadoDisponibilidad").asString()).isEqualTo(estado);
        }
    }

    @Test
    void rechazaCamposNoPermitidosEnElJson() throws Exception {
        var payload = new java.util.HashMap<String, Object>(Map.of(
                "nombre", "X", "vehiculo", "MOTO"));
        payload.put("id", 99);
        payload.put("version", 99);
        payload.put("rol", "ADMIN");
        assertThat(llamar("POST", "/repartidores", payload).statusCode()).isEqualTo(400);
        assertThat(repartidores.count()).isZero();

        var cambio = new java.util.HashMap<String, Object>(Map.of("estado", "OCUPADO"));
        cambio.put("pedidoId", 999L);
        assertThat(llamar("POST", "/repartidores",
                Map.of("nombre", "Y", "vehiculo", "MOTO")).statusCode()).isEqualTo(201);
        var creado = repartidores.findAll().get(0);
        assertThat(llamar("PUT", "/repartidores/" + creado.getId() + "/disponibilidad",
                cambio).statusCode()).isEqualTo(400);
    }

    @Test
    void conflictoNoFiltraDetallesDeLaBaseNiCreaDuplicado() throws Exception {
        guardar(TENANT, OBJECT_ID);
        var respuesta = llamar("POST", "/repartidores",
                Map.of("nombre", "Dup", "vehiculo", "MOTO"));
        assertThat(respuesta.statusCode()).isEqualTo(409);
        assertThat(json(respuesta).get("status").asInt()).isEqualTo(409);
        assertThat(json(respuesta).get("detail").asString()).isNotBlank();
        assertThat(respuesta.body()).doesNotContain("INSERT", "SELECT", "stackTrace", "uk_repartidores");
        assertThat(repartidores.count()).isEqualTo(1);
    }

    @Test
    void rechazaNotaDemasiadoLargaYAsignacionPersonaInvalida() throws Exception {
        var repartidor = guardar(TENANT, UUID.randomUUID());
        String nota = "a".repeat(501);
        assertThat(llamar("POST", "/repartidores/" + repartidor.getId() + "/asignaciones",
                Map.of("pedidoId", 300L, "nota", nota)).statusCode()).isEqualTo(400);
        assertThat(llamar("POST", "/repartidores/" + repartidor.getId() + "/asignaciones",
                Map.of("pedidoId", 0L)).statusCode()).isEqualTo(400);
        assertThat(asignaciones.count()).isZero();
    }
}
