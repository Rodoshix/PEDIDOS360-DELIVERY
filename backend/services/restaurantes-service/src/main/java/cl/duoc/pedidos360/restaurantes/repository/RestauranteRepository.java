package cl.duoc.pedidos360.restaurantes.repository;

import cl.duoc.pedidos360.restaurantes.entity.Restaurante;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RestauranteRepository extends JpaRepository<Restaurante, Long> {
}