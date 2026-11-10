package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EventCodecTests {
  private final EventCodec codec = new EventCodec();

  @Test
  void bothContractsRoundTrip() {
    for (String topic : java.util.List.of(EventCodec.RESTAURANTES, EventCodec.PRODUCTOS)) {
      String body =
          codec.encode(
              topic, 42, topic.equals(EventCodec.RESTAURANTES) ? "CERRADO" : "false", 2, "CHANGED");
      var event = codec.decode(topic, "42", body);
      assertThat(event.aggregateVersion()).isEqualTo(2);
      assertThat(EventCodec.state(event)).isIn("CERRADO", "false");
    }
  }

  @Test
  void additionalFieldsCannotChangeContractMeaning() {
    String product = codec.encode(EventCodec.PRODUCTOS, 42, "false", 1, "INITIALIZED");
    String restaurant = codec.encode(EventCodec.RESTAURANTES, 42, "CERRADO", 1, "INITIALIZED");
    for (String extra : java.util.List.of("\"true\"", "null", "{}")) {
      String contradictory = product.replace("\"disponible\":false", "\"disponible\":false,\"estado\":" + extra);
      assertThat(EventCodec.state(codec.decode(EventCodec.PRODUCTOS, "42", contradictory))).isEqualTo("false");
    }
    assertThat(EventCodec.state(codec.decode(EventCodec.RESTAURANTES, "42",
        restaurant.replace("\"estado\":\"CERRADO\"", "\"estado\":\"CERRADO\",\"disponible\":true")))).isEqualTo("CERRADO");
    assertThatThrownBy(() -> codec.decode(EventCodec.PRODUCTOS, "42", product.replace("\"disponible\":false", "\"estado\":\"true\"")))
        .isInstanceOf(EventCodec.InvalidEvent.class);
    assertThatThrownBy(() -> codec.decode(EventCodec.RESTAURANTES, "42", restaurant.replace("\"estado\":\"CERRADO\"", "\"disponible\":true")))
        .isInstanceOf(EventCodec.InvalidEvent.class);
  }

  @Test
  void rejectsInvalidBoundaries() {
    String good = codec.encode(EventCodec.PRODUCTOS, 42, "true", 1, "INITIALIZED");
    for (String body :
        java.util.List.of(
            "null",
            "{}",
            good.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            good.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"),
            good.replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"schemaVersion\":1"),
            good.replace("\"disponible\":true", "\"disponible\":\"true\""),
            good.replace("INITIALIZED", "BOGUS"),
            good.replace("\"aggregateVersion\":1", "\"aggregateVersion\":0"),
            " ".repeat(16385)))
      assertThatThrownBy(() -> codec.decode(EventCodec.PRODUCTOS, "42", body))
          .isInstanceOf(EventCodec.InvalidEvent.class);
    assertThatThrownBy(() -> codec.decode(EventCodec.PRODUCTOS, "43", good))
        .isInstanceOf(EventCodec.InvalidEvent.class);
    assertThatThrownBy(() -> codec.encode(EventCodec.RESTAURANTES, 42, "BORRADO", 1, "CHANGED"))
        .isInstanceOf(EventCodec.InvalidEvent.class);
  }
}
