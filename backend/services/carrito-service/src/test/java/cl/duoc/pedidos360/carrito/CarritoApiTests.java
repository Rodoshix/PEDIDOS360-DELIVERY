package cl.duoc.pedidos360.carrito;

import java.util.Map;
import java.util.UUID;

import cl.duoc.pedidos360.carrito.entity.Carrito;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CarritoApiTests extends CarritoApiTestBase {

    @Test
    void consultarVacioNoCreaRegistros() throws Exception {
        var response = llamar("GET", "/carrito", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("id").isNull()).isTrue();
        assertThat(json(response).get("total").asLong()).isZero();
        assertThat(json(response).get("items").size()).isZero();
        assertThat(carritos.count()).isZero();
    }

    @Test
    void recorridoCompletoCalculaEnServidorYConservaVersion() throws Exception {
        var inicial = agregar(101, 2);
        assertThat(inicial.statusCode()).isEqualTo(200);
        assertThat(json(inicial).get("total").asLong()).isEqualTo(13980L);
        assertThat(json(inicial).get("version").asLong()).isZero();
        var repetido = agregar(101, 1);
        assertThat(json(repetido).get("items").size()).isEqualTo(1);
        assertThat(json(repetido).get("total").asLong()).isEqualTo(20970L);
        assertThat(json(repetido).get("version").asLong()).isGreaterThan(0L);
        assertThat(agregar(102, 1).statusCode()).isEqualTo(200);
        var editado = llamar("PUT", "/carrito/items/101", Map.of("cantidad", 1));
        assertThat(editado.statusCode()).isEqualTo(200);
        assertThat(json(editado).get("total").asLong()).isEqualTo(8490L);
        assertThat(json(llamar("GET", "/carrito", null))).isEqualTo(json(editado));
        var quitado = llamar("DELETE", "/carrito/items/101", null);
        assertThat(quitado.statusCode()).isEqualTo(204);
        assertThat(quitado.body()).isEmpty();
        assertThat(json(llamar("GET", "/carrito", null)).get("total").asLong()).isEqualTo(1500L);
        assertThat(llamar("DELETE", "/carrito", null).statusCode()).isEqualTo(204);
        assertThat(json(llamar("GET", "/carrito", null)).get("items").size()).isZero();
        assertThat(agregar(201, 1).statusCode()).isEqualTo(200);
    }

    @Test
    void productoInexistenteONoDisponibleNoCreaCarrito() throws Exception {
        assertThat(agregar(999, 1).statusCode()).isEqualTo(404);
        assertThat(agregar(103, 1).statusCode()).isEqualTo(409);
        assertThat(carritos.count()).isZero();
    }

    @Test
    void conflictoDeRestauranteConservaElCarrito() throws Exception {
        var anterior = json(agregar(101, 1));
        var conflicto = agregar(201, 1);
        assertThat(conflicto.statusCode()).isEqualTo(409);
        assertThat(conflicto.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(json(llamar("GET", "/carrito", null))).isEqualTo(anterior);
    }

    @Test
    void camposDePrecioPropietarioYRolesSeRechazan() throws Exception {
        for (String campo : new String[] { "precio", "precioUnitario", "total", "usuarioId", "tenantId", "roles", "restauranteId" }) {
            var response = enviar("POST", "/carrito/items", "{\"productoId\":101,\"cantidad\":1,\"" + campo + "\":1}", "application/json", Map.of());
            assertThat(response.statusCode()).as(campo).isEqualTo(400);
        }
        assertThat(carritos.count()).isZero();
    }

    @Test
    void validaJsonTiposYCantidadesSinModificarDatos() throws Exception {
        var anterior = json(agregar(101, 1));
        for (String body : new String[] { "{", "{}", "null", "{\"cantidad\":null}", "{\"cantidad\":0}",
                "{\"cantidad\":100}", "{\"cantidad\":-1}", "{\"cantidad\":1.5}", "{\"cantidad\":2147483648}", "{\"cantidad\":2,\"precio\":1}" }) {
            assertThat(enviar("PUT", "/carrito/items/101", body, "application/json", Map.of()).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(enviar("PUT", "/carrito/items/101", "{\"cantidad\":2}", "text/plain", Map.of()).statusCode()).isEqualTo(415);
        assertThat(json(llamar("GET", "/carrito", null))).isEqualTo(anterior);
    }

    @Test
    void acumulacionSobreElLimiteNoCambiaPrecioCantidadNiVersion() throws Exception {
        var anterior = json(agregar(101, 99));
        assertThat(agregar(101, 1).statusCode()).isEqualTo(400);
        assertThat(json(llamar("GET", "/carrito", null))).isEqualTo(anterior);
    }

    @Test
    void lineasInexistentesYParametrosInvalidosTienenErroresControlados() throws Exception {
        assertThat(llamar("PUT", "/carrito/items/101", Map.of("cantidad", 2)).statusCode()).isEqualTo(404);
        assertThat(llamar("DELETE", "/carrito/items/101", null).statusCode()).isEqualTo(404);
        agregar(101, 1);
        assertThat(llamar("PUT", "/carrito/items/201", Map.of("cantidad", 2)).statusCode()).isEqualTo(404);
        for (String id : new String[] { "0", "-1", "abc", "9223372036854775808" }) {
            assertThat(llamar("DELETE", "/carrito/items/" + id, null).statusCode()).as(id).isEqualTo(400);
        }
    }

    @Test
    void vaciarEsIdempotenteInclusoSinCarrito() throws Exception {
        assertThat(llamar("DELETE", "/carrito", null).statusCode()).isEqualTo(204);
        assertThat(carritos.count()).isZero();
        agregar(101, 1);
        assertThat(llamar("DELETE", "/carrito", null).statusCode()).isEqualTo(204);
        var vacio = json(llamar("GET", "/carrito", null));
        assertThat(llamar("DELETE", "/carrito", null).statusCode()).isEqualTo(204);
        assertThat(json(llamar("GET", "/carrito", null))).isEqualTo(vacio);
    }

    @Test
    void cabecerasYParametrosNoPermitenConsultarNiVaciarCarritosAjenos() throws Exception {
        var ajeno = new Carrito(TENANT, UUID.randomUUID());
        ajeno.agregarProducto(201L, 21L, "Ajeno", 8990L, 1);
        carritos.saveAndFlush(ajeno);
        var otroDirectorio = new Carrito(UUID.randomUUID(), OBJECT);
        otroDirectorio.agregarProducto(201L, 21L, "Otro directorio", 8990L, 2);
        carritos.saveAndFlush(otroDirectorio);
        var headers = Map.of("X-User-Id", ajeno.getEntraObjectId().toString(), "X-Roles", "ADMIN", "Authorization", "Bearer token-falso");
        var consulta = enviar("GET", "/carrito?usuarioId=" + ajeno.getId(), null, "application/json", headers);
        assertThat(json(consulta).get("items").size()).isZero();
        assertThat(enviar("DELETE", "/carrito", null, "application/json", headers).statusCode()).isEqualTo(204);
        assertThat(carritos.findByTenantIdAndEntraObjectId(TENANT, ajeno.getEntraObjectId()).orElseThrow().getTotal()).isEqualTo(8990L);
        assertThat(carritos.findByTenantIdAndEntraObjectId(otroDirectorio.getTenantId(), OBJECT).orElseThrow().getTotal()).isEqualTo(17980L);
        assertThat(carritos.findByTenantIdAndEntraObjectId(TENANT, OBJECT)).isEmpty();
    }
}
