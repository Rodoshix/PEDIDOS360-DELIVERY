package cl.duoc.pedidos360.repartidores.dto;

import cl.duoc.pedidos360.repartidores.entity.Vehiculo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PerfilRepartidorRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 120, message = "El nombre admite hasta 120 caracteres.") String nombre,
        @Size(max = 30, message = "El teléfono admite hasta 30 caracteres.") String telefono,
        @NotNull(message = "El vehículo es obligatorio.") Vehiculo vehiculo,
        @Size(max = 100, message = "La zona admite hasta 100 caracteres.") String zona) {

    public PerfilRepartidorRequest {
        nombre = nombre == null ? null : nombre.strip();
        telefono = telefono == null || telefono.isBlank() ? null : telefono.strip();
        zona = zona == null || zona.isBlank() ? null : zona.strip();
    }
}
