package cl.duoc.pedidos360.repartidores.dto;

import java.time.Instant;

import cl.duoc.pedidos360.repartidores.entity.EstadoDisponibilidad;
import cl.duoc.pedidos360.repartidores.entity.Repartidor;
import cl.duoc.pedidos360.repartidores.entity.Vehiculo;

public record RepartidorResponse(
        Long id,
        String nombre,
        String telefono,
        Vehiculo vehiculo,
        String zona,
        EstadoDisponibilidad estadoDisponibilidad,
        Instant creadoEn,
        Instant actualizadoEn,
        Long version) {

    public static RepartidorResponse desde(Repartidor repartidor) {
        return new RepartidorResponse(repartidor.getId(), repartidor.getNombre(),
                repartidor.getTelefono(), repartidor.getVehiculo(), repartidor.getZona(),
                repartidor.getEstadoDisponibilidad(), repartidor.getCreadoEn(),
                repartidor.getActualizadoEn(), repartidor.getVersion());
    }
}
