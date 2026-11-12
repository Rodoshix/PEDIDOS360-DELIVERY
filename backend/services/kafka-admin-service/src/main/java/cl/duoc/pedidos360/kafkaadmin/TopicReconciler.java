package cl.duoc.pedidos360.kafkaadmin;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;

public final class TopicReconciler {
  private final Admin admin;

  public TopicReconciler(Admin admin) {
    this.admin = admin;
  }

  public List<String> reconcile(boolean apply) throws Exception {
    var actions = new ArrayList<java.util.concurrent.Callable<Void>>();
    var result = new ArrayList<String>();
    var existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
    for (var spec : TopicCatalog.topics()) {
      if (!existing.contains(spec.name())) {
        result.add("CREATE " + spec.name());
        actions.add(
            () -> {
              admin
                  .createTopics(
                      List.of(
                          new NewTopic(spec.name(), spec.partitions(), spec.replicas())
                              .configs(spec.config())))
                  .all()
                  .get(10, TimeUnit.SECONDS);
              return null;
            });
        continue;
      }
      var desc =
          admin
              .describeTopics(List.of(spec.name()))
              .allTopicNames()
              .get(10, TimeUnit.SECONDS)
              .get(spec.name());
      if (desc.partitions().size() != spec.partitions()
          || desc.partitions().stream().anyMatch(x -> x.replicas().size() != spec.replicas()))
        throw new IllegalStateException(
            "Partition/replication drift requires separate plan: " + spec.name());
      var resource = new ConfigResource(ConfigResource.Type.TOPIC, spec.name());
      var config =
          admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS).get(resource);
      var ops = new ArrayList<AlterConfigOp>();
      for (var entry : spec.config().entrySet()) {
        var actual = config.get(entry.getKey());
        if (actual == null || !entry.getValue().equals(actual.value())) {
          result.add("SET " + spec.name() + " " + entry.getKey() + "=" + entry.getValue());
          ops.add(
              new AlterConfigOp(
                  new ConfigEntry(entry.getKey(), entry.getValue()), AlterConfigOp.OpType.SET));
        }
      }
      if (!ops.isEmpty())
        actions.add(
            () -> {
              admin.incrementalAlterConfigs(Map.of(resource, ops)).all().get(10, TimeUnit.SECONDS);
              return null;
            });
      if (ops.isEmpty()) result.add("UNCHANGED " + spec.name());
    }
    if (apply) for (var action : actions) action.call();
    return result;
  }
}
