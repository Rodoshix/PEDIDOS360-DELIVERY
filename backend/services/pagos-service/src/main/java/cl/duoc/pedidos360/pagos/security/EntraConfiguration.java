package cl.duoc.pedidos360.pagos.security;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** Contrato single-tenant v2. Mantener las mismas reglas en BFF, Usuarios y Carrito. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "entra.enabled", havingValue = "true")
public class EntraConfiguration {
    @Bean
    JwtDecoder entraDelegadoDecoder(Environment env) {
        if (env.getProperty("pagos.identidad-local.enabled", Boolean.class, false)) {
            throw new IllegalStateException("JWT e identidad local no pueden coexistir.");
        }
        String tenant = uuid(env.getRequiredProperty("entra.tenant-id"));
        String audience = uuid(env.getRequiredProperty("entra.api-client-id"));
        String frontend = uuid(env.getRequiredProperty("entra.frontend-client-id"));
        if (audience.equals(frontend)) throw new IllegalStateException("API y frontend requieren IDs distintos.");
        // Destino construido desde configuración validada, nunca desde claims del token.
        var decoder = NimbusJwtDecoder.withJwkSetUri(
                "https://login.microsoftonline.com/" + tenant + "/discovery/v2.0/keys").build();
        decoder.setJwtValidator(validators(tenant, audience, frontend));
        return decoder;
    }

    static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Se requiere un UUID completo.");
        return UUID.fromString(value).toString();
    }

    public static OAuth2TokenValidator<Jwt> validators(String tenant, String audience, String frontend) {
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                boolean valid = "2.0".equals(jwt.getClaimAsString("ver"))
                        && tenant.equals(uuid(jwt.getClaimAsString("tid")))
                        && audience.equals(jwt.getAudience().size() == 1 ? jwt.getAudience().getFirst() : "")
                        && frontend.equals(jwt.getClaimAsString("azp"))
                        && jwt.getExpiresAt() != null && jwt.getNotBefore() != null
                        && jwt.getExpiresAt().isAfter(jwt.getNotBefore());
                uuid(jwt.getClaimAsString("oid"));
                if (valid) return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ignored) {
                // Nunca incluir el token ni sus claims en errores.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token no válido para esta API.", null));
        };
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("https://login.microsoftonline.com/" + tenant + "/v2.0"), claims);
    }

    public static List<GrantedAuthority> authorities(Jwt jwt) {
        var result = new ArrayList<GrantedAuthority>();
        Object scopes = jwt.getClaims().get("scp");
        if (scopes instanceof String text && List.of(text.split(" ")).contains("access_as_user"))
            result.add(new SimpleGrantedAuthority("SCOPE_access_as_user"));
        Object roles = jwt.getClaims().get("roles");
        if (roles instanceof List<?> values) {
            for (String role : List.of("CLIENTE", "ADMIN"))
                if (values.contains(role)) result.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return result;
    }
}
