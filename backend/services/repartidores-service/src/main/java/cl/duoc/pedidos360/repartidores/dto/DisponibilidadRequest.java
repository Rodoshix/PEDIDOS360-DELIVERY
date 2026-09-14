package cl.duoc.pedidos360.repartidores.dto;

import cl.duoc.pedidos360.repartidores.entity.EstadoDisponibilidad;
import jakarta.validation.constraints.NotNull;

public record DisponibilidadRequest(
        @NotNull(message = "El estado de disponibilidad es obligatorio.") EstadoDisponibilidad estado) {
}
