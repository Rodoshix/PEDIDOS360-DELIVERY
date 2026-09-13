package cl.duoc.pedidos360.pedidos.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica la política JWT del endpoint interno (acuerdo #47) sobre el validador REAL
 * de SeguridadInternaConfiguration, construyendo tokens con los claims de un token de aplicación.
 */
class SeguridadInternaTests {

    private static final String TENANT = "a048ca4e-cd7f-4a01-a43e-cb4deccf1ff2";
    private static final String ISSUER = "https://login.microsoftonline.com/" + TENANT + "/v2.0";
    private static final String AUD = "13c0f63f-2007-41c4-8d9f-02640b8a1886";
    private static final String WORKER = "worker-client-id";
    private static final String ROL = "Pedidos.Confirmar";

    private final SeguridadInternaProperties properties =
            new SeguridadInternaProperties(true, TENANT, AUD, WORKER, ROL, null);

    private OAuth2TokenValidator<Jwt> validador() {
        return SeguridadInternaConfiguration.validar(properties, TENANT);
    }

    private Jwt token(Map<String, Object> overrides) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("worker")
                .issuedAt(Instant.now().minusSeconds(30))
                .notBefore(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("ver", "2.0")
                .claim("tid", TENANT)
                .claim("aud", List.of(AUD))
                .claim("roles", List.of(ROL))
                .claim("azp", WORKER);
        overrides.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void tokenDeAplicacionValidoSeAcepta() {
        // La prueba positiva debe comprobar que NO hay errores (validate no lanza excepción).
        assertThat(validador().validate(token(Map.of())).hasErrors()).isFalse();
    }

    @Test
    void tokenDelegadoConScpSeRechaza() {
        // Un token de usuario (con scp) no debe acceder al endpoint interno.
        assertThat(validador().validate(token(Map.of("scp", "access_as_user"))).hasErrors()).isTrue();
    }

    @Test
    void tokenV1SeRechaza() {
        assertThat(validador().validate(token(Map.of("ver", "1.0"))).hasErrors()).isTrue();
    }

    @Test
    void otroDirectorioSeRechaza() {
        assertThat(validador().validate(token(Map.of("tid", "00000000-0000-0000-0000-000000000000"))).hasErrors()).isTrue();
    }

    @Test
    void audienciaIncorrectaSeRechaza() {
        assertThat(validador().validate(token(Map.of("aud", List.of("otra-api")))).hasErrors()).isTrue();
    }

    @Test
    void otroEmisorAzpSeRechaza() {
        assertThat(validador().validate(token(Map.of("azp", "frontend-client-id"))).hasErrors()).isTrue();
    }

    @Test
    void sinElRolRequeridoSeRechaza() {
        assertThat(validador().validate(token(Map.of("roles", List.of("Otro.Rol")))).hasErrors()).isTrue();
    }

    @Test
    void tokenExpiradoSeRechaza() {
        Jwt expirado = Jwt.withTokenValue("token").header("alg", "RS256").issuer(ISSUER).subject("worker")
                .issuedAt(Instant.now().minusSeconds(1200))
                .notBefore(Instant.now().minusSeconds(1200)).expiresAt(Instant.now().minusSeconds(600))
                .claim("ver", "2.0").claim("tid", TENANT).claim("aud", List.of(AUD))
                .claim("roles", List.of(ROL)).claim("azp", WORKER).build();

        assertThat(validador().validate(expirado).hasErrors()).isTrue();
    }

    @Test
    void issuerIncorrectoSeRechaza() {
        Jwt otroIssuer = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer("https://login.microsoftonline.com/otro-tenant/v2.0").subject("worker")
                .issuedAt(Instant.now().minusSeconds(30))
                .notBefore(Instant.now().minusSeconds(30)).expiresAt(Instant.now().plusSeconds(300))
                .claim("ver", "2.0").claim("tid", TENANT).claim("aud", List.of(AUD))
                .claim("roles", List.of(ROL)).claim("azp", WORKER).build();

        assertThat(validador().validate(otroIssuer).hasErrors()).isTrue();
    }

    // --- Configuración del JWKS (evita duplicar /v2.0) ---

    @Test
    void elJwkSetNoDuplicaLaVersionEnLaRuta() {
        String jwk = SeguridadInternaConfiguration.jwkSetUri(TENANT);

        assertThat(jwk).isEqualTo("https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys");
        assertThat(jwk).doesNotContain("/v2.0/discovery/v2.0/keys");
    }

    @Test
    void elIssuerDerivadoCoincideConElDeEntra() {
        assertThat(SeguridadInternaConfiguration.entraBase(TENANT) + "/v2.0").isEqualTo(ISSUER);
    }

    @Test
    void unTenantQueNoEsUuidSeRechaza() {
        assertThatThrownBy(() -> SeguridadInternaConfiguration.uuid("no-es-un-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
