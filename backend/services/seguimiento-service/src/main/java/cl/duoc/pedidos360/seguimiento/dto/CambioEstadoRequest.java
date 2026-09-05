package cl.duoc.pedidos360.seguimiento.dto;

import cl.duoc.pedidos360.seguimiento.entity.EstadoSeguimiento;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CambioEstadoRequest(
        @NotNull(message = "El nuevo estado es obligatorio.") EstadoSeguimiento estado,
        @Size(max = 500, message = "La nota admite hasta 500 caracteres.") String nota) {
}
