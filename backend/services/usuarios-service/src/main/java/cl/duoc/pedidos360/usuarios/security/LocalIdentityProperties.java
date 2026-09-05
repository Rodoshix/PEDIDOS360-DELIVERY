package cl.duoc.pedidos360.usuarios.security;

import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("usuarios.identidad-local")
public record LocalIdentityProperties(
        boolean enabled, UUID tenantId, UUID objectId, Set<IdentidadUsuario.Rol> roles) {
}
