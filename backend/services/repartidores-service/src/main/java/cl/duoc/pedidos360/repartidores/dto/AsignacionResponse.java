package cl.duoc.pedidos360.repartidores.dto;

import java.time.Instant;

import cl.duoc.pedidos360.repartidores.entity.AsignacionRepartidor;
import cl.duoc.pedidos360.repartidores.entity.EstadoAsignacion;

public record AsignacionResponse(
        Long id,
        Long pedidoId,
        EstadoAsignacion estado,
        Instant asignadaEn,
        String nota) {

    public static AsignacionResponse desde(AsignacionRepartidor asignacion) {
        return new AsignacionResponse(asignacion.getId(), asignacion.getPedidoId(),
                asignacion.getEstado(), asignacion.getAsignadaEn(), asignacion.getNota());
    }
}
