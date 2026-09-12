package cl.duoc.pedidos360.bff;

import cl.duoc.pedidos360.bff.config.ConnectionConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ConnectionConfigurationTests {
    @ParameterizedTest
    @ValueSource(strings = {"*", "null", "http://example.com", "https://user:pass@example.com",
        "https://example.com/path", "https://example.com?x=1", "https://example.com#hash", "http://localhost:0"})
    void rechazaOrigenInseguroOConRuta(String value) {
        assertThatThrownBy(() -> ConnectionConfiguration.origin(value)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aceptaHttpsOLoopback() {
        assertThat(ConnectionConfiguration.origin("https://example.com").getHost()).isEqualTo("example.com");
        assertThat(ConnectionConfiguration.origin("http://127.0.0.1:8081").getPort()).isEqualTo(8081);
    }
}
