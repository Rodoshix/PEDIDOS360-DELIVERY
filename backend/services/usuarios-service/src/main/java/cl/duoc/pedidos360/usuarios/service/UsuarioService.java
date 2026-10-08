package cl.duoc.pedidos360.usuarios.service;

import cl.duoc.pedidos360.usuarios.dto.PaginaUsuarios;
import cl.duoc.pedidos360.usuarios.dto.PerfilRequest;
import cl.duoc.pedidos360.usuarios.dto.UsuarioResponse;
import cl.duoc.pedidos360.usuarios.entity.Usuario;
import cl.duoc.pedidos360.usuarios.exception.ApiException;
import cl.duoc.pedidos360.usuarios.repository.UsuarioRepository;
import cl.duoc.pedidos360.usuarios.security.IdentidadActual;
import cl.duoc.pedidos360.usuarios.security.IdentidadUsuario;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UsuarioService {
    private final UsuarioRepository usuarios;
    private final IdentidadActual identidadActual;

    public UsuarioService(UsuarioRepository usuarios, IdentidadActual identidadActual) {
        this.usuarios = usuarios;
        this.identidadActual = identidadActual;
    }

    public PaginaUsuarios listar(int pagina, int tamanio) {
        var actor = actorActivo();
        if (!actor.esAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo ADMIN puede listar perfiles.");
        }
        var resultados = usuarios.findByTenantId(actor.tenantId(),
                PageRequest.of(pagina, tamanio, Sort.by("id")));
        return new PaginaUsuarios(resultados.map(UsuarioResponse::desde).getContent(),
                pagina, tamanio, resultados.getTotalElements(), resultados.getTotalPages());
    }

    public UsuarioResponse obtener(Long id) {
        return UsuarioResponse.desde(perfilAccesible(id, actorActivo()));
    }

    public UsuarioResponse obtenerActual() {
        return obtenerActual(identidadActual.obtener());
    }

    /** Identidad previamente verificada por el transporte; no depende de contexto HTTP. */
    public UsuarioResponse obtenerActual(IdentidadUsuario identidad) {
        return resolverActual(identidad).perfil();
    }

    /** Marca capturada inmediatamente antes del SELECT: la latencia de lectura consume la vigencia. */
    public PerfilActualVerificado resolverActual(IdentidadUsuario identidad) {
        var verificadoEn = cl.duoc.pedidos360.messaging.identity.IdentityProofCodec.millis(java.time.Instant.now());
        var usuario = usuarios.findByTenantIdAndEntraObjectId(identidad.tenantId(), identidad.objectId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Todavía no tienes un perfil."));
        if (!usuario.getTenantId().equals(identidad.tenantId()) || !usuario.getEntraObjectId().equals(identidad.objectId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "La identidad no corresponde al perfil.");
        if (!usuario.isActivo()) throw new ApiException(HttpStatus.FORBIDDEN, "El perfil está desactivado.");
        return new PerfilActualVerificado(usuario.getTenantId(), usuario.getEntraObjectId(),
                UsuarioResponse.desde(usuario), verificadoEn);
    }

    @Transactional
    public UsuarioResponse crear(PerfilRequest request) {
        var actor = actorActivo();
        if (usuarios.existsByTenantIdAndEntraObjectId(actor.tenantId(), actor.objectId())) {
            throw new ApiException(HttpStatus.CONFLICT, "La identidad ya tiene un perfil.");
        }
        var usuario = new Usuario(actor.tenantId(), actor.objectId(),
                request.nombre(), request.apellido(), request.email(), request.telefono());
        return UsuarioResponse.desde(usuarios.saveAndFlush(usuario));
    }

    @Transactional
    public UsuarioResponse actualizar(Long id, PerfilRequest request) {
        var usuario = perfilAccesible(id, actorActivo());
        if (!usuario.isActivo()) {
            throw new ApiException(HttpStatus.CONFLICT, "No se puede editar un perfil desactivado.");
        }
        usuario.actualizarPerfil(request.nombre(), request.apellido(), request.email(), request.telefono());
        usuarios.flush();
        return UsuarioResponse.desde(usuario);
    }

    @Transactional
    public void desactivar(Long id) {
        var usuario = perfilAccesible(id, actorActivo());
        usuario.desactivar();
        usuarios.flush();
    }

    private IdentidadUsuario actorActivo() {
        return actorActivo(identidadActual.obtener());
    }

    private IdentidadUsuario actorActivo(IdentidadUsuario actor) {
        usuarios.findByTenantIdAndEntraObjectId(actor.tenantId(), actor.objectId())
                .filter(usuario -> !usuario.isActivo())
                .ifPresent(usuario -> {
                    throw new ApiException(HttpStatus.FORBIDDEN, "El perfil está desactivado.");
                });
        return actor;
    }

    private Usuario perfilAccesible(Long id, IdentidadUsuario actor) {
        if (!actor.esAdmin()) {
            // Resolver por identidad evita revelar si existe el ID de otro usuario.
            return usuarios.findByTenantIdAndEntraObjectId(actor.tenantId(), actor.objectId())
                    .filter(usuario -> usuario.getId().equals(id))
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                            "Solo puedes acceder a tu propio perfil."));
        }
        return usuarios.findById(id).filter(usuario -> usuario.getTenantId().equals(actor.tenantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Perfil no encontrado."));
    }
}
