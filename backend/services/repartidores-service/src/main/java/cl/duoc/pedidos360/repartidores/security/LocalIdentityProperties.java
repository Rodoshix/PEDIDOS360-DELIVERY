package cl.duoc.pedidos360.repartidores.security;

import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("repartidores.identidad-local")
public record LocalIdentityProperties(
        boolean enabled, UUID tenantId, UUID objectId, Set<IdentidadUsuario.Rol> roles) {
}
