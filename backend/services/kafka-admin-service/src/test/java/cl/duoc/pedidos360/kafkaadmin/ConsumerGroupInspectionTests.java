package cl.duoc.pedidos360.kafkaadmin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.*;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.*;
import org.apache.kafka.common.errors.*;
import org.junit.jupiter.api.*;

class ConsumerGroupInspectionTests {
  Admin admin;
  static final String GROUP = ConsumerGroupInspection.EXPECTED.getFirst();
  static final TopicPartition TP = new TopicPartition("p360.catalogo.restaurantes.estado.v1", 0);

  @BeforeEach void setup() { admin = mock(Admin.class); }

  @AfterEach void onlyReadOperations() {
    assertThat(mockingDetails(admin).getInvocations()).allSatisfy(call ->
        assertThat(call.getMethod().getName()).isIn(
            "listConsumerGroups", "describeConsumerGroups", "listConsumerGroupOffsets", "listOffsets"));
  }

  void list(String... names) {
    var result = mock(ListConsumerGroupsResult.class);
    List<ConsumerGroupListing> groups = Arrays.stream(names).map(id -> {
      var group = mock(ConsumerGroupListing.class); when(group.groupId()).thenReturn(id); return group;
    }).toList();
    when(admin.listConsumerGroups()).thenReturn(result);
    when(result.all()).thenReturn(KafkaFuture.completedFuture(groups));
  }

  void description(Collection<MemberDescription> members, ConsumerGroupState state) {
    var result = mock(DescribeConsumerGroupsResult.class);
    var description = mock(ConsumerGroupDescription.class);
    when(description.members()).thenReturn(members); when(description.state()).thenReturn(state);
    when(admin.describeConsumerGroups(List.of(GROUP))).thenReturn(result);
    when(result.all()).thenReturn(KafkaFuture.completedFuture(Map.of(GROUP, description)));
  }

  void offsets(Map<TopicPartition, OffsetAndMetadata> values) {
    var result = mock(ListConsumerGroupOffsetsResult.class);
    when(admin.listConsumerGroupOffsets(GROUP)).thenReturn(result);
    when(result.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(values));
  }

  @Test void emptyCatalogDoesNotInventGroupMetrics() {
    list();
    assertThat(ConsumerGroupInspection.read(admin)).contains("GROUPS NO_VISIBLE_GROUPS",
        "GROUP " + GROUP + " NOT_LISTED").noneMatch(s -> s.contains("lag=0"));
    verify(admin, never()).describeConsumerGroups(anyCollection());
  }

  @Test void emptyRegisteredGroupHasNoMembersOrCommittedOffsets() {
    list(GROUP); description(List.of(), ConsumerGroupState.EMPTY); offsets(Map.of());
    assertThat(ConsumerGroupInspection.read(admin)).contains("GROUP " + GROUP + " " + ConsumerGroupState.EMPTY + " members=0",
        "MEMBERS " + GROUP + " NONE", "OFFSETS " + GROUP + " NONE");
    verify(admin, never()).listOffsets(anyMap());
  }

  @Test void activeGroupReportsAssignmentsAndLagWithoutMemberIdentityOrHost() {
    list(GROUP);
    var member = new MemberDescription("PRIVATE-MEMBER", "PRIVATE-CLIENT", "PRIVATE-HOST",
        new MemberAssignment(Set.of(TP)));
    description(List.of(member), ConsumerGroupState.STABLE);
    offsets(Map.of(TP, new OffsetAndMetadata(3)));
    var ends = mock(ListOffsetsResult.class);
    var end = mock(ListOffsetsResult.ListOffsetsResultInfo.class); when(end.offset()).thenReturn(5L);
    when(admin.listOffsets(anyMap())).thenReturn(ends);
    when(ends.all()).thenReturn(KafkaFuture.completedFuture(Map.of(TP, end)));
    String result = String.join("\n", ConsumerGroupInspection.read(admin));
    assertThat(result).contains(ConsumerGroupState.STABLE + " members=1", "assignment=[" + TP + "]", "committed=3 lag=2")
        .doesNotContain("PRIVATE-MEMBER", "PRIVATE-CLIENT", "PRIVATE-HOST");
    assertThat(result).containsPattern("member_hash=[a-f0-9]{64}");
  }

  @Test void assignedPartitionWithoutCommitIsUnknownNotZero() {
    list(GROUP);
    description(List.of(new MemberDescription("m", "c", "h", new MemberAssignment(Set.of(TP)))), ConsumerGroupState.STABLE);
    offsets(Map.of());
    assertThat(ConsumerGroupInspection.read(admin)).contains(
        "OFFSET " + GROUP + " " + TP + " committed=UNKNOWN lag=UNKNOWN");
  }

  @Test void groupDisappearingBetweenListAndDescribeIsExplicit() {
    list(GROUP);
    var result = mock(DescribeConsumerGroupsResult.class);
    when(admin.describeConsumerGroups(List.of(GROUP))).thenReturn(result);
    var future = new org.apache.kafka.common.internals.KafkaFutureImpl<Map<String, ConsumerGroupDescription>>();
    future.completeExceptionally(new GroupIdNotFoundException("PRIVATE-BROKER"));
    when(result.all()).thenReturn(future);
    assertThat(ConsumerGroupInspection.read(admin)).contains("GROUP " + GROUP + " MISSING_DURING_QUERY")
        .noneMatch(s -> s.contains("PRIVATE-BROKER"));
  }

  @Test void unlistedForeignGroupsAreNotDescribed() {
    list("foreign-group", "p360-bad\nname");
    assertThat(ConsumerGroupInspection.read(admin)).contains("GROUPS NO_VISIBLE_GROUPS");
    verify(admin, never()).describeConsumerGroups(anyCollection());
  }

  @Test void authorizationFailureIsNotReportedAsAnEmptySuccessfulResult() throws Exception {
    var result = mock(ListConsumerGroupsResult.class); when(admin.listConsumerGroups()).thenReturn(result);
    when(result.all()).thenThrow(new GroupAuthorizationException("PRIVATE-DETAIL"));
    assertThatThrownBy(() -> ConsumerGroupInspection.read(admin)).isInstanceOf(IllegalStateException.class)
        .hasMessage("Consumer group query failed; results incomplete").hasNoCause();
  }

  @Test void offsetQueryFailureDoesNotReturnPartialSuccess() {
    list(GROUP); description(List.of(), ConsumerGroupState.EMPTY);
    var result = mock(ListConsumerGroupOffsetsResult.class);
    when(admin.listConsumerGroupOffsets(GROUP)).thenReturn(result);
    when(result.partitionsToOffsetAndMetadata()).thenThrow(new TimeoutException("PRIVATE-DETAIL"));
    assertThatThrownBy(() -> ConsumerGroupInspection.read(admin)).hasMessage("Consumer group query failed; results incomplete");
  }
}
