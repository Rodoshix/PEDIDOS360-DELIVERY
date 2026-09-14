package cl.duoc.pedidos360.pagos.security;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pagos.identidad-local")
public record LocalIdentityProperties(
        boolean enabled, Long usuarioId, Set<IdentidadUsuario.Rol> roles) {
}
