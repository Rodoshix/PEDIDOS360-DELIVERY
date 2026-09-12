package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.client.UsuariosClient;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/usuarios")
public class UsuariosController {
    private final UsuariosClient client;
    public UsuariosController(UsuariosClient client) { this.client = client; }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanio, JwtAuthenticationToken token) {
        if (pagina < 0 || tamanio < 1 || tamanio > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return client.call("GET", "/usuarios?pagina=" + pagina + "&tamanio=" + tamanio, null, token);
    }
    @GetMapping("/me")
    public ResponseEntity<?> me(JwtAuthenticationToken token) { return client.call("GET", "/usuarios/me", null, token); }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable long id, JwtAuthenticationToken token) {
        return client.call("GET", path(id), null, token);
    }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody JsonNode body, JwtAuthenticationToken token) {
        return client.call("POST", "/usuarios", body.toString(), token);
    }
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody JsonNode body, JwtAuthenticationToken token) {
        return client.call("PUT", path(id), body.toString(), token);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, JwtAuthenticationToken token) {
        return client.call("DELETE", path(id), null, token);
    }
    private String path(long id) {
        if (id < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return "/usuarios/" + id;
    }
}
