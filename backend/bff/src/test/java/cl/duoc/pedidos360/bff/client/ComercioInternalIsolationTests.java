package cl.duoc.pedidos360.bff.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ComercioInternalIsolationTests {
    @Test
    void internalProjectionNeverEntersPublicAllowlist() {
        for (String method : java.util.List.of("GET", "POST", "PUT", "DELETE", "PATCH")) {
            assertThat(ComercioClient.allowed(method, "/internal/pedidos/1/resumen-pago")).isFalse();
            assertThat(ComercioClient.allowed(method, "/internal/pedidos/1/confirmacion-pago")).isFalse();
        }
        assertThat(ComercioClient.allowed("GET", "/pedidos/1")).isTrue();
        assertThat(ComercioClient.allowed("GET", "/pagos/1")).isTrue();
    }
}
