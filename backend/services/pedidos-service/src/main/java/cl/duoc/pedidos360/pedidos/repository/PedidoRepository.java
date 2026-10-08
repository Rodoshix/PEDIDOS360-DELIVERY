package cl.duoc.pedidos360.pedidos.repository;

import java.util.List;

import cl.duoc.pedidos360.pedidos.entity.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    List<Pedido> findByTenantIdAndUsuarioId(java.util.UUID tenantId, Long usuarioId);
    List<Pedido> findByTenantId(java.util.UUID tenantId);
    java.util.Optional<Pedido> findByTenantIdAndId(java.util.UUID tenantId, Long id);
}
