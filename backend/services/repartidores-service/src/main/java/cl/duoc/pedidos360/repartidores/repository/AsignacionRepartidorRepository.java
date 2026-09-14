package cl.duoc.pedidos360.repartidores.repository;

import java.util.List;
import java.util.Optional;

import cl.duoc.pedidos360.repartidores.entity.AsignacionRepartidor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AsignacionRepartidorRepository extends JpaRepository<AsignacionRepartidor, Long> {

    List<AsignacionRepartidor> findByRepartidorIdOrderByAsignadaEnAsc(Long repartidorId);

    Optional<AsignacionRepartidor> findByPedidoId(Long pedidoId);

    boolean existsByPedidoId(Long pedidoId);
}
