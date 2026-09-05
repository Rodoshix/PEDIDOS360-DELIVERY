package cl.duoc.pedidos360.seguimiento.repository;

import java.util.Optional;

import cl.duoc.pedidos360.seguimiento.entity.Seguimiento;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeguimientoRepository extends JpaRepository<Seguimiento, Long> {

    Optional<Seguimiento> findByPedidoId(Long pedidoId);

    boolean existsByPedidoId(Long pedidoId);
}
