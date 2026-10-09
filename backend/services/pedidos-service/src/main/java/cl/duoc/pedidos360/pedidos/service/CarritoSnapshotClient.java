package cl.duoc.pedidos360.pedidos.service;

import cl.duoc.pedidos360.pedidos.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.pedidos.exception.PedidoException;
import cl.duoc.pedidos360.pedidos.security.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CarritoSnapshotClient {
  public record Snapshot(UUID tenant, UUID oid, long carritoId, long version) {
    @Override
    public String toString() {
      return "Snapshot[redacted]";
    }
  }

  private final UpstreamSeguro http;
  private final java.net.URI carrito;

  public CarritoSnapshotClient(UpstreamSeguro http, Environment env) {
    this.http = http;
    carrito =
        UpstreamSeguro.origen(env.getProperty("pedidos.carrito-url", "http://localhost:8084"));
  }

  public Snapshot obtener(IdentidadUsuario identidad, CrearPedidoRequest request) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken token) || !token.isAuthenticated())
      throw new PedidoException(HttpStatus.FORBIDDEN, "Identidad delegada requerida.");
    UUID tenant = UUID.fromString(token.getToken().getClaimAsString("tid")),
        oid = UUID.fromString(token.getToken().getClaimAsString("oid"));
    if (!tenant.equals(identidad.tenantId())
        || token.getToken().getExpiresAt() == null
        || !token.getToken().getExpiresAt().isAfter(java.time.Instant.now()))
      throw new PedidoException(HttpStatus.FORBIDDEN, "Identidad vigente requerida.");
    var n = http.get(carrito.resolve("/carrito"), token.getToken().getTokenValue(), true);
    try {
      var names = new HashSet<String>();
      n.properties().forEach(e -> names.add(e.getKey()));
      if (!names.equals(
              Set.of("id", "restauranteId", "moneda", "total", "version", "actualizadoEn", "items"))
          || !"CLP".equals(n.path("moneda").asText())) throw new IllegalArgumentException();
      long id = number(n, "id"),
          version = number(n, "version"),
          restaurant = number(n, "restauranteId");
      if (id < 1
          || version < 0
          || restaurant != request.restauranteId()
          || !n.path("items").isArray()
          || n.path("items").isEmpty()) throw new IllegalArgumentException();
      var expected = new TreeMap<Long, Integer>();
      for (var item : request.items())
        if (item == null
            || item.productoId() == null
            || item.productoId() < 1
            || item.cantidad() < 1
            || expected.put(item.productoId(), item.cantidad()) != null)
          throw new IllegalArgumentException();
      var actual = new TreeMap<Long, Integer>();
      for (var item : n.path("items")) {
        long product = number(item, "productoId"), qty = number(item, "cantidad");
        if (product < 1 || qty < 1 || qty > 99 || actual.put(product, (int) qty) != null)
          throw new IllegalArgumentException();
      }
      if (!actual.equals(expected)) throw new IllegalArgumentException();
      return new Snapshot(tenant, oid, id, version);
    } catch (RuntimeException invalid) {
      throw new PedidoException(
          HttpStatus.CONFLICT, "El carrito cambió o su snapshot no es válido.");
    }
  }

  private static long number(tools.jackson.databind.JsonNode n, String field) {
    var v = n.path(field);
    if (!v.isIntegralNumber() || !v.canConvertToLong()) throw new IllegalArgumentException();
    return v.longValue();
  }
}
