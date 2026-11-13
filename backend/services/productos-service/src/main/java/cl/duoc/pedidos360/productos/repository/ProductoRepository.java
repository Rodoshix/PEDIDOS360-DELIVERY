package cl.duoc.pedidos360.productos.repository;

import cl.duoc.pedidos360.productos.entity.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductoRepository extends JpaRepository<Producto, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Producto e where e.id = :id")
    java.util.Optional<Producto> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);


    List<Producto> findByRestauranteId(Long restauranteId);

    List<Producto> findByRestauranteIdAndDisponibleTrue(Long restauranteId);
}