package cl.duoc.pedidos360.seguimiento.security;

import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("seguimiento.identidad-local")
public record LocalIdentityProperties(
        boolean enabled, UUID tenantId, UUID objectId, Set<IdentidadUsuario.Rol> roles) {
}
