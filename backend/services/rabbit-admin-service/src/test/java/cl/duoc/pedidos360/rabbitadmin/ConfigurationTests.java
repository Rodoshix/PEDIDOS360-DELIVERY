package cl.duoc.pedidos360.rabbitadmin;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConfigurationTests {
    @Test void emptyAndDefaultExchangeNamesAreInvalid() {
        assertThatThrownBy(() -> SandboxRules.name("")).isInstanceOf(AdminFailure.class)
            .satisfies(e -> assertThat(((AdminFailure)e).status()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> SandboxRules.name(null)).isInstanceOf(AdminFailure.class);
    }
    @Test void absentEntraConfigurationFailsClosed() {
        assertThatThrownBy(() -> new EntraConfiguration().entraDecoder(new MockEnvironment()))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void disabledFlagCannotBypassRequiredEntraConfiguration() {
        assertThatThrownBy(() -> new EntraConfiguration().entraDecoder(new MockEnvironment().withProperty("entra.enabled","false")))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void sharedFrontendAudienceIsRejected() {
        var env=new MockEnvironment().withProperty("entra.tenant-id",RabbitAdminIntegrationTests.TENANT)
            .withProperty("entra.api-client-id",RabbitAdminIntegrationTests.API)
            .withProperty("entra.frontend-client-id",RabbitAdminIntegrationTests.API);
        assertThatThrownBy(() -> new EntraConfiguration().entraDecoder(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingPasswordAndBroadAccountAreRejectedWithoutSecrets() {
        for(var env:java.util.List.of(new MockEnvironment().withProperty("rabbit-admin.password",""),
            new MockEnvironment().withProperty("rabbit-admin.username","guest").withProperty("rabbit-admin.password","secret-sentinel"))) {
            assertThatThrownBy(() -> new BrokerConfiguration().rabbitConnectionFactory(env))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret-sentinel");
        }
    }
}
