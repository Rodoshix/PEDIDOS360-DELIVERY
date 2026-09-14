package cl.duoc.pedidos360.seguimiento.dto;

import java.time.Instant;

import cl.duoc.pedidos360.seguimiento.entity.EstadoSeguimiento;
import cl.duoc.pedidos360.seguimiento.entity.Seguimiento;

public record SeguimientoResponse(
        Long id,
        Long pedidoId,
        EstadoSeguimiento estadoActual,
        Instant creadoEn,
        Instant actualizadoEn,
        Long version) {

    public static SeguimientoResponse desde(Seguimiento seguimiento) {
        return new SeguimientoResponse(seguimiento.getId(), seguimiento.getPedidoId(),
                seguimiento.getEstadoActual(), seguimiento.getCreadoEn(),
                seguimiento.getActualizadoEn(), seguimiento.getVersion());
    }
}
