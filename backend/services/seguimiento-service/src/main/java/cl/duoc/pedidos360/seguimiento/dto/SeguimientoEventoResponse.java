package cl.duoc.pedidos360.seguimiento.dto;

import java.time.Instant;

import cl.duoc.pedidos360.seguimiento.entity.EstadoSeguimiento;
import cl.duoc.pedidos360.seguimiento.entity.SeguimientoEvento;

public record SeguimientoEventoResponse(
        Long id,
        EstadoSeguimiento estado,
        Instant ocurridoEn,
        String nota) {

    public static SeguimientoEventoResponse desde(SeguimientoEvento evento) {
        return new SeguimientoEventoResponse(evento.getId(), evento.getEstado(),
                evento.getOcurridoEn(), evento.getNota());
    }
}
