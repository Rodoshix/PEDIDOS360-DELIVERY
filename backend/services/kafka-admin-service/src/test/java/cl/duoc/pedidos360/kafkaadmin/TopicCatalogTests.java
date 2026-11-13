package cl.duoc.pedidos360.kafkaadmin;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TopicCatalogTests {
  @Test
  void onlyApprovedTopicsAndTerminalDestinations() {
    assertThat(TopicCatalog.topics()).hasSize(4);
    assertThat(TopicCatalog.topics())
        .allSatisfy(
            s -> {
              assertThat(s.partitions()).isEqualTo(3);
              assertThat(s.replicas()).isEqualTo((short) 1);
            });
  }

  @Test
  void rejectsArbitraryTopicsAndUnsupportedTopology() {
    assertThatThrownBy(() -> new TopicCatalog.Spec("another", 3, (short) 1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TopicCatalog.Spec(
                    "p360.catalogo.restaurantes.estado.v1", 1, (short) 1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
