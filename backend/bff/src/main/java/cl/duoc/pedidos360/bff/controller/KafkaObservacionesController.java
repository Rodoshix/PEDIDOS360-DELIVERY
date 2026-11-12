package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.client.ComercioClient;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class KafkaObservacionesController {
  private final ComercioClient client;

  public KafkaObservacionesController(ComercioClient client) {
    this.client = client;
  }

  @GetMapping("/productos/restaurante/{id}/estado-restaurante")
  public ResponseEntity<?> restaurante(@PathVariable long id, JwtAuthenticationToken token) {
    if (id < 1)
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
    return client.call("GET", "/productos/restaurante/" + id + "/estado-restaurante", null, token);
  }

  @GetMapping("/carrito/disponibilidad")
  public ResponseEntity<?> carrito(JwtAuthenticationToken token) {
    return client.call("GET", "/carrito/disponibilidad", null, token);
  }
}
