package cl.duoc.pedidos360.usuarios.security;

import java.io.IOException;
import java.net.InetAddress;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Se instancia solo dentro de la cadena de seguridad, no como filtro servlet global.
final class LocalIdentityFilter extends OncePerRequestFilter {
    private final IdentidadUsuario identidad;

    LocalIdentityFilter(IdentidadUsuario identidad) {
        this.identidad = identidad;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
            var authorities = identidad.roles().stream()
                    .map(rol -> new SimpleGrantedAuthority("ROLE_" + rol.name())).toList();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    identidad, null, authorities));
            SecurityContextHolder.setContext(context);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
