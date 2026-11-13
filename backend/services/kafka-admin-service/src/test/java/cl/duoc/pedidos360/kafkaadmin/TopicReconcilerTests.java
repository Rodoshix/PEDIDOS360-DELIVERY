package cl.duoc.pedidos360.kafkaadmin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;

class TopicReconcilerTests {
  @Test
  void planDoesNotWriteAndApplyCreatesOnlyDeclaredTopics() throws Exception {
    Admin admin = mock(Admin.class);
    var topics = mock(ListTopicsResult.class);
    when(admin.listTopics()).thenReturn(topics);
    when(topics.names()).thenReturn(KafkaFuture.completedFuture(Set.of()));
    var created = mock(CreateTopicsResult.class);
    when(admin.createTopics(anyCollection())).thenReturn(created);
    when(created.all()).thenReturn(KafkaFuture.completedFuture(null));
    var reconciler = new TopicReconciler(admin);
    assertThat(reconciler.reconcile(false)).hasSize(4).allMatch(x -> x.startsWith("CREATE p360.catalogo."));
    verify(admin, never()).createTopics(anyCollection());
    assertThat(reconciler.reconcile(true)).hasSize(4);
    verify(admin, times(4)).createTopics(anyCollection());
  }

  @Test
  void topologyDriftAbortsBeforeEarlierPlannedCreates() {
    Admin admin = mock(Admin.class);
    var topics = mock(ListTopicsResult.class);
    String existing = "p360.catalogo.productos.disponibilidad.v1";
    when(admin.listTopics()).thenReturn(topics);
    when(topics.names()).thenReturn(KafkaFuture.completedFuture(Set.of(existing)));
    var result = mock(DescribeTopicsResult.class);
    var description = mock(TopicDescription.class);
    when(description.partitions()).thenReturn(List.of(mock(TopicPartitionInfo.class), mock(TopicPartitionInfo.class)));
    when(admin.describeTopics(anyCollection())).thenReturn(result);
    when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of(existing, description)));
    assertThatThrownBy(() -> new TopicReconciler(admin).reconcile(true)).isInstanceOf(IllegalStateException.class);
    verify(admin, never()).createTopics(anyCollection());
    verify(admin, never()).incrementalAlterConfigs(anyMap());
  }
}
