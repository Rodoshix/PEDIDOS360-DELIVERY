package cl.duoc.pedidos360.pedidos.service;

import java.util.List;

import cl.duoc.pedidos360.pedidos.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.pedidos.dto.LineaPedidoRequest;
import cl.duoc.pedidos360.pedidos.dto.LineaPedidoResponse;
import cl.duoc.pedidos360.pedidos.dto.PedidoResponse;
import cl.duoc.pedidos360.pedidos.entity.EstadoPedido;
import cl.duoc.pedidos360.pedidos.entity.LineaPedido;
import cl.duoc.pedidos360.pedidos.entity.Pedido;
import cl.duoc.pedidos360.pedidos.exception.PedidoException;
import cl.duoc.pedidos360.pedidos.exception.PedidoNoEncontradoException;
import cl.duoc.pedidos360.pedidos.repository.LineaPedidoRepository;
import cl.duoc.pedidos360.pedidos.repository.PedidoRepository;
import cl.duoc.pedidos360.pedidos.security.IdentidadUsuario;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PedidoService {

    private final PedidoRepository pedidos;
    private final LineaPedidoRepository lineas;
    private final CatalogoPedidos catalogo;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cl.duoc.pedidos360.pedidos.messaging.carrito.CarritoOutboxStore carritoOutbox;

    public PedidoService(PedidoRepository pedidos, LineaPedidoRepository lineas, CatalogoPedidos catalogo) {
        this.pedidos = pedidos;
        this.lineas = lineas;
        this.catalogo = catalogo;
    }

    /** Crea un pedido para la identidad autenticada. Regla del MVP: un solo restaurante por pedido. */
    @Transactional
    public PedidoResponse crear(IdentidadUsuario identidad, CrearPedidoRequest request) {
        // El carrito envía los ítems; la regla "un restaurante por pedido" se valida aquí
        // porque es el backend quien crea el pedido. Los ítems deben ser del restaurante indicado.
        Pedido pedido = new Pedido(identidad.tenantId(), identidad.usuarioId(), request.restauranteId(),
                request.direccionEntrega(), "CLP");
        for (LineaPedidoRequest item : request.items()) {
            if (item == null || item.cantidad() < 1)
                throw new PedidoException(HttpStatus.BAD_REQUEST, "Cantidad inválida.");
            Long precio = catalogo.precio(item.productoId(), request.restauranteId());
            try { Math.addExact(pedido.getTotal(), Math.multiplyExact((long) item.cantidad(), precio)); }
            catch (ArithmeticException error) {
                throw new PedidoException(HttpStatus.BAD_REQUEST, "Total fuera de rango.");
            }
            pedido.agregarLinea(new LineaPedido(item.productoId(), item.cantidad(), precio));
        }
        return toResponse(pedidos.save(pedido));
    }

    @Transactional
    public PedidoResponse crearConCarrito(IdentidadUsuario identidad, CrearPedidoRequest request,
            CarritoSnapshotClient.Snapshot snapshot) {
        if (carritoOutbox == null || snapshot == null || !identidad.tenantId().equals(snapshot.tenant()))
            throw new PedidoException(HttpStatus.FORBIDDEN,"Coordinación de carrito no habilitada.");
        var response = crear(identidad,request);
        pedidos.flush();
        carritoOutbox.crear(pedidos.findById(response.pedidoId()).orElseThrow(),snapshot);
        return response;
    }

    /** Detalle del pedido: solo el propietario o ADMIN. */
    @Transactional(readOnly = true)
    public PedidoResponse obtener(IdentidadUsuario identidad, Long id) {
        Pedido pedido = buscar(identidad.tenantId(), id);
        exigirAcceso(identidad, pedido);
        return toResponse(pedido);
    }

    /** Historial de la identidad autenticada (ruta /pedidos/me). No recibe usuarioId del cliente. */
    @Transactional(readOnly = true)
    public List<PedidoResponse> listarPropios(IdentidadUsuario identidad) {
        return pedidos.findByTenantIdAndUsuarioId(identidad.tenantId(), identidad.usuarioId()).stream().map(this::toResponse).toList();
    }

    /** Historial de otro usuario: solo ADMIN o el propio usuario. */
    @Transactional(readOnly = true)
    public List<PedidoResponse> listarPorUsuario(IdentidadUsuario identidad, Long usuarioId) {
        if (!identidad.puedeAccederA(usuarioId)) {
            throw new PedidoException(HttpStatus.FORBIDDEN, "No tienes acceso a los pedidos de este usuario.");
        }
        return pedidos.findByTenantIdAndUsuarioId(identidad.tenantId(), usuarioId).stream().map(this::toResponse).toList();
    }

    /** Listado global: solo ADMIN (un CLIENTE usa /pedidos/me). */
    @Transactional(readOnly = true)
    public List<PedidoResponse> listar(IdentidadUsuario identidad) {
        if (!identidad.esAdmin()) {
            throw new PedidoException(HttpStatus.FORBIDDEN, "Solo ADMIN puede listar todos los pedidos.");
        }
        return pedidos.findByTenantId(identidad.tenantId()).stream().map(this::toResponse).toList();
    }

    /** Cambio de estado: solo ADMIN (gestión de restaurante/logística inicial). */
    @Transactional
    public PedidoResponse cambiarEstado(IdentidadUsuario identidad, Long id, EstadoPedido destino) {
        Pedido pedido = buscar(identidad.tenantId(), id);
        if (!identidad.puedeGestionarPedidos()) {
            throw new PedidoException(HttpStatus.FORBIDDEN, "No tienes permiso para cambiar el estado de pedidos.");
        }
        exigirNuevo(pedido);
        pedido.transicionarA(destino);
        return toResponse(pedidos.save(pedido));
    }

    /**
     * Confirmación de pago (solo aplicación worker, ruta interna).
     * Confirma CREADO → CONFIRMADO. Es idempotente: si el pedido ya está confirmado o en un
     * estado posterior, no hace nada. Lanza PedidoException(CONFLICT) si está CANCELADO y
     * PedidoNoEncontradoException si no existe. El llamante (/internal/...) traduce a 204/409/404.
     */
    @Transactional
    public void confirmarPorPago(java.util.UUID tenantId, Long id) {
        Pedido pedido = buscar(tenantId, id);
        exigirNuevo(pedido);
        if (pedido.getEstado() == EstadoPedido.CANCELADO) {
            throw new PedidoException(HttpStatus.CONFLICT,
                    "El pedido " + id + " está CANCELADO y no puede confirmarse.");
        }
        if (pedido.getEstado() == EstadoPedido.CREADO) {
            pedido.transicionarA(EstadoPedido.CONFIRMADO);
            pedidos.save(pedido);
        }
        // CONFIRMADO o posterior: nada que hacer (idempotente).
    }

    @Transactional(readOnly=true)
    public cl.duoc.pedidos360.pedidos.dto.PedidoResumenPagoResponse resumenParaPago(IdentidadUsuario identidad, Long id) {
        Pedido pedido=buscar(identidad.tenantId(), id);
        exigirAcceso(identidad,pedido);
        return new cl.duoc.pedidos360.pedidos.dto.PedidoResumenPagoResponse(pedido.getId(),
            pedido.getTenantId(),pedido.getUsuarioId(),pedido.getEstado().name(),pedido.getTotal(),pedido.getMoneda(),pedido.getTenantOrigin().name());
    }

    private void exigirNuevo(Pedido pedido) {
        if (!pedido.esNuevoAutenticado())
            throw new cl.duoc.pedidos360.pedidos.exception.PedidoHistoricoRetenidoException();
    }

    private void exigirAcceso(IdentidadUsuario identidad, Pedido pedido) {
        if (!identidad.puedeAccederA(pedido.getUsuarioId())) {
            throw new PedidoException(HttpStatus.FORBIDDEN, "No tienes acceso a este pedido.");
        }
    }

    private Pedido buscar(java.util.UUID tenantId, Long id) {
        java.util.Objects.requireNonNull(tenantId, "tenant");
        return pedidos.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new PedidoNoEncontradoException("Pedido no encontrado: " + id));
    }

    private PedidoResponse toResponse(Pedido pedido) {
        var lineas = pedido.getLineas().stream()
                .map(linea -> new LineaPedidoResponse(linea.getId(), linea.getProductoId(),
                        linea.getCantidad(), linea.getPrecioUnitario(), linea.getSubtotal()))
                .toList();
        return new PedidoResponse(pedido.getId(), pedido.getUsuarioId(), pedido.getRestauranteId(),
                pedido.getDireccionEntrega(), pedido.getEstado().name(), pedido.getTotal(),
                pedido.getMoneda(), pedido.getCreadoEn(), lineas);
    }
}
