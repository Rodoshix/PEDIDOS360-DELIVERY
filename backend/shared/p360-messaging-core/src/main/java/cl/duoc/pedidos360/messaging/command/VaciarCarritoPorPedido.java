package cl.duoc.pedidos360.messaging.command;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Independent command contract; never carries delegated credentials. */
public record VaciarCarritoPorPedido(
    UUID messageId,
    String type,
    int version,
    Instant occurredAt,
    long pedidoId,
    long carritoId,
    long expectedCarritoVersion,
    Propietario propietario) {
  public record Propietario(UUID tenantId, UUID entraObjectId) {
    public Propietario {
      java.util.Objects.requireNonNull(tenantId);
      java.util.Objects.requireNonNull(entraObjectId);
    }
  }

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> FIELDS =
      Set.of(
          "messageId",
          "type",
          "version",
          "occurredAt",
          "pedidoId",
          "carritoId",
          "expectedCarritoVersion",
          "propietario");

  public VaciarCarritoPorPedido {
    java.util.Objects.requireNonNull(messageId);
    java.util.Objects.requireNonNull(occurredAt);
    java.util.Objects.requireNonNull(propietario);
    if (!"VaciarCarritoPorPedido".equals(type)
        || version != 1
        || pedidoId <= 0
        || carritoId <= 0
        || expectedCarritoVersion < 0) throw new IllegalArgumentException("Invalid cart command");
  }

  public static VaciarCarritoPorPedido leer(byte[] body) {
    if (body == null || body.length == 0 || body.length > 65536)
      throw new IllegalArgumentException("Invalid command size");
    var n = JSON.readTree(body);
    exact(n, FIELDS);
    var owner = n.path("propietario");
    exact(owner, Set.of("tenantId", "entraObjectId"));
    if (!n.path("type").isString()
        || !n.path("occurredAt").isString()
        || !n.path("occurredAt").stringValue().endsWith("Z"))
      throw new IllegalArgumentException("Invalid metadata");
    long version = integer(n, "version");
    if (version != 1) throw new IllegalArgumentException("Invalid version");
    return new VaciarCarritoPorPedido(
        uuid(n, "messageId"),
        n.path("type").stringValue(),
        1,
        Instant.parse(n.path("occurredAt").stringValue()),
        integer(n, "pedidoId"),
        integer(n, "carritoId"),
        integer(n, "expectedCarritoVersion"),
        new Propietario(uuid(owner, "tenantId"), uuid(owner, "entraObjectId")));
  }

  private static void exact(JsonNode n, Set<String> fields) {
    if (!n.isObject()) throw new IllegalArgumentException("Object required");
    var names = new java.util.HashSet<String>();
    n.properties().forEach(e -> names.add(e.getKey()));
    if (!names.equals(fields)) throw new IllegalArgumentException("Invalid fields");
  }

  private static long integer(JsonNode n, String field) {
    var v = n.path(field);
    if (!v.isIntegralNumber() || !v.canConvertToLong())
      throw new IllegalArgumentException("Integer required");
    return v.longValue();
  }

  private static UUID uuid(JsonNode n, String field) {
    var v = n.path(field);
    if (!v.isString()) throw new IllegalArgumentException("UUID required");
    var id = UUID.fromString(v.stringValue());
    if (!id.toString().equals(v.stringValue()))
      throw new IllegalArgumentException("Canonical UUID required");
    return id;
  }

  public String canonical() {
    return JSON.writeValueAsString(this);
  }

  @Override
  public String toString() {
    return "VaciarCarritoPorPedido[redacted]";
  }
}
