package cl.duoc.pedidos360.restaurantes.repository;

import cl.duoc.pedidos360.restaurantes.entity.Restaurante;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RestauranteRepository extends JpaRepository<Restaurante, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Restaurante e where e.id = :id")
    java.util.Optional<Restaurante> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

}