package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.client.ComercioClient;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Solo se propaga el Bearer validado. La autorización por perfil y la pertenencia
 * del recurso se revalidan en repartidores-service, que es la fuente de verdad.
 */
@RestController
public class RepartidoresController {
    private static final java.util.Set<String> VEHICULOS = java.util.Set.of("MOTO", "BICICLETA", "AUTO");
    private static final java.util.Set<String> DISPONIBILIDAD = java.util.Set.of(
            "DISPONIBLE", "OCUPADO", "EN_CAMINO", "EN_PAUSA", "DESCONECTADO", "INACTIVO", "SUSPENDIDO");
    private static final java.util.Set<String> ASIGNACION = java.util.Set.of(
            "ASIGNADA", "EN_CAMINO", "ENTREGADA", "CANCELADA");

    private final ComercioClient client;
    public RepartidoresController(ComercioClient client) { this.client = client; }

    @GetMapping("/repartidores")
    public ResponseEntity<?> listar(@RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanio, JwtAuthenticationToken token) {
        check(pagina >= 0 && tamanio >= 1 && tamanio <= 100);
        return client.call("GET", "/repartidores?pagina=" + pagina + "&tamanio=" + tamanio, null, token);
    }
    @GetMapping("/repartidores/me")
    public ResponseEntity<?> actual(JwtAuthenticationToken token) {
        return client.call("GET", "/repartidores/me", null, token);
    }
    @GetMapping("/repartidores/{id}")
    public ResponseEntity<?> obtener(@PathVariable long id, JwtAuthenticationToken token) {
        return client.call("GET", "/repartidores/" + positive(id), null, token);
    }
    @PostMapping(value = "/repartidores", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> crear(@RequestBody tools.jackson.databind.JsonNode body, JwtAuthenticationToken token) {
        check(perfilValido(body));
        return client.call("POST", "/repartidores", body.toString(), token);
    }
    @PutMapping(value = "/repartidores/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> actualizar(@PathVariable long id, @RequestBody tools.jackson.databind.JsonNode body,
            JwtAuthenticationToken token) {
        positive(id);
        check(perfilValido(body));
        return client.call("PUT", "/repartidores/" + id, body.toString(), token);
    }
    @PutMapping(value = "/repartidores/{id}/disponibilidad", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> disponibilidad(@PathVariable long id, @RequestBody tools.jackson.databind.JsonNode body,
            JwtAuthenticationToken token) {
        positive(id);
        check(estadoUnico(body, DISPONIBILIDAD));
        return client.call("PUT", "/repartidores/" + id + "/disponibilidad", body.toString(), token);
    }
    @DeleteMapping("/repartidores/{id}")
    public ResponseEntity<?> desactivar(@PathVariable long id, @RequestBody(required = false) String body,
            JwtAuthenticationToken token) {
        check(body == null || body.isBlank());
        return client.call("DELETE", "/repartidores/" + positive(id), null, token);
    }
    @GetMapping("/repartidores/{id}/asignaciones")
    public ResponseEntity<?> asignaciones(@PathVariable long id, JwtAuthenticationToken token) {
        return client.call("GET", "/repartidores/" + positive(id) + "/asignaciones", null, token);
    }
    @PostMapping(value = "/repartidores/{id}/asignaciones", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> asignar(@PathVariable long id, @RequestBody tools.jackson.databind.JsonNode body,
            JwtAuthenticationToken token) {
        positive(id);
        check(body.isObject() && (body.size() == 1 || body.size() == 2) && integer(body.get("pedidoId")));
        check(textoOpcional(body.get("nota"), 500));
        return client.call("POST", "/repartidores/" + id + "/asignaciones", body.toString(), token);
    }
    @PutMapping(value = "/repartidores/{id}/asignaciones/{pedidoId}/estado", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> estadoAsignacion(@PathVariable long id, @PathVariable long pedidoId,
            @RequestBody tools.jackson.databind.JsonNode body, JwtAuthenticationToken token) {
        positive(id);
        positive(pedidoId);
        check(estadoUnico(body, ASIGNACION));
        return client.call("PUT", "/repartidores/" + id + "/asignaciones/" + pedidoId + "/estado", body.toString(), token);
    }

    private static boolean perfilValido(tools.jackson.databind.JsonNode body) {
        if (!body.isObject() || body.size() < 2 || body.size() > 4) return false;
        var nombre = body.get("nombre");
        if (nombre == null || !nombre.isTextual()) return false;
        var limpio = nombre.asText().strip();
        if (limpio.isEmpty() || limpio.length() > 120) return false;
        var vehiculo = body.get("vehiculo");
        if (vehiculo == null || !vehiculo.isTextual() || !VEHICULOS.contains(vehiculo.asText())) return false;
        return textoOpcional(body.get("telefono"), 30) && textoOpcional(body.get("zona"), 100);
    }
    private static boolean textoOpcional(tools.jackson.databind.JsonNode node, int maximo) {
        return node == null || (node.isTextual() && node.asText().strip().length() <= maximo);
    }
    private static boolean estadoUnico(tools.jackson.databind.JsonNode body, java.util.Set<String> permitidos) {
        if (!body.isObject() || body.size() != 1) return false;
        var estado = body.get("estado");
        return estado != null && estado.isTextual() && permitidos.contains(estado.asText());
    }
    private static long positive(long id) {
        if (id < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return id;
    }
    private static boolean integer(tools.jackson.databind.JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0;
    }
    private static void check(boolean valid) {
        if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}
