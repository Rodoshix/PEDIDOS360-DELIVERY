package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.client.ComercioClient;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Lecturas únicamente. La pertenencia se revalida en los servicios de I3. */
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
}
