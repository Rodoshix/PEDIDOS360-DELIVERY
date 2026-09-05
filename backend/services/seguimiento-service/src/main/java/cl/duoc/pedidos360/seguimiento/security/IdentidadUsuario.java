package cl.duoc.pedidos360.seguimiento.security;

import java.util.Set;
import java.util.UUID;

public record IdentidadUsuario(UUID tenantId, UUID objectId, Set<Rol> roles) {
    public IdentidadUsuario {
        roles = Set.copyOf(roles);
    }

    public boolean esAdmin() {
        return roles.contains(Rol.ADMIN);
    }

    public enum Rol { CLIENTE, ADMIN }
}
