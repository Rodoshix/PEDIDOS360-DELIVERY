package cl.duoc.pedidos360.kafkaadmin;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class KafkaAdminApplication {
  public static void main(String[] args) {
    try (var context = SpringApplication.run(KafkaAdminApplication.class, args)) {}
  }

  @Bean
  ApplicationRunner adminCommand(Environment env) {
    return args -> {
      String command = env.getProperty("p360.kafka.admin.command", "describe");
      if (!Set.of("describe", "plan", "apply", "groups").contains(command))
        throw new IllegalArgumentException(
            "Operation disabled; only describe/plan/apply/groups allowed");
      Properties properties = new Properties();
      properties.put(
          "bootstrap.servers", env.getProperty("p360.kafka.bootstrap-servers", "localhost:19092"));
      properties.put("default.api.timeout.ms", "10000");
      properties.put("request.timeout.ms", "5000");
      try (Admin admin = Admin.create(properties)) {
        if (command.equals("groups")) {
          for (var group : admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS)) {
            String id = group.groupId();
            if (!id.startsWith("p360-")) continue;
            var description =
                admin.describeConsumerGroups(List.of(id)).all().get(10, TimeUnit.SECONDS).get(id);
            System.out.println(
                "GROUP "
                    + id
                    + " "
                    + description.state()
                    + " members="
                    + description.members().size());
            var offsets =
                admin
                    .listConsumerGroupOffsets(id)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            var requests =
                new HashMap<
                    org.apache.kafka.common.TopicPartition,
                    org.apache.kafka.clients.admin.OffsetSpec>();
            offsets
                .keySet()
                .forEach(
                    tp -> requests.put(tp, org.apache.kafka.clients.admin.OffsetSpec.latest()));
            var ends = admin.listOffsets(requests).all().get(10, TimeUnit.SECONDS);
            for (var e : offsets.entrySet())
              System.out.println(
                  "OFFSET "
                      + id
                      + " "
                      + e.getKey()
                      + " committed="
                      + e.getValue().offset()
                      + " lag="
                      + Math.max(0, ends.get(e.getKey()).offset() - e.getValue().offset()));
          }
        } else if (command.equals("describe")) {
          var names = admin.listTopics().names().get(10, TimeUnit.SECONDS);
          for (var spec : TopicCatalog.topics()) {
            if (!names.contains(spec.name())) {
              System.out.println("MISSING " + spec.name());
              continue;
            }
            var description =
                admin
                    .describeTopics(List.of(spec.name()))
                    .allTopicNames()
                    .get(10, TimeUnit.SECONDS)
                    .get(spec.name());
            var resource =
                new org.apache.kafka.common.config.ConfigResource(
                    org.apache.kafka.common.config.ConfigResource.Type.TOPIC, spec.name());
            var actual =
                admin
                    .describeConfigs(List.of(resource))
                    .all()
                    .get(10, TimeUnit.SECONDS)
                    .get(resource);
            System.out.println(
                "TOPIC "
                    + spec.name()
                    + " partitions="
                    + description.partitions().size()
                    + " replicas="
                    + description.partitions().getFirst().replicas().size());
            for (String key : spec.config().keySet())
              System.out.println(
                  "CONFIG " + spec.name() + " " + key + "=" + actual.get(key).value());
          }
        } else
          new TopicReconciler(admin)
              .reconcile(command.equals("apply"))
              .forEach(System.out::println);
      }
    };
  }
}
