package cl.duoc.pedidos360.carrito.repository;

import java.util.Optional;
import java.util.UUID;

import cl.duoc.pedidos360.carrito.entity.Carrito;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarritoRepository extends JpaRepository<Carrito, Long> {

    @EntityGraph(attributePaths = "lineas")
    Optional<Carrito> findByTenantIdAndEntraObjectId(UUID tenantId, UUID entraObjectId);
}
