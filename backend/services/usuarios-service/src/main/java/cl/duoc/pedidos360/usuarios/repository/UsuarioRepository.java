package cl.duoc.pedidos360.usuarios.repository;

import java.util.Optional;
import java.util.UUID;

import cl.duoc.pedidos360.usuarios.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByTenantIdAndEntraObjectId(UUID tenantId, UUID entraObjectId);

    boolean existsByTenantIdAndEntraObjectId(UUID tenantId, UUID entraObjectId);
}
