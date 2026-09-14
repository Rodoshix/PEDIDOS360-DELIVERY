package cl.duoc.pedidos360.pagos.repository;

import java.util.List;
import java.util.Optional;

import cl.duoc.pedidos360.pagos.entity.EstadoPago;
import cl.duoc.pedidos360.pagos.entity.Pago;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PagoRepository extends JpaRepository<Pago, Long> {

    /** Clave de idempotencia con alcance por identidad. */
    Optional<Pago> findByUsuarioIdAndClaveIdempotencia(Long usuarioId, String claveIdempotencia);

    List<Pago> findByPedidoId(Long pedidoId);

    boolean existsByPedidoIdAndEstadoIn(Long pedidoId, List<EstadoPago> estados);

    /** Pagos activos cuya coordinación con Pedidos todavía no se aplicó (para reconciliación). */
    List<Pago> findByPedidoConfirmadoFalseAndEstadoIn(List<EstadoPago> estados);
}
