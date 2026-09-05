package cl.duoc.pedidos360.usuarios.dto;

import java.util.Locale;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PerfilRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre admite hasta 100 caracteres.") String nombre,
        @NotBlank(message = "El apellido es obligatorio.")
        @Size(max = 100, message = "El apellido admite hasta 100 caracteres.") String apellido,
        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no tiene un formato válido.")
        @Size(max = 254, message = "El email admite hasta 254 caracteres.") String email,
        @Size(max = 30, message = "El teléfono admite hasta 30 caracteres.") String telefono) {

    public PerfilRequest {
        nombre = nombre == null ? null : nombre.strip();
        apellido = apellido == null ? null : apellido.strip();
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        telefono = telefono == null || telefono.isBlank() ? null : telefono.strip();
    }
}
