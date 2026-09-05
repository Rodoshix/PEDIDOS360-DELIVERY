package cl.duoc.pedidos360.usuarios.repository;

import java.util.Optional;
import java.util.UUID;

import cl.duoc.pedidos360.usuarios.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByTenantIdAndEntraObjectId(UUID tenantId, UUID entraObjectId);

    boolean existsByTenantIdAndEntraObjectId(UUID tenantId, UUID entraObjectId);

    Page<Usuario> findByTenantId(UUID tenantId, Pageable pageable);
}
