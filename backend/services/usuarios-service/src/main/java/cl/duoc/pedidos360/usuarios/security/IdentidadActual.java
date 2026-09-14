package cl.duoc.pedidos360.usuarios.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class IdentidadActual {

    public IdentidadUsuario obtener() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken token
                && token.isAuthenticated()) {
            var jwt = token.getToken();
            var roles = token.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .filter(role -> role.equals("ROLE_CLIENTE") || role.equals("ROLE_ADMIN"))
                    .map(role -> IdentidadUsuario.Rol.valueOf(role.substring(5)))
                    .collect(java.util.stream.Collectors.toSet());
            return new IdentidadUsuario(java.util.UUID.fromString(jwt.getClaimAsString("tid")),
                    java.util.UUID.fromString(jwt.getClaimAsString("oid")), roles);
        }
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof IdentidadUsuario identidad)) {
            throw new AuthenticationCredentialsNotFoundException("Se requiere una identidad validada.");
        }
        return identidad;
    }
}
