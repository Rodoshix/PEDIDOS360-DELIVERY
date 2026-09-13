package cl.duoc.pedidos360.pagos.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class IdentidadActual {
    private final UpstreamSeguro upstream;
    private final java.net.URI usuarios;
    public IdentidadActual(UpstreamSeguro upstream, org.springframework.core.env.Environment env) {
        this.upstream = upstream;
        this.usuarios = UpstreamSeguro.origen(env.getProperty("entra.usuarios-url", "http://localhost:8081"));
    }

    public IdentidadUsuario obtener() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken token
                && token.isAuthenticated()) {
            var authorities = token.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority).toList();
            if (!authorities.contains("SCOPE_access_as_user")
                || !(authorities.contains("ROLE_CLIENTE") || authorities.contains("ROLE_ADMIN")))
                throw new org.springframework.security.access.AccessDeniedException("Se requiere identidad delegada.");
            var profile = upstream.get(usuarios.resolve("/usuarios/me"), token.getToken().getTokenValue());
            var id = profile.get("id");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1
                || !profile.path("activo").isBoolean() || !profile.path("activo").booleanValue())
                throw new org.springframework.security.access.AccessDeniedException("Perfil no habilitado.");
            var roles = authorities.stream().filter(r -> r.equals("ROLE_CLIENTE") || r.equals("ROLE_ADMIN"))
                .map(r -> IdentidadUsuario.Rol.valueOf(r.substring(5))).collect(java.util.stream.Collectors.toSet());
            return new IdentidadUsuario(id.longValue(), roles);
        }
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof IdentidadUsuario identidad)) {
            throw new AuthenticationCredentialsNotFoundException("Se requiere una identidad validada.");
        }
        return identidad;
    }
}
