package cl.duoc.pedidos360.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict wire boundary; no Java type headers, tokens or polymorphic deserialization. */
public final class EventCodec {
  public static final String RESTAURANTES = "p360.catalogo.restaurantes.estado.v1";
  public static final String PRODUCTOS = "p360.catalogo.productos.disponibilidad.v1";
  private final JsonMapper json = JsonMapper.builder()
      .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  public record Event(
      int schemaVersion,
      UUID eventId,
      String eventType,
      String producer,
      Instant occurredAt,
      long aggregateVersion,
      String reason,
      JsonNode data) {}

  public record RestauranteEstadoEventV1(
      int schemaVersion,
      UUID eventId,
      String eventType,
      String producer,
      Instant occurredAt,
      long aggregateVersion,
      String reason,
      RestauranteData data) {}

  public record RestauranteData(String restauranteId, String estado) {}

  public record ProductoDisponibilidadEventV1(
      int schemaVersion,
      UUID eventId,
      String eventType,
      String producer,
      Instant occurredAt,
      long aggregateVersion,
      String reason,
      ProductoData data) {}

  public record ProductoData(String productoId, boolean disponible) {}

  public static final class InvalidEvent extends RuntimeException {
    public InvalidEvent() {
      super("INVALID_EVENT");
    }
  }

  public String encode(String topic, long id, String state, long version, String reason) {
    UUID idEvent = UUID.randomUUID();
    Instant at = Instant.now();
    Object dto;
    if (topic.equals(RESTAURANTES))
      dto =
          new RestauranteEstadoEventV1(
              1,
              idEvent,
              "RestauranteEstadoPublicado",
              "restaurantes-service",
              at,
              version,
              reason,
              new RestauranteData(Long.toString(id), state));
    else if (topic.equals(PRODUCTOS)) {
      if (!Set.of("true", "false").contains(state)) throw new InvalidEvent();
      dto =
          new ProductoDisponibilidadEventV1(
              1,
              idEvent,
              "ProductoDisponibilidadPublicada",
              "productos-service",
              at,
              version,
              reason,
              new ProductoData(Long.toString(id), Boolean.parseBoolean(state)));
    } else throw new InvalidEvent();
    var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(dto);
    node.put("occurredAt", at.toString());
    String body = json.writeValueAsString(node);
    decode(topic, Long.toString(id), body);
    return body;
  }

  public Event decode(String topic, String key, String body) {
    try {
      if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 16384)
        throw new InvalidEvent();
      var n = json.readTree(body);
      if (n == null
          || !n.isObject()
          || !n.path("schemaVersion").isIntegralNumber()
          || !n.path("schemaVersion").canConvertToInt()
          || n.path("schemaVersion").intValue() != 1
          || !n.path("aggregateVersion").isIntegralNumber()
          || !n.path("aggregateVersion").canConvertToLong()
          || n.path("aggregateVersion").longValue() < 1) throw new InvalidEvent();
      String eventType = text(n, "eventType"),
          producer = text(n, "producer"),
          reason = text(n, "reason");
      if (!Set.of("INITIALIZED", "CHANGED", "SNAPSHOT").contains(reason)) throw new InvalidEvent();
      UUID uuid = UUID.fromString(text(n, "eventId"));
      if (!uuid.toString().equals(text(n, "eventId"))) throw new InvalidEvent();
      Instant at = Instant.parse(text(n, "occurredAt"));
      var d = n.path("data");
      if (!d.isObject()) throw new InvalidEvent();
      String id;
      if (topic.equals(RESTAURANTES)) {
        id = text(d, "restauranteId");
        if (!Set.of("ABIERTO", "CERRADO", "INACTIVO").contains(text(d, "estado"))
            || !eventType.equals("RestauranteEstadoPublicado")
            || !producer.equals("restaurantes-service")) throw new InvalidEvent();
      } else if (topic.equals(PRODUCTOS)) {
        id = text(d, "productoId");
        if (!d.path("disponible").isBoolean()
            || !eventType.equals("ProductoDisponibilidadPublicada")
            || !producer.equals("productos-service")) throw new InvalidEvent();
      } else throw new InvalidEvent();
      if (!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0 || !id.equals(key))
        throw new InvalidEvent();
      return new Event(
          1, uuid, eventType, producer, at, n.path("aggregateVersion").longValue(), reason, d);
    } catch (InvalidEvent e) {
      throw e;
    } catch (RuntimeException e) {
      throw new InvalidEvent();
    }
  }

  private static String text(JsonNode n, String f) {
    var x = n.path(f);
    if (!x.isString() || x.stringValue().isBlank()) throw new InvalidEvent();
    return x.stringValue();
  }

  public static String state(Event e) {
    return e.eventType().equals("RestauranteEstadoPublicado")
        ? e.data().path("estado").stringValue()
        : Boolean.toString(e.data().path("disponible").booleanValue());
  }
}
