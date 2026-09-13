package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.client.ComercioClient;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
public class ComercioController {
    private final ComercioClient client;
    public ComercioController(ComercioClient client) { this.client = client; }

    @GetMapping("/restaurantes")
    public ResponseEntity<?> restaurantes(JwtAuthenticationToken token) { return client.call("GET", "/restaurantes", null, token); }
    @GetMapping("/restaurantes/{id}")
    public ResponseEntity<?> restaurante(@PathVariable long id, JwtAuthenticationToken token) { return client.call("GET", "/restaurantes/" + positive(id), null, token); }
    @GetMapping("/productos")
    public ResponseEntity<?> productos(JwtAuthenticationToken token) { return client.call("GET", "/productos", null, token); }
    @GetMapping("/productos/{id}")
    public ResponseEntity<?> producto(@PathVariable long id, JwtAuthenticationToken token) { return client.call("GET", "/productos/" + positive(id), null, token); }
    @GetMapping({"/productos/restaurante/{id}", "/restaurantes/{id}/productos"})
    public ResponseEntity<?> porRestaurante(@PathVariable long id, JwtAuthenticationToken token) { return client.call("GET", "/productos/restaurante/" + positive(id), null, token); }
    @GetMapping("/productos/restaurante/{id}/disponibles")
    public ResponseEntity<?> disponibles(@PathVariable long id, JwtAuthenticationToken token) { return client.call("GET", "/productos/restaurante/" + positive(id) + "/disponibles", null, token); }
    @GetMapping("/carrito")
    public ResponseEntity<?> carrito(JwtAuthenticationToken token) { return client.call("GET", "/carrito", null, token); }
    @DeleteMapping("/carrito")
    public ResponseEntity<?> vaciar(JwtAuthenticationToken token) { return client.call("DELETE", "/carrito", null, token); }
    @DeleteMapping("/carrito/items/{id}")
    public ResponseEntity<?> quitar(@PathVariable long id, JwtAuthenticationToken token) { return client.call("DELETE", "/carrito/items/" + positive(id), null, token); }
    @PostMapping(value = "/carrito/items", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> agregar(@RequestBody JsonNode body, JwtAuthenticationToken token) {
        validate(body, true);
        return client.call("POST", "/carrito/items", body.toString(), token);
    }
    @PutMapping(value = "/carrito/items/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> cantidad(@PathVariable long id, @RequestBody JsonNode body, JwtAuthenticationToken token) {
        positive(id);
        validate(body, false);
        return client.call("PUT", "/carrito/items/" + id, body.toString(), token);
    }
    private static long positive(long id) {
        if (id < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return id;
    }
    private static void validate(JsonNode body, boolean create) {
        if (!body.isObject() || body.size() != (create ? 2 : 1)
                || !integer(body.get("cantidad"), 99)
                || (create && !integer(body.get("productoId"), Long.MAX_VALUE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
    private static boolean integer(JsonNode value, long max) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 1 && value.longValue() <= max;
    }
}
