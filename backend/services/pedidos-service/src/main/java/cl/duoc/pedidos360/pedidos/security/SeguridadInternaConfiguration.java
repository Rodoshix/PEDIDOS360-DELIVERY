package cl.duoc.pedidos360.pedidos.security;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Validación del token de aplicación (client_credentials) para el endpoint interno.
 *
 * <p>Política separada de la delegada: además de firma, vigencia, issuer y audiencia,
 * exige el rol de aplicación {@code Pedidos.Confirmar}, el {@code azp} exacto del worker
 * y la AUSENCIA de {@code scp} (un token app-only no representa a un usuario).
 * No se amplía el validador delegado para aceptar al worker.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SeguridadInternaProperties.class)
@ConditionalOnProperty(name = "pedidos.interno.enabled", havingValue = "true")
public class SeguridadInternaConfiguration {

    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_SCOPE = "scp";

    @Bean
    JwtDecoder jwtDecoderInterno(SeguridadInternaProperties properties) {
        JwtDecoder decoder = JwtDecoders.fromIssuerLocation(properties.issuerUri());
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<List<String>>(CLAIM_ROLES,
                        roles -> roles != null && roles.contains(properties.rolRequerido())),
                new JwtClaimValidator<String>("azp",
                        azp -> properties.workerClientId().equals(azp)),
                new JwtClaimValidator<String>(CLAIM_SCOPE,
                        scope -> scope == null || scope.isBlank()));
        ((org.springframework.security.oauth2.jwt.NimbusJwtDecoder) decoder).setJwtValidator(validators);
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverterInterno() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(CLAIM_ROLES);
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
