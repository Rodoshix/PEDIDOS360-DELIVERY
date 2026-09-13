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
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Validación del token de aplicación (client_credentials) para el endpoint interno.
 *
 * <p>Política separada de la delegada: además de firma, vigencia, issuer y audiencia,
 * exige token <b>v2</b>, el <b>tid</b> del directorio, el rol de aplicación
 * {@code Pedidos.Confirmar}, el {@code azp} exacto del worker y la <b>ausencia</b> del
 * claim {@code scp} (un token app-only no representa a un usuario).
 * No se amplía el validador delegado para aceptar al worker.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SeguridadInternaProperties.class)
@ConditionalOnProperty(name = "pedidos.interno.enabled", havingValue = "true")
public class SeguridadInternaConfiguration {

    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_SCOPE = "scp";
    static final String CLAIM_VER = "ver";
    static final String CLAIM_TID = "tid";
    static final String VERSION_V2 = "2.0";

    @Bean
    JwtDecoder jwtDecoderInterno(SeguridadInternaProperties properties) {
        // Entra publica su JWK Set en el issuer; el decoder valida la firma contra esas claves.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.issuerUri() + "/discovery/v2.0/keys").build();
        decoder.setJwtValidator(validar(properties));
        return decoder;
    }

    /** Validadores de la política interna; se expone para poder probarlos sin claves reales. */
    static OAuth2TokenValidator<Jwt> validar(SeguridadInternaProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                // Firma (vía JWK), expiración/nbf e issuer configurado.
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()),
                // Token v2 (acuerdo #47).
                new JwtClaimValidator<String>(CLAIM_VER, VERSION_V2::equals),
                // Directorio esperado.
                new JwtClaimValidator<String>(CLAIM_TID,
                        tid -> properties.tenantId() != null && properties.tenantId().equals(tid)),
                // Audiencia = API registrada (GUID en token v2).
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience())),
                // Rol de aplicación otorgado al worker.
                new JwtClaimValidator<List<String>>(CLAIM_ROLES,
                        roles -> roles != null && roles.contains(properties.rolRequerido())),
                // Emisor autorizado exacto (client ID del worker).
                new JwtClaimValidator<String>("azp",
                        azp -> properties.workerClientId() != null && properties.workerClientId().equals(azp)),
                // Un token de aplicación no representa a un usuario: el claim scp debe estar AUSENTE.
                new JwtClaimValidator<String>(CLAIM_SCOPE, scope -> scope == null));
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
