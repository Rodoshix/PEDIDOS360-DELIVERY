package cl.duoc.pedidos360.kafkaadmin;

import java.util.*;

public final class TopicCatalog {
  public record Spec(String name, int partitions, short replicas, Map<String, String> config) {
    public Spec {
      if (!name.matches(
              "p360\\.catalogo\\.(restaurantes\\.estado|productos\\.disponibilidad)\\.v1(\\.dlt)?")
          || partitions != 3
          || replicas != 1) throw new IllegalArgumentException("Topic outside local catalog");
      var expected = Map.of(
          "cleanup.policy", name.endsWith(".dlt") ? "delete" : "compact",
          "retention.ms", name.endsWith(".dlt") ? "604800000" : "-1",
          "min.insync.replicas", "1");
      if (!expected.equals(config)) throw new IllegalArgumentException("Configuration outside local policy");
      config = Map.copyOf(config);
    }
  }

  public static List<Spec> topics() {
    var list = new ArrayList<Spec>();
    for (String domain : List.of("restaurantes.estado", "productos.disponibilidad")) {
      String name = "p360.catalogo." + domain + ".v1";
      list.add(
          new Spec(
              name,
              3,
              (short) 1,
              Map.of(
                  "cleanup.policy", "compact", "retention.ms", "-1", "min.insync.replicas", "1")));
      list.add(
          new Spec(
              name + ".dlt",
              3,
              (short) 1,
              Map.of(
                  "cleanup.policy",
                  "delete",
                  "retention.ms",
                  "604800000",
                  "min.insync.replicas",
                  "1")));
    }
    return List.copyOf(list);
  }
}
