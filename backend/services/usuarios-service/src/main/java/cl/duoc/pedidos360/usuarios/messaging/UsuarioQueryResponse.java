package cl.duoc.pedidos360.usuarios.messaging;

import java.time.Instant;
import cl.duoc.pedidos360.usuarios.dto.UsuarioResponse;

/** Extensión interna v1; UsuarioResponse HTTP conserva sus ocho campos. */
public record UsuarioQueryResponse(Long id, String nombre, String apellido, String email, String telefono,
        boolean activo, Instant creadoEn, Instant actualizadoEn, String pruebaIdentidad) {
    public static UsuarioQueryResponse desde(UsuarioResponse p, String prueba) {
        return new UsuarioQueryResponse(p.id(), p.nombre(), p.apellido(), p.email(), p.telefono(),
                p.activo(), p.creadoEn(), p.actualizadoEn(), prueba);
    }
    @Override public String toString() { return "UsuarioQueryResponse[redacted]"; }
}
