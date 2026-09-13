package cl.duoc.pedidos360.pedidos.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seguridad del endpoint interno (worker de Pagos).
 * Valores públicos (tenant/audiencia/client IDs), no credenciales.
 * El issuer y el JWK Set se derivan del tenant validado; {@code jwkSetUri} solo se usa
 * para pruebas locales con un JWKS propio y queda vacío en despliegue.
 */
@ConfigurationProperties("pedidos.interno")
public record SeguridadInternaProperties(
        boolean enabled,
        String tenantId,
        String audience,
        String workerClientId,
        String rolRequerido,
        String jwkSetUri) {
}
