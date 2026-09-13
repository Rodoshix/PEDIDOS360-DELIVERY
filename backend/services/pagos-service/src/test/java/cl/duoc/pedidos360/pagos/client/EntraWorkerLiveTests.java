package cl.duoc.pedidos360.pagos.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in; nunca imprime el token ni lo usa en mensajes de aserción. */
@EnabledIfEnvironmentVariable(named = "RUN_ENTRA_WORKER_LIVE", matches = "true")
class EntraWorkerLiveTests {
    @Test void autenticaConProveedorReal() {
        var env = new org.springframework.mock.env.MockEnvironment()
            .withProperty("pagos.worker.tenant-id", System.getenv("ENTRA_TENANT_ID"))
            .withProperty("pagos.worker.api-client-id", System.getenv("ENTRA_API_CLIENT_ID"))
            .withProperty("pagos.worker.client-id", System.getenv("PAGOS_WORKER_CLIENT_ID"))
            .withProperty("pagos.worker.client-secret", System.getenv("PAGOS_WORKER_CLIENT_SECRET"));
        var provider = new EntraWorkerTokenProvider(env);
        String token = provider.token();
        assertThat(token != null && !token.isBlank()).isTrue();
        assertThat(token.equals(provider.token())).isTrue();
    }
}
