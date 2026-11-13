package cl.duoc.pedidos360.kafkaadmin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;

/** Read-only formatting of the existing groups command, without client IDs or host addresses. */
final class ConsumerGroupInspection {
  static final List<String> EXPECTED = List.of(
      "p360-productos-restaurantes-estado-v1", "p360-carrito-productos-disponibilidad-v1");

  static List<String> read(Admin admin) {
    try {
      var visible = new TreeSet<String>();
      for (var group : admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS)) {
        if (group.groupId().matches("p360-[A-Za-z0-9._-]{1,160}")) visible.add(group.groupId());
      }
      var lines = new ArrayList<String>();
      if (visible.isEmpty()) lines.add("GROUPS NO_VISIBLE_GROUPS");
      for (var expected : EXPECTED) if (!visible.contains(expected))
        lines.add("GROUP " + expected + " NOT_LISTED");
      for (String id : visible) {
        ConsumerGroupDescription description;
        try {
          description = admin.describeConsumerGroups(List.of(id)).all()
              .get(10, TimeUnit.SECONDS).get(id);
        } catch (ExecutionException failure) {
          if (failure.getCause() instanceof GroupIdNotFoundException) {
            lines.add("GROUP " + id + " MISSING_DURING_QUERY");
            continue;
          }
          throw failure;
        }
        lines.add("GROUP " + id + " " + description.state() + " members=" + description.members().size());
        var members = new TreeMap<String, String>();
        var assigned = new TreeSet<TopicPartition>(Comparator.comparing(TopicPartition::topic)
            .thenComparingInt(TopicPartition::partition));
        for (var member : description.members()) {
          var partitions = member.assignment().topicPartitions().stream()
              .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
              .toList();
          assigned.addAll(partitions);
          var names = partitions.stream().map(ConsumerGroupInspection::partitionName).toList();
          String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
              .digest(member.consumerId().getBytes(StandardCharsets.UTF_8)));
          members.put(hash, "MEMBER " + id + " member_hash=" + hash + " assignment=" + names);
        }
        if (members.isEmpty()) lines.add("MEMBERS " + id + " NONE");
        lines.addAll(members.values());
        var offsets = admin.listConsumerGroupOffsets(id).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS);
        var requests = new HashMap<TopicPartition, OffsetSpec>();
        offsets.keySet().forEach(tp -> requests.put(tp, OffsetSpec.latest()));
        var ends = requests.isEmpty()
            ? Map.<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>of()
            : admin.listOffsets(requests).all().get(10, TimeUnit.SECONDS);
        if (offsets.isEmpty()) lines.add("OFFSETS " + id + " NONE");
        for (var tp : offsets.keySet().stream().sorted(Comparator.comparing(TopicPartition::topic)
            .thenComparingInt(TopicPartition::partition)).toList()) {
          lines.add("OFFSET " + id + " " + partitionName(tp) + " committed=" + offsets.get(tp).offset()
              + " lag=" + Math.max(0, ends.get(tp).offset() - offsets.get(tp).offset()));
        }
        for (var tp : assigned) if (!offsets.containsKey(tp))
          lines.add("OFFSET " + id + " " + partitionName(tp) + " committed=UNKNOWN lag=UNKNOWN");
      }
      return List.copyOf(lines);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Consumer group query interrupted; results incomplete");
    } catch (Exception failure) {
      // Do not attach broker exception messages, addresses or authorization details.
      throw new IllegalStateException("Consumer group query failed; results incomplete");
    }
  }

  private static String partitionName(TopicPartition tp) {
    if (!tp.topic().matches("[A-Za-z0-9._-]{1,249}") || tp.partition() < 0)
      throw new IllegalArgumentException("Invalid partition metadata");
    return tp.topic() + "-" + tp.partition();
  }
}
