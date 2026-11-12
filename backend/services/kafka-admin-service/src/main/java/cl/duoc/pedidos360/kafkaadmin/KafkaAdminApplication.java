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
      AdminRequest request;
      try { request = AdminRequest.parse(env, args); }
      catch (IllegalArgumentException failure) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("AUDIT kafka-admin operation=UNKNOWN outcome=REJECTED");
        throw failure;
      }
      String command = request.command();
      org.slf4j.LoggerFactory.getLogger(getClass()).info("AUDIT kafka-admin operation={} resource={} run={} outcome=STARTED", command, request.topic() == null ? "catalog" : request.topic(), request.runId() == null ? "none" : request.runId());
      Properties properties = new Properties();
      properties.put(
          "bootstrap.servers", request.servers());
      properties.put("default.api.timeout.ms", "10000");
      properties.put("request.timeout.ms", "5000");
      KafkaTlsProperties.apply(env, properties);
      try (Admin admin = Admin.create(properties)) {
        if (command.startsWith("test-")) {
          new DisposableTopicAdmin(admin).execute(request).forEach(System.out::println);
        } else if (command.equals("list")) {
          var names = admin.listTopics().names().get(10, TimeUnit.SECONDS);
          TopicCatalog.topics().stream().map(TopicCatalog.Spec::name).filter(names::contains)
              .sorted().forEach(name -> System.out.println("TOPIC " + name));
        } else if (command.equals("groups")) {
          ConsumerGroupInspection.read(admin).forEach(System.out::println);
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
        org.slf4j.LoggerFactory.getLogger(getClass()).info("AUDIT kafka-admin operation={} resource={} run={} outcome=SUCCEEDED", command, request.topic() == null ? "catalog" : request.topic(), request.runId() == null ? "none" : request.runId());
      } catch (Exception failure) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("AUDIT kafka-admin operation={} resource={} run={} outcome=FAILED", command, request.topic() == null ? "catalog" : request.topic(), request.runId() == null ? "none" : request.runId());
        throw failure;
      }
    };
  }
}
