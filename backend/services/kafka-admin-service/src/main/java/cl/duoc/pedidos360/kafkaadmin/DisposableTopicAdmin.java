package cl.duoc.pedidos360.kafkaadmin;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;

/** Run-scoped resources only. Cluster/rack/endpoint checks prevent accidents, not prove isolation.
 * The caller must provision and inspect a disposable broker with no operational mounts. */
final class DisposableTopicAdmin {
  private final Admin admin;
  DisposableTopicAdmin(Admin admin) { this.admin = admin; }

  void verifyIsolation(AdminRequest r) throws Exception {
    if (!r.command().startsWith("test-") || !r.disposable()) throw new IllegalArgumentException("Disposable request required");
    var cluster = admin.describeCluster();
    if (!r.clusterId().equals(cluster.clusterId().get(10, TimeUnit.SECONDS)))
      throw new IllegalStateException("Isolated cluster ID mismatch");
    var nodes = cluster.nodes().get(10, TimeUnit.SECONDS);
    if (nodes.size() != 1) throw new IllegalStateException("One disposable broker required");
    var node = nodes.iterator().next();
    if (!("p360-disposable-" + r.runId()).equals(node.rack()))
      throw new IllegalStateException("Disposable broker marker mismatch");
    var resource = new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(node.id()));
    var cfg = admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS).get(resource);
    if (cfg.get("delete.topic.enable") == null || !"true".equals(cfg.get("delete.topic.enable").value()))
      throw new IllegalStateException("Deletion must be enabled only on the isolated broker");
  }

  List<String> execute(AdminRequest r) throws Exception {
    verifyIsolation(r);
    String name = r.topic();
    var resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
    switch (r.command()) {
      case "test-create" -> admin.createTopics(List.of(new NewTopic(name, 1, (short) 1)
          .configs(Map.of("cleanup.policy", "delete", "retention.ms", "600000"))))
          .all().get(10, TimeUnit.SECONDS);
      case "test-update" -> admin.incrementalAlterConfigs(Map.of(resource, List.of(
          new AlterConfigOp(new ConfigEntry(r.config(), r.value()), AlterConfigOp.OpType.SET))))
          .all().get(10, TimeUnit.SECONDS);
      case "test-reset-config" -> admin.incrementalAlterConfigs(Map.of(resource, List.of(
          new AlterConfigOp(new ConfigEntry(r.config(), null), AlterConfigOp.OpType.DELETE))))
          .all().get(10, TimeUnit.SECONDS);
      case "test-delete" -> {
        admin.deleteTopics(List.of(name)).all().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(name)) {
          if (System.nanoTime() >= deadline) throw new IllegalStateException("Deletion not yet observed");
          Thread.sleep(100);
        }
        return List.of("DELETED " + name);
      }
      case "test-describe" -> { }
      default -> throw new IllegalArgumentException("Unsupported disposable operation");
    }
    var description = admin.describeTopics(List.of(name)).allTopicNames().get(10, TimeUnit.SECONDS).get(name);
    var cfg = admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS).get(resource);
    if (description.partitions().size() != 1
        || description.partitions().stream().anyMatch(p -> p.replicas().size() != 1))
      throw new IllegalStateException("Disposable topic topology mismatch");
    if (r.command().equals("test-update") && !r.value().equals(cfg.get(r.config()).value()))
      throw new IllegalStateException("Configuration update not observed");
    if (r.command().equals("test-reset-config") && cfg.get(r.config()).source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
      throw new IllegalStateException("Configuration reset not observed");
    var output = new ArrayList<String>();
    output.add("TOPIC " + name + " partitions=" + description.partitions().size()
        + " replicas=" + description.partitions().getFirst().replicas().size());
    for (String key : new TreeSet<>(AdminRequest.CONFIGS)) {
      var entry = cfg.get(key);
      output.add("CONFIG " + name + " " + key + "=" + entry.value() + " source=" + entry.source());
    }
    return output;
  }
}
