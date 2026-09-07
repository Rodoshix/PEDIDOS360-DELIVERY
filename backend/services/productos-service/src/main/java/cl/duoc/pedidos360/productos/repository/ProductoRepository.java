package cl.duoc.pedidos360.productos.repository;

import cl.duoc.pedidos360.productos.entity.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductoRepository extends JpaRepository<Producto, Long> {

    List<Producto> findByRestauranteId(Long restauranteId);

    List<Producto> findByRestauranteIdAndDisponibleTrue(Long restauranteId);
}