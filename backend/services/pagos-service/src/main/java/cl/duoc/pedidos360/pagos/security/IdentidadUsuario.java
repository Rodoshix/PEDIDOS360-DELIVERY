package cl.duoc.pedidos360.pagos.security;

import java.util.Set;

public record IdentidadUsuario(Long usuarioId, Set<Rol> roles) {
    public IdentidadUsuario {
        roles = Set.copyOf(roles);
    }

    public boolean esAdmin() {
        return roles.contains(Rol.ADMIN);
    }

    /**
     * Permiso para aprobar/cobrar un pago (p. ej. efectivo recibido en la entrega).
     * Según el acuerdo con I1/I5, inicialmente solo ADMIN: un rol de repartidor
     * requeriría definir además su asignación, no solo el nombre del rol.
     */
    public boolean puedeAprobarCobros() {
        return roles.contains(Rol.ADMIN);
    }

    /** El recurso es accesible si lo posee esta identidad o si es ADMIN. */
    public boolean puedeAccederA(Long propietarioId) {
        return esAdmin() || usuarioId.equals(propietarioId);
    }

    public enum Rol { CLIENTE, REPARTIDOR, ADMIN }
}
