package cl.duoc.pedidos360.pedidos.security;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pedidos.identidad-local")
public record LocalIdentityProperties(
        boolean enabled, Long usuarioId, Set<IdentidadUsuario.Rol> roles) {
}
