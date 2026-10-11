package cl.duoc.pedidos360.pedidos;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cl.duoc.pedidos360.pedidos.dto.*;
import cl.duoc.pedidos360.pedidos.security.*;
import cl.duoc.pedidos360.pedidos.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class CarritoSnapshotClientTests {
  final UUID tenant = UUID.randomUUID(), oid = UUID.randomUUID();
  final UpstreamSeguro http = mock(UpstreamSeguro.class);
  final CarritoSnapshotClient client = new CarritoSnapshotClient(http, new MockEnvironment());
  final IdentidadUsuario identity =
      new IdentidadUsuario(tenant, 42L, Set.of(IdentidadUsuario.Rol.CLIENTE));
  final CrearPedidoRequest request =
      new CrearPedidoRequest(20L, "Dirección", List.of(new LineaPedidoRequest(101L, 2)));

  @BeforeEach
  void authenticate() {
    var jwt =
        Jwt.withTokenValue("verified-fixture")
            .header("alg", "RS256")
            .claim("tid", tenant.toString())
            .claim("oid", oid.toString())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt,
                List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_CLIENTE"))));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  void answer(String mutation) {
    var j = JsonMapper.builder().build();
    var n =
        j.createObjectNode()
            .put("id", 5)
            .put("version", 3)
            .put("restauranteId", 20)
            .put("moneda", "CLP")
            .put("total", 100)
            .put("actualizadoEn", "2026-01-01T00:00:00Z");
    var a = n.putArray("items");
    a.addObject().put("productoId", 101).put("cantidad", 2);
    switch (mutation) {
      case "version" -> n.put("version", -1);
      case "restaurant" -> n.put("restauranteId", 99);
      case "quantity" -> ((tools.jackson.databind.node.ObjectNode) a.get(0)).put("cantidad", 3);
      case "duplicate" -> a.add(a.get(0).deepCopy());
      case "id" -> n.putNull("id");
      case "extra" -> n.put("extra", 1);
      default -> {}
    }
    when(http.get(any(), eq("verified-fixture"), eq(true))).thenReturn(n);
  }

  @Test
  void usesVerifiedOidNotLocalNumericId() {
    answer("valid");
    var s = client.obtener(identity, request);
    assertThat(s.oid()).isEqualTo(oid);
    assertThat(s.carritoId()).isEqualTo(5);
    assertThat(s.version()).isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "restaurant", "quantity", "duplicate", "id", "extra"})
  void rejectsUnverifiedSnapshots(String m) {
    answer(m);
    assertThatThrownBy(() -> client.obtener(identity, request))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void unauthenticatedHasNoRemoteCall() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> client.obtener(identity, request))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(http);
  }
}
