package cl.duoc.pedidos360.repartidores.repository;

import java.util.Optional;
import java.util.UUID;

import cl.duoc.pedidos360.repartidores.entity.Repartidor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepartidorRepository extends JpaRepository<Repartidor, Long> {

    Optional<Repartidor> findByTenantIdAndEntraObjectId(UUID tenantId, UUID entraObjectId);

    boolean existsByTenantIdAndEntraObjectId(UUID tenantId, UUID entraObjectId);
}
