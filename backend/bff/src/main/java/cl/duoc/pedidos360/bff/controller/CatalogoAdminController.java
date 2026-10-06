package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.client.ComercioClient;
import java.util.Set;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Escrituras de catálogo: seguridad ADMIN aplicada antes de entrar al controlador. */
@RestController
public class CatalogoAdminController {
    private final ComercioClient client;
    public CatalogoAdminController(ComercioClient client) { this.client = client; }

    @GetMapping("/restaurantes/admin/acceso")
    public ResponseEntity<Void> acceso() { return ResponseEntity.noContent().build(); }

    @PostMapping(value = "/restaurantes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> crearRestaurante(@RequestBody JsonNode body, JwtAuthenticationToken token) {
        restaurante(body); return client.call("POST", "/restaurantes", body.toString(), token);
    }
    @PutMapping(value = "/restaurantes/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> editarRestaurante(@PathVariable long id, @RequestBody JsonNode body, JwtAuthenticationToken token) {
        restaurante(body); return client.call("PUT", "/restaurantes/" + positive(id), body.toString(), token);
    }
    @DeleteMapping("/restaurantes/{id}")
    public ResponseEntity<?> desactivar(@PathVariable long id, JwtAuthenticationToken token) {
        return client.call("DELETE", "/restaurantes/" + positive(id), null, token);
    }
    @PostMapping(value = "/productos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> crearProducto(@RequestBody JsonNode body, JwtAuthenticationToken token) {
        producto(body); return client.call("POST", "/productos", body.toString(), token);
    }
    @PutMapping(value = "/productos/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> editarProducto(@PathVariable long id, @RequestBody JsonNode body, JwtAuthenticationToken token) {
        producto(body); return client.call("PUT", "/productos/" + positive(id), body.toString(), token);
    }
    @PatchMapping("/productos/{id}/disponibilidad")
    public ResponseEntity<?> disponibilidad(@PathVariable long id, @RequestParam boolean disponible, JwtAuthenticationToken token) {
        return client.call("PATCH", "/productos/" + positive(id) + "/disponibilidad?disponible=" + disponible, null, token);
    }
    private static long positive(long id) { if (id < 1) invalid(); return id; }
    private static void fields(JsonNode body, Set<String> allowed) {
        if (!body.isObject()) invalid();
        for (String name : body.propertyNames()) if (!allowed.contains(name)) invalid();
    }
    private static void text(JsonNode body, String name, int max, boolean required) {
        var value = body.get(name);
        if (value == null || value.isNull()) { if (required) invalid(); return; }
        if (!value.isString() || value.stringValue().length() > max || (required && value.stringValue().isBlank())) invalid();
    }
    private static void restaurante(JsonNode body) {
        fields(body, Set.of("nombre", "descripcion", "direccion", "estado"));
        text(body, "nombre", 120, true); text(body, "descripcion", 500, false); text(body, "direccion", 255, false);
        var estado = body.get("estado");
        if (estado == null || !estado.isString() || !Set.of("ABIERTO", "CERRADO", "INACTIVO").contains(estado.stringValue())) invalid();
    }
    private static void producto(JsonNode body) {
        fields(body, Set.of("restauranteId", "nombre", "descripcion", "precio", "categoria", "disponible"));
        text(body, "nombre", 120, true); text(body, "descripcion", 500, false); text(body, "categoria", 80, true);
        var id = body.get("restauranteId"); var precio = body.get("precio"); var disponible = body.get("disponible");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1
                || precio == null || !precio.isNumber() || precio.decimalValue().compareTo(new java.math.BigDecimal("0.01")) < 0
                || precio.decimalValue().compareTo(new java.math.BigDecimal("99999999.99")) > 0
                || precio.decimalValue().stripTrailingZeros().scale() > 2
                || disponible == null || !disponible.isBoolean()) invalid();
    }
    private static void invalid() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de catálogo inválidos."); }
}
