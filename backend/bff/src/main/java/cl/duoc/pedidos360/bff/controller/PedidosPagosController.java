package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.client.ComercioClient;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** La pertenencia y las transiciones se revalidan en los servicios de I3. */
@RestController
public class PedidosPagosController {
    private final ComercioClient client;
    public PedidosPagosController(ComercioClient client) { this.client = client; }

    @GetMapping("/pedidos")
    public ResponseEntity<?> todos(JwtAuthenticationToken token) {
        return client.call("GET", "/pedidos", null, token);
    }
    @GetMapping("/pedidos/me")
    public ResponseEntity<?> propios(JwtAuthenticationToken token) {
        return client.call("GET", "/pedidos/me", null, token);
    }
    @GetMapping("/pedidos/{id}")
    public ResponseEntity<?> pedido(@PathVariable long id, JwtAuthenticationToken token) {
        return client.call("GET", "/pedidos/" + positive(id), null, token);
    }
    @GetMapping("/pagos/{id}")
    public ResponseEntity<?> pago(@PathVariable long id, JwtAuthenticationToken token) {
        return client.call("GET", "/pagos/" + positive(id), null, token);
    }
    @GetMapping("/pagos/pedido/{id}")
    public ResponseEntity<?> pagosPedido(@PathVariable long id, JwtAuthenticationToken token) {
        return client.call("GET", "/pagos/pedido/" + positive(id), null, token);
    }
    private static long positive(long id) {
        if (id < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return id;
    }

    @PostMapping(value = "/pedidos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> crearPedido(@RequestBody tools.jackson.databind.JsonNode body, JwtAuthenticationToken token) {
        check(body.isObject() && body.size() == 3 && integer(body.get("restauranteId")));
        var address = body.get("direccionEntrega");
        check(address != null && address.isTextual() && !address.asText().isBlank() && address.asText().length() <= 255);
        var items = body.get("items");
        check(items != null && items.isArray() && !items.isEmpty());
        for (var item : items) check(item.isObject() && item.size() == 2
            && integer(item.get("productoId")) && integer(item.get("cantidad"))
            && item.get("cantidad").canConvertToInt());
        return client.call("POST", "/pedidos", body.toString(), token);
    }
    @PostMapping(value = "/pagos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> crearPago(@RequestBody tools.jackson.databind.JsonNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) java.util.List<String> keys,
            JwtAuthenticationToken token) {
        check(keys != null && keys.size() == 1 && keys.getFirst().matches("[A-Za-z0-9_-]{1,80}"));
        check(body.isObject() && body.size() == 2 && integer(body.get("pedidoId")));
        check(body.has("metodo") && body.get("metodo").isTextual()
            && java.util.Set.of("TARJETA", "EFECTIVO").contains(body.get("metodo").asText()));
        return client.call("POST", "/pagos", body.toString(), token, keys.getFirst());
    }
    @PutMapping(value = "/pedidos/{id}/estado", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> estado(@PathVariable long id, @RequestBody tools.jackson.databind.JsonNode body, JwtAuthenticationToken token) {
        positive(id);
        check(body.isObject() && body.size() == 1 && body.has("estado") && body.get("estado").isTextual()
            && java.util.Set.of("CREADO", "CONFIRMADO", "PREPARANDO", "LISTO", "EN_REPARTO", "ENTREGADO", "CANCELADO")
                .contains(body.get("estado").asText()));
        return client.call("PUT", "/pedidos/" + id + "/estado", body.toString(), token);
    }
    @PutMapping("/pagos/{id}/aprobar")
    public ResponseEntity<?> aprobar(@PathVariable long id, @RequestBody(required = false) String body, JwtAuthenticationToken token) {
        check(body == null || body.isBlank());
        return client.call("PUT", "/pagos/" + positive(id) + "/aprobar", null, token);
    }
    private static boolean integer(tools.jackson.databind.JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0;
    }
    private static void check(boolean valid) {
        if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}
