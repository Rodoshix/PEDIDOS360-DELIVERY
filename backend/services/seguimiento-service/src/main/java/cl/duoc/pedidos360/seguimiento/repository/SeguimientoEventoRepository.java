package cl.duoc.pedidos360.seguimiento.repository;

import java.util.List;

import cl.duoc.pedidos360.seguimiento.entity.SeguimientoEvento;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeguimientoEventoRepository extends JpaRepository<SeguimientoEvento, Long> {

    List<SeguimientoEvento> findBySeguimientoIdOrderByOcurridoEnAsc(Long seguimientoId);
}
