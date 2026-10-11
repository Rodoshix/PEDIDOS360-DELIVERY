package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.*;

import cl.duoc.pedidos360.messaging.command.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CarritoCommandContractTests {
  VaciarCarritoPorPedido command() {
    return new VaciarCarritoPorPedido(
        UUID.randomUUID(),
        "VaciarCarritoPorPedido",
        1,
        Instant.parse("2026-01-01T00:00:00Z"),
        1,
        2,
        0,
        new VaciarCarritoPorPedido.Propietario(UUID.randomUUID(), UUID.randomUUID()));
  }

  @Test
  void exactV1RoundTripAndRedaction() {
    var c = command();
    assertThat(
            VaciarCarritoPorPedido.leer(
                c.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isEqualTo(c);
    assertThat(c.toString()).isEqualTo("VaciarCarritoPorPedido[redacted]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "extra",
        "missing",
        "duplicate",
        "ownerExtra",
        "ownerMissing",
        "type",
        "version",
        "float",
        "negative",
        "stringId",
        "nullOwner",
        "badUuid",
        "badTime",
        "offset",
        "trailing"
      })
  void strictRejection(String mutation) {
    String s = command().canonical();
    s =
        switch (mutation) {
          case "extra" -> s.replace("\"type\":", "\"extra\":1,\"type\":");
          case "missing" -> s.replace("\"version\":1,", "");
          case "duplicate" -> s.replace("\"version\":1", "\"version\":1,\"version\":1");
          case "ownerExtra" -> s.replace("\"tenantId\":", "\"extra\":1,\"tenantId\":");
          case "ownerMissing" -> s.replaceAll("\"entraObjectId\":\"[^\"]+\"", "\"x\":1");
          case "type" -> s.replace("VaciarCarritoPorPedido", "Other");
          case "version" -> s.replace("\"version\":1", "\"version\":2");
          case "float" -> s.replace("\"version\":1", "\"version\":1.0");
          case "negative" ->
              s.replace("\"expectedCarritoVersion\":0", "\"expectedCarritoVersion\":-1");
          case "stringId" -> s.replace("\"pedidoId\":1", "\"pedidoId\":\"1\"");
          case "nullOwner" -> s.replaceAll("\"propietario\":\\{.*?}", "\"propietario\":null");
          case "badUuid" -> s.replaceAll("\"messageId\":\"[^\"]+\"", "\"messageId\":\"1-1-1-1-1\"");
          case "badTime" -> s.replace("2026-01-01T00:00:00Z", "bad");
          case "offset" -> s.replace("2026-01-01T00:00:00Z", "2026-01-01T00:00:00+00:00");
          default -> s + "{}";
        };
    byte[] body = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThatThrownBy(() -> VaciarCarritoPorPedido.leer(body))
        .isInstanceOf(RuntimeException.class);
  }
}
