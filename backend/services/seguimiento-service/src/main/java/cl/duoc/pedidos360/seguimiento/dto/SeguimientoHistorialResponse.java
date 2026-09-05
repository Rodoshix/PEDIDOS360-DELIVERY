package cl.duoc.pedidos360.seguimiento.dto;

import java.util.List;

import cl.duoc.pedidos360.seguimiento.entity.EstadoSeguimiento;

public record SeguimientoHistorialResponse(
        Long pedidoId,
        EstadoSeguimiento estadoActual,
        List<SeguimientoEventoResponse> eventos) {
}
