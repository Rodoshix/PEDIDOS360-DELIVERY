package cl.duoc.pedidos360.pedidos.security;

import java.util.Set;

public record IdentidadUsuario(Long usuarioId, Set<Rol> roles) {
    public IdentidadUsuario {
        roles = Set.copyOf(roles);
    }

    public boolean esAdmin() {
        return roles.contains(Rol.ADMIN);
    }

    public enum Rol { CLIENTE, ADMIN }
}
