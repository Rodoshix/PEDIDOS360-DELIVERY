package cl.duoc.pedidos360.pedidos.repository;

import java.util.List;

import cl.duoc.pedidos360.pedidos.entity.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    List<Pedido> findByUsuarioId(Long usuarioId);
}
