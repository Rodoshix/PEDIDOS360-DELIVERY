package cl.duoc.pedidos360.pagos.repository;

import java.util.List;
import java.util.Optional;

import cl.duoc.pedidos360.pagos.entity.EstadoPago;
import cl.duoc.pedidos360.pagos.entity.Pago;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PagoRepository extends JpaRepository<Pago, Long> {

    /** Clave de idempotencia con alcance por identidad. */
    Optional<Pago> findByTenantIdAndUsuarioIdAndClaveIdempotencia(java.util.UUID tenantId, Long usuarioId, String claveIdempotencia);
    Optional<Pago> findByTenantIdAndId(java.util.UUID tenantId, Long id);
    boolean existsByUsuarioIdAndClaveIdempotencia(Long usuarioId,String claveIdempotencia);

    List<Pago> findByTenantIdAndPedidoId(java.util.UUID tenantId,Long pedidoId);

    boolean existsByPedidoIdAndEstadoIn(Long pedidoId, List<EstadoPago> estados);
    boolean existsByTenantIdAndPedidoIdAndEstadoIn(java.util.UUID tenantId,Long pedidoId,List<EstadoPago> estados);

    /** Pagos activos cuya coordinación con Pedidos todavía no se aplicó (para reconciliación). */
    List<Pago> findByTenantIdAndTenantOriginAndPedidoConfirmadoFalseAndEstadoInAndCoordinacion(java.util.UUID tenantId, cl.duoc.pedidos360.pagos.entity.TenantOrigin origin, List<EstadoPago> estados,
            cl.duoc.pedidos360.pagos.messaging.RabbitProperties.Mode coordinacion);
}
