package cl.duoc.pedidos360.pedidos.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seguridad del endpoint interno (worker de Pagos).
 * Valores públicos (issuer/audiencia/client IDs), no credenciales.
 */
@ConfigurationProperties("pedidos.interno")
public record SeguridadInternaProperties(
        boolean enabled,
        String issuerUri,
        String tenantId,
        String audience,
        String workerClientId,
        String rolRequerido) {
}
