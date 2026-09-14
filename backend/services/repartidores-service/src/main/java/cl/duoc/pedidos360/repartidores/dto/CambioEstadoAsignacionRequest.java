package cl.duoc.pedidos360.repartidores.dto;

import cl.duoc.pedidos360.repartidores.entity.EstadoAsignacion;
import jakarta.validation.constraints.NotNull;

public record CambioEstadoAsignacionRequest(
        @NotNull(message = "El estado de la asignación es obligatorio.") EstadoAsignacion estado) {
}
