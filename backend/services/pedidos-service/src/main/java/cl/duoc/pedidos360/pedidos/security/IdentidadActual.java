package cl.duoc.pedidos360.pedidos.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class IdentidadActual {

    public IdentidadUsuario obtener() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof IdentidadUsuario identidad)) {
            throw new AuthenticationCredentialsNotFoundException("Se requiere una identidad validada.");
        }
        return identidad;
    }
}
