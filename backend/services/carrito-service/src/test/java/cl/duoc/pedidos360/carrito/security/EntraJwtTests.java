package cl.duoc.pedidos360.carrito.security;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.assertj.core.api.Assertions.*;

class EntraJwtTests {
    @Test void aceptaFirmaYClaimsValidos() {
        var jwt = EntraTestTokens.decoder().decode(EntraTestTokens.token(Map.of()));
        assertThat(jwt.getClaimAsString("oid")).isEqualTo(EntraTestTokens.USER);
        assertThat(EntraConfiguration.authorities(jwt)).extracting(Object::toString)
                .containsExactly("SCOPE_access_as_user", "ROLE_CLIENTE");
    }
    @TestFactory Collection<DynamicTest> rechazaClaimsInvalidos() {
        var cases = new LinkedHashMap<String, Object>();
        cases.put("iss", "https://attacker.invalid");
        cases.put("aud", EntraTestTokens.FRONTEND);
        cases.put("tid", EntraTestTokens.API);
        cases.put("oid", "no-es-uuid");
        cases.put("azp", EntraTestTokens.API);
        cases.put("ver", "1.0");
        cases.put("exp", Date.from(Instant.now().minusSeconds(180)));
        cases.put("nbf", Date.from(Instant.now().plusSeconds(180)));
        var tests = new ArrayList<DynamicTest>();
        cases.forEach((claim, value) -> tests.add(DynamicTest.dynamicTest(claim,
                () -> assertThatThrownBy(() -> EntraTestTokens.decoder().decode(
                        EntraTestTokens.token(Map.of(claim, value)))).isInstanceOf(JwtException.class))));
        for (String claim : List.of("exp", "nbf", "tid", "oid", "azp")) {
            tests.add(DynamicTest.dynamicTest("missing-" + claim, () -> {
                var missing = new HashMap<String, Object>();
                missing.put(claim, null);
                assertThatThrownBy(() -> EntraTestTokens.decoder().decode(EntraTestTokens.token(missing)))
                        .isInstanceOf(JwtException.class);
            }));
        }
        return tests;
    }
    @Test void rechazaFirmaDistinta() {
        assertThatThrownBy(() -> EntraTestTokens.decoder().decode(EntraTestTokens.token(Map.of(), EntraTestTokens.key())))
                .isInstanceOf(JwtException.class);
    }
    @Test void noConcedeAutoridadesPorClaimsDesconocidos() {
        var jwt = EntraTestTokens.decoder().decode(EntraTestTokens.token(Map.of("scp", "otro", "roles", List.of("SUPERADMIN"))));
        assertThat(EntraConfiguration.authorities(jwt)).isEmpty();
    }
    @Test void fallaConfiguracionIncompletaOIdentidadMixta() {
        var config = new EntraConfiguration();
        assertThatThrownBy(() -> config.entraDecoder(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.entraDecoder(new MockEnvironment()
                .withProperty("carrito.identidad-local.enabled", "true"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.entraDecoder(new MockEnvironment()
                .withProperty("entra.tenant-id", EntraTestTokens.TENANT)
                .withProperty("entra.api-client-id", EntraTestTokens.API)
                .withProperty("entra.frontend-client-id", EntraTestTokens.API))).isInstanceOf(IllegalStateException.class);
    }
}
