package cl.duoc.pedidos360.repartidores.service;

import java.util.List;

import cl.duoc.pedidos360.repartidores.dto.AsignacionResponse;
import cl.duoc.pedidos360.repartidores.dto.AsignarPedidoRequest;
import cl.duoc.pedidos360.repartidores.dto.CambioEstadoAsignacionRequest;
import cl.duoc.pedidos360.repartidores.dto.DisponibilidadRequest;
import cl.duoc.pedidos360.repartidores.dto.PaginaRepartidores;
import cl.duoc.pedidos360.repartidores.dto.PerfilRepartidorRequest;
import cl.duoc.pedidos360.repartidores.dto.RepartidorResponse;
import cl.duoc.pedidos360.repartidores.entity.AsignacionRepartidor;
import cl.duoc.pedidos360.repartidores.entity.EstadoDisponibilidad;
import cl.duoc.pedidos360.repartidores.entity.Repartidor;
import cl.duoc.pedidos360.repartidores.exception.ApiException;
import cl.duoc.pedidos360.repartidores.repository.AsignacionRepartidorRepository;
import cl.duoc.pedidos360.repartidores.repository.RepartidorRepository;
import cl.duoc.pedidos360.repartidores.security.IdentidadActual;
import cl.duoc.pedidos360.repartidores.security.IdentidadUsuario;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RepartidorService {
    private final RepartidorRepository repartidores;
    private final AsignacionRepartidorRepository asignaciones;
    private final IdentidadActual identidadActual;

    public RepartidorService(RepartidorRepository repartidores,
                             AsignacionRepartidorRepository asignaciones,
                             IdentidadActual identidadActual) {
        this.repartidores = repartidores;
        this.asignaciones = asignaciones;
        this.identidadActual = identidadActual;
    }

    public PaginaRepartidores listar(int pagina, int tamanio) {
        var actor = actorActivo();
        if (!actor.esAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo ADMIN puede listar repartidores.");
        }
        var resultados = repartidores.findByTenantId(actor.tenantId(),
                PageRequest.of(pagina, tamanio, Sort.by("id")));
        return new PaginaRepartidores(
                resultados.map(RepartidorResponse::desde).getContent(),
                pagina, tamanio, resultados.getTotalElements(), resultados.getTotalPages());
    }

    public RepartidorResponse obtener(Long id) {
        return RepartidorResponse.desde(repartidorAccesible(id, actorActivo()));
    }

    public RepartidorResponse obtenerActual() {
        var actor = actorActivo();
        return repartidores.findByTenantIdAndEntraObjectId(actor.tenantId(), actor.objectId())
                .map(RepartidorResponse::desde)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Todavía no tienes un perfil de repartidor."));
    }

    @Transactional
    public RepartidorResponse crear(PerfilRepartidorRequest request) {
        var actor = actorActivo();
        if (repartidores.existsByTenantIdAndEntraObjectId(actor.tenantId(), actor.objectId())) {
            throw new ApiException(HttpStatus.CONFLICT, "La identidad ya tiene un perfil de repartidor.");
        }
        var repartidor = new Repartidor(actor.tenantId(), actor.objectId(),
                request.nombre(), request.telefono(), request.vehiculo(), request.zona());
        return RepartidorResponse.desde(repartidores.saveAndFlush(repartidor));
    }

    @Transactional
    public RepartidorResponse actualizar(Long id, PerfilRepartidorRequest request) {
        var repartidor = repartidorAccesible(id, actorActivo());
        repartidor.actualizarPerfil(request.nombre(), request.telefono(), request.vehiculo(), request.zona());
        repartidores.flush();
        return RepartidorResponse.desde(repartidor);
    }

    @Transactional
    public RepartidorResponse cambiarDisponibilidad(Long id, DisponibilidadRequest request) {
        var repartidor = repartidorAccesible(id, actorActivo());
        if (repartidor.getEstadoDisponibilidad() == request.estado()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El repartidor ya se encuentra " + request.estado() + ".");
        }
        repartidor.cambiarDisponibilidad(request.estado());
        repartidores.flush();
        return RepartidorResponse.desde(repartidor);
    }

    @Transactional
    public void desactivar(Long id) {
        var repartidor = repartidorAccesible(id, actorActivo());
        repartidor.cambiarDisponibilidad(EstadoDisponibilidad.INACTIVO);
        repartidores.flush();
    }

    public List<AsignacionResponse> listarAsignaciones(Long repartidorId) {
        var repartidor = repartidorAccesible(repartidorId, actorActivo());
        return asignaciones.findByRepartidorIdOrderByAsignadaEnAsc(repartidor.getId())
                .stream().map(AsignacionResponse::desde).toList();
    }

    @Transactional
    public AsignacionResponse asignarPedido(Long repartidorId, AsignarPedidoRequest request) {
        var repartidor = repartidorAccesible(repartidorId, actorActivo());
        if (asignaciones.existsByPedidoId(request.pedidoId())) {
            throw new ApiException(HttpStatus.CONFLICT, "El pedido ya está asignado a un repartidor.");
        }
        var asignacion = asignaciones.saveAndFlush(new AsignacionRepartidor(
                repartidor.getId(), request.pedidoId(), request.nota()));
        return AsignacionResponse.desde(asignacion);
    }

    @Transactional
    public AsignacionResponse cambiarEstadoAsignacion(Long repartidorId, Long pedidoId,
                                                      CambioEstadoAsignacionRequest request) {
        var repartidor = repartidorAccesible(repartidorId, actorActivo());
        var asignacion = asignaciones.findByPedidoId(pedidoId)
                .filter(a -> a.getRepartidorId().equals(repartidor.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No se encontró la asignación del pedido " + pedidoId + "."));
        if (asignacion.getEstado() == request.estado()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "La asignación ya se encuentra " + request.estado() + ".");
        }
        asignacion.cambiarEstado(request.estado());
        asignaciones.flush();
        return AsignacionResponse.desde(asignacion);
    }

    private IdentidadUsuario actorActivo() {
        return identidadActual.obtener();
    }

    private Repartidor repartidorAccesible(Long id, IdentidadUsuario actor) {
        if (!actor.esAdmin()) {
            return repartidores.findByTenantIdAndEntraObjectId(actor.tenantId(), actor.objectId())
                    .filter(r -> r.getId().equals(id))
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                            "Solo puedes acceder a tu propio perfil de repartidor."));
        }
        return repartidores.findById(id)
                .filter(r -> r.getTenantId().equals(actor.tenantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Repartidor no encontrado."));
    }
}
