package cl.duoc.pedidos360.pedidos.security;

import java.util.Set;

public record IdentidadUsuario(Long usuarioId, Set<Rol> roles) {
    public IdentidadUsuario {
        roles = Set.copyOf(roles);
    }

    public boolean esAdmin() {
        return roles.contains(Rol.ADMIN);
    }

    /** El recurso es accesible si lo posee esta identidad o si es ADMIN. */
    public boolean puedeAccederA(Long propietarioId) {
        return esAdmin() || usuarioId.equals(propietarioId);
    }

    /**
     * La gestión de estados del restaurante es inicialmente solo para ADMIN.
     * Un rol de restaurante/repartidor requeriría definir además su asignación.
     */
    public boolean puedeGestionarPedidos() {
        return esAdmin();
    }

    public enum Rol { CLIENTE, ADMIN }
}
