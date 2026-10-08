package cl.duoc.pedidos360.usuarios.messaging;

import cl.duoc.pedidos360.messaging.actor.SigningKey;
import cl.duoc.pedidos360.messaging.identity.IdentityProofKeys;
import com.nimbusds.jose.jwk.ECKey;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "relay-mode", havingValue = "ACTIVE")
class UsuariosIdentityProofConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "pedidos360.messaging.identity-proof", name = "enabled", havingValue = "true")
    UsuariosIdentityProofSigner usuariosIdentityProofSigner(Environment env) {
        return signer(env);
    }
    static UsuariosIdentityProofSigner signer(Environment env) {
        String prefix = "pedidos360.messaging.identity-proof.";
        try {
            if (!"SERVICE".equals(env.getProperty("pedidos360.messaging.role", "SERVICE"))) throw new IllegalArgumentException();
            String privateJwk = env.getProperty(prefix + "private-jwk", "");
            if (privateJwk.isBlank() || privateJwk.length() > 8192) throw new IllegalArgumentException();
            tools.jackson.databind.json.JsonMapper.builder()
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readTree(privateJwk);
            var ec = ECKey.parse(privateJwk);
            var key = new SigningKey(UUID.fromString(ec.getKeyID()), ec);
            if (!key.keyId().toString().equals(env.getProperty(prefix + "key-id"))) throw new IllegalArgumentException();
            var keys = IdentityProofKeys.load(env.getProperty(prefix + "public-jwks"), env.getProperty("pedidos360.messaging.actor.public-jwks"));
            return new UsuariosIdentityProofSigner(key, keys, Clock.systemUTC(), env.getProperty(prefix + "clock-margin", Duration.class, Duration.ofMillis(250)));
        } catch (Exception invalid) { throw new IllegalStateException("prueba habilitada exige privada propia y públicas válidas de Usuarios"); }
    }
}
