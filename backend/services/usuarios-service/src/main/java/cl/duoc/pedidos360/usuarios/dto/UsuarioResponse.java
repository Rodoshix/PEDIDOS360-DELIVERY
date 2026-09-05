package cl.duoc.pedidos360.usuarios.dto;

import java.time.Instant;
import cl.duoc.pedidos360.usuarios.entity.Usuario;

public record UsuarioResponse(Long id, String nombre, String apellido, String email,
        String telefono, boolean activo, Instant creadoEn, Instant actualizadoEn) {

    public static UsuarioResponse desde(Usuario usuario) {
        return new UsuarioResponse(usuario.getId(), usuario.getNombre(), usuario.getApellido(),
                usuario.getEmail(), usuario.getTelefono(), usuario.isActivo(),
                usuario.getCreadoEn(), usuario.getActualizadoEn());
    }
}
