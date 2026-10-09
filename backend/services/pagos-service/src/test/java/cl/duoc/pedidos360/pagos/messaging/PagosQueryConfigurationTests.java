package cl.duoc.pedidos360.pagos.messaging;

import cl.duoc.pedidos360.pagos.PagosQueryKeys;
import cl.duoc.pedidos360.messaging.relay.QueryConsumer;
import cl.duoc.pedidos360.messaging.identity.IdentityProofVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class PagosQueryConfigurationTests {
    final PagosQueryConfiguration config=new PagosQueryConfiguration();
    MockEnvironment env() { return new MockEnvironment()
            .withProperty("pedidos360.messaging.identity-proof.enabled","true")
            .withProperty("pedidos360.messaging.identity-proof.public-jwks",PagosQueryKeys.proofs())
            .withProperty("pedidos360.messaging.actor.public-jwks",PagosQueryKeys.actors()); }
    @Test void disabledDefaultDoesNotRegisterListenerOrSecondConnection() {
        new ApplicationContextRunner().withUserConfiguration(PagosQueryConfiguration.class).run(context->{
            assertThat(context).hasNotFailed().doesNotHaveBean(QueryConsumer.class).doesNotHaveBean(IdentityProofVerifier.class);
            assertThat(context).doesNotHaveBean("queryConnectionFactory").doesNotHaveBean("queryRabbitTemplate");
        });
    }
    @ParameterizedTest @ValueSource(strings={"disabled","private","missing","reused","zeroMargin","largeMargin"})
    void proofConfigurationFailsClosedWithoutFallback(String condition) {
        var e=env();
        switch(condition) {
            case "disabled" -> e.setProperty("pedidos360.messaging.identity-proof.enabled","false");
            case "private" -> e.setProperty("pedidos360.messaging.identity-proof.private-jwk","forbidden");
            case "missing" -> e.setProperty("pedidos360.messaging.identity-proof.public-jwks","");
            case "reused" -> e.setProperty("pedidos360.messaging.identity-proof.public-jwks",PagosQueryKeys.actors());
            case "zeroMargin" -> e.setProperty("pedidos360.messaging.identity-proof.clock-margin","0ms");
            case "largeMargin" -> e.setProperty("pedidos360.messaging.identity-proof.clock-margin","1001ms");
        }
        assertThatThrownBy(()->config.pagosIdentityProofVerifier(e)).isInstanceOf(RuntimeException.class);
    }
    @Test void pagosCannotBecomeActorIssuerThroughRoleOrPrivateKeyConfiguration() {
        assertThatThrownBy(()->config.queryActorVerifier(env().withProperty("pedidos360.messaging.role","BFF")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->config.queryActorVerifier(env().withProperty("pedidos360.messaging.actor.private-jwk","forbidden")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(config.queryActorVerifier(env()).claveVigente()).isEmpty();
        assertThat(config.pagosIdentityProofVerifier(env())).isNotNull();
    }
}
