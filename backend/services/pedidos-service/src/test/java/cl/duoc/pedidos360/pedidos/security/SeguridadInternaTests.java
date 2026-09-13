package cl.duoc.pedidos360.pedidos.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifica la política JWT del endpoint interno (acuerdo #47) sobre el validador REAL
 * de SeguridadInternaConfiguration, construyendo tokens con los claims de un token de aplicación.
 */
class SeguridadInternaTests {

    private static final String ISSUER = "https://login.microsoftonline.com/tenant-test/v2.0";
    private static final String TENANT = "tenant-test";
    private static final String AUD = "api-test-guid";
    private static final String WORKER = "worker-client-id";
    private static final String ROL = "Pedidos.Confirmar";

    private final SeguridadInternaProperties properties =
            new SeguridadInternaProperties(true, ISSUER, TENANT, AUD, WORKER, ROL);

    private OAuth2TokenValidator<Jwt> validador() {
        return SeguridadInternaConfiguration.validar(properties);
    }

    private Jwt token(Map<String, Object> overrides) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("worker")
                .issuedAt(Instant.now().minusSeconds(30))
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
        assertThatCode(() -> validador().validate(token(Map.of()))).doesNotThrowAnyException();
    }

    @Test
    void tokenDelegadoConScpSeRechaza() {
        // Un token de usuario (con scp) no debe acceder al endpoint interno.
        var resultado = validador().validate(token(Map.of("scp", "access_as_user")));
        assertThat(resultado.hasErrors()).isTrue();
    }

    @Test
    void tokenV1SeRechaza() {
        var resultado = validador().validate(token(Map.of("ver", "1.0")));
        assertThat(resultado.hasErrors()).isTrue();
    }

    @Test
    void otroDirectorioSeRechaza() {
        var resultado = validador().validate(token(Map.of("tid", "otro-tenant")));
        assertThat(resultado.hasErrors()).isTrue();
    }

    @Test
    void audienciaIncorrectaSeRechaza() {
        var resultado = validador().validate(token(Map.of("aud", List.of("otra-api"))));
        assertThat(resultado.hasErrors()).isTrue();
    }

    @Test
    void otroEmisorAzpSeRechaza() {
        var resultado = validador().validate(token(Map.of("azp", "frontend-client-id")));
        assertThat(resultado.hasErrors()).isTrue();
    }

    @Test
    void sinElRolRequeridoSeRechaza() {
        var resultado = validador().validate(token(Map.of("roles", List.of("Otro.Rol"))));
        assertThat(resultado.hasErrors()).isTrue();
    }

    @Test
    void tokenExpiradoSeRechaza() {
        Jwt expirado = Jwt.withTokenValue("token").header("alg", "RS256").issuer(ISSUER).subject("worker")
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(60))
                .claim("ver", "2.0").claim("tid", TENANT).claim("aud", List.of(AUD))
                .claim("roles", List.of(ROL)).claim("azp", WORKER).build();

        assertThat(validador().validate(expirado).hasErrors()).isTrue();
    }

    @Test
    void issuerIncorrectoSeRechaza() {
        Jwt otroIssuer = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer("https://login.microsoftonline.com/otro/v2.0").subject("worker")
                .issuedAt(Instant.now().minusSeconds(30)).expiresAt(Instant.now().plusSeconds(300))
                .claim("ver", "2.0").claim("tid", TENANT).claim("aud", List.of(AUD))
                .claim("roles", List.of(ROL)).claim("azp", WORKER).build();

        assertThat(validador().validate(otroIssuer).hasErrors()).isTrue();
    }
}
