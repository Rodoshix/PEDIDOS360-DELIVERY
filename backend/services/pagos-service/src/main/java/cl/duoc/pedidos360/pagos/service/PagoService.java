package cl.duoc.pedidos360.pagos.service;

import java.util.List;

import cl.duoc.pedidos360.pagos.messaging.RabbitProperties;
import cl.duoc.pedidos360.pagos.messaging.OutboxStore;
import cl.duoc.pedidos360.pagos.client.PedidoResumen;
import cl.duoc.pedidos360.pagos.client.PedidosClient;
import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
import cl.duoc.pedidos360.pagos.dto.PagoResponse;
import cl.duoc.pedidos360.pagos.entity.EstadoPago;
import cl.duoc.pedidos360.pagos.entity.MetodoPago;
import cl.duoc.pedidos360.pagos.entity.Pago;
import cl.duoc.pedidos360.pagos.exception.PagoException;
import cl.duoc.pedidos360.pagos.exception.PagoNoEncontradoException;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PagoService {

    /** Estados que se consideran "activos": impiden un segundo pago para el mismo pedido. */
    private static final List<EstadoPago> ACTIVOS = List.of(EstadoPago.PENDIENTE, EstadoPago.APROBADO);

    private final RabbitProperties messaging;
    private final OutboxStore outbox;
    private final PagoRepository pagos;
    private final PedidosClient pedidos;
    private final TransactionTemplate transaccion;
    private final cl.duoc.pedidos360.pagos.security.TenantSistema tenant;

    public PagoService(PagoRepository pagos, PedidosClient pedidos, PlatformTransactionManager txManager,
                       RabbitProperties messaging, OutboxStore outbox, cl.duoc.pedidos360.pagos.security.TenantSistema tenant) {
        this.tenant = tenant;
        this.messaging = messaging;
        this.outbox = outbox;
        this.pagos = pagos;
        this.pedidos = pedidos;
        this.transaccion = new TransactionTemplate(txManager);
    }

    /**
     * Registra un pago simulado de forma recuperable.
     *
     * <p>La consulta remota precede al commit local. En modo RABBITMQ, Pago e intención se guardan
     * juntos sin esperar al broker. Los pagos HTTP conservan la confirmación y reconciliación
     * existentes. El modo persistido impide coordinar el mismo pago por ambos transportes.
     *
     * <p>Autorización: el pedido debe pertenecer a la identidad autenticada (o ser ADMIN).
     * Idempotencia: la clave tiene alcance por identidad y debe corresponder a la misma operación.
     */
    public PagoResponse registrar(IdentidadUsuario identidad, String claveIdempotencia, CrearPagoRequest request) {
        var existente = pagos.findByTenantIdAndUsuarioIdAndClaveIdempotencia(identidad.tenantId(), identidad.usuarioId(), claveIdempotencia);
        if (existente.isPresent()) {
            return resolverReintento(existente.get(), request);
        }

        PedidoResumen pedido = pedidos.obtener(request.pedidoId());
        if (pedido == null) {
            throw new PagoException(HttpStatus.NOT_FOUND, "Pedido no encontrado: " + request.pedidoId());
        }
        validarPedido(identidad, pedido, request.pedidoId());
        if (!identidad.puedeAccederA(pedido.usuarioId())) {
            throw new PagoException(HttpStatus.FORBIDDEN,
                    "No puedes registrar un pago para un pedido de otro usuario.");
        }
        if (!"AUTHENTICATED_NEW".equals(pedido.tenantOrigin()))
            throw new PagoException(HttpStatus.CONFLICT,"Recurso histórico retenido.");
        ocultarColisiones(identidad, claveIdempotencia, request.pedidoId());
        if (pagos.existsByTenantIdAndPedidoIdAndEstadoIn(identidad.tenantId(),request.pedidoId(), ACTIVOS)) {
            // The same-key transaction can commit between the initial lookup and this check.
            var concurrente = pagos.findByTenantIdAndUsuarioIdAndClaveIdempotencia(identidad.tenantId(), identidad.usuarioId(), claveIdempotencia);
            if (concurrente.isPresent()) return resolverReintento(concurrente.get(), request);
            throw new PagoException(HttpStatus.CONFLICT,
                    "El pedido " + request.pedidoId() + " ya tiene un pago activo.");
        }

        Pago pago;
        try {
            pago = transaccion.execute(status -> {
                EstadoPago estadoInicial = resolverEstadoInicial(request.metodo());
                Pago nuevo = new Pago(pedido.tenantId(), request.pedidoId(), identidad.usuarioId(),
                        pedido.total(), pedido.moneda(), request.metodo(), estadoInicial, claveIdempotencia);
                nuevo.asignarCoordinacion(messaging.coordinationMode());
                pagos.saveAndFlush(nuevo);
                if (nuevo.getCoordinacion() == RabbitProperties.Mode.RABBITMQ) {
                    outbox.crear(nuevo);
                }
                return nuevo;
            });
        } catch (DataIntegrityViolationException error) {
            // Carrera: la misma clave (identidad) o un pago activo ya fue insertado por otra transacción.
            var porClave = pagos.findByTenantIdAndUsuarioIdAndClaveIdempotencia(identidad.tenantId(), identidad.usuarioId(), claveIdempotencia);
            if (porClave.isPresent()) {
                return resolverReintento(porClave.get(), request);
            }
            // An outbox constraint/storage failure is not a business conflict.
            ocultarColisiones(identidad, claveIdempotencia, request.pedidoId());
            if (!pagos.existsByTenantIdAndPedidoIdAndEstadoIn(identidad.tenantId(),request.pedidoId(), ACTIVOS)) throw error;
            throw new PagoException(HttpStatus.CONFLICT,
                    "El pedido " + request.pedidoId() + " ya tiene un pago activo.");
        }

        intentarConfirmacion(pago);
        return toResponse(buscar(identidad.tenantId(),pago.getId()));
    }

    @Transactional(readOnly = true)
    public PagoResponse obtener(IdentidadUsuario identidad, Long id) {
        Pago pago = buscar(identidad.tenantId(),id);
        if (!identidad.puedeAccederA(pago.getUsuarioId())) {
            throw new PagoException(HttpStatus.FORBIDDEN, "No tienes acceso a este pago.");
        }
        return toResponse(pago);
    }

    @Transactional(readOnly = true)
    public List<PagoResponse> listarPorPedido(IdentidadUsuario identidad, Long pedidoId) {
        PedidoResumen pedido = pedidos.obtener(pedidoId);
        if (pedido == null) {
            throw new PagoException(HttpStatus.NOT_FOUND, "Pedido no encontrado: " + pedidoId);
        }
        validarPedido(identidad, pedido, pedidoId);
        if (!identidad.puedeAccederA(pedido.usuarioId())) {
            throw new PagoException(HttpStatus.FORBIDDEN, "No tienes acceso a los pagos de este pedido.");
        }
        return pagos.findByTenantIdAndPedidoId(identidad.tenantId(),pedidoId).stream().map(this::toResponse).toList();
    }

    /** Aprueba/cobra un pago nuevo pendiente del tenant. Requiere ADMIN. */
    @Transactional
    public PagoResponse aprobar(IdentidadUsuario identidad, Long id) {
        Pago pago = buscar(identidad.tenantId(),id);
        if (!identidad.puedeAprobarCobros()) {
            throw new PagoException(HttpStatus.FORBIDDEN, "No tienes permiso para aprobar cobros.");
        }
        exigirNuevo(pago);
        if (pago.getEstado() != EstadoPago.PENDIENTE) {
            throw new PagoException(HttpStatus.CONFLICT,
                    "Solo un pago PENDIENTE puede aprobarse. Estado actual: " + pago.getEstado());
        }
        pago.aprobar();
        return toResponse(pagos.save(pago));
    }

    /**
     * Reintenta de forma idempotente las confirmaciones pendientes (reconciliación).
     * Es seguro llamarlo repetidamente: confirmar un pedido ya confirmado no falla.
     *
     * @return cantidad de pagos cuya confirmación quedó aplicada en esta pasada.
     */
    public int reconciliarConfirmacionesPendientes() {
        int recuperados = 0;
        for (Pago pago : pagos.findByTenantIdAndTenantOriginAndPedidoConfirmadoFalseAndEstadoInAndCoordinacion(tenant.obtener(), cl.duoc.pedidos360.pagos.entity.TenantOrigin.AUTHENTICATED_NEW, ACTIVOS, RabbitProperties.Mode.HTTP)) {
            if (intentarConfirmacion(pago)) {
                recuperados++;
            }
        }
        return recuperados;
    }

    /** Valida que la clave reutilizada corresponda a la misma operación (pedido y método). */
    private PagoResponse resolverReintento(Pago existente, CrearPagoRequest request) {
        exigirNuevo(existente);
        boolean mismaOperacion = existente.getPedidoId().equals(request.pedidoId())
                && existente.getMetodo() == request.metodo();
        if (!mismaOperacion) {
            throw new PagoException(HttpStatus.CONFLICT,
                    "La clave de idempotencia ya fue usada para otra operación.");
        }
        return toResponse(existente);
    }

    /** Confirma el pedido de forma recuperable; deja el estado pendiente si falla. */
    private boolean intentarConfirmacion(Pago pago) {
        if (!pago.esNuevoAutenticado() || !pago.getTenantId().equals(tenant.obtener())) return false;
        if (pago.getCoordinacion() != RabbitProperties.Mode.HTTP) return false;
        if (pago.isPedidoConfirmado()) {
            return true;
        }
        if (!pago.estaActivo()) {
            return false;
        }
        try {
            pedidos.confirmar(pago.getPedidoId());
        } catch (PagoException error) {
            return false; // pedido_confirmado queda en false; la reconciliación reintenta
        }
        transaccion.executeWithoutResult(status -> {
            var actual = buscar(pago.getTenantId(),pago.getId());
            exigirNuevo(actual);
            actual.marcarPedidoConfirmado();
            pagos.saveAndFlush(actual);
        });
        return true;
    }

    private Pago buscar(java.util.UUID tenantId,Long id) {
        java.util.Objects.requireNonNull(tenantId,"tenant");
        return pagos.findByTenantIdAndId(tenantId,id)
            .orElseThrow(() -> new PagoNoEncontradoException("Recurso no encontrado."));
    }
    private void exigirNuevo(Pago pago) {
        if (!pago.esNuevoAutenticado())
            throw new PagoException(HttpStatus.CONFLICT,"Recurso histórico retenido.");
    }
    private void validarPedido(IdentidadUsuario actor,PedidoResumen pedido,Long id) {
        if (!id.equals(pedido.pedidoId()))
            throw new PagoException(HttpStatus.BAD_GATEWAY,"Resumen inválido.");
        if (!actor.tenantId().equals(pedido.tenantId()))
            throw new PagoException(HttpStatus.NOT_FOUND,"Recurso no encontrado.");
    }
    private void ocultarColisiones(IdentidadUsuario actor,String clave,Long pedidoId) {
        if (pagos.existsByUsuarioIdAndClaveIdempotencia(actor.usuarioId(),clave)
            && pagos.findByTenantIdAndUsuarioIdAndClaveIdempotencia(actor.tenantId(),actor.usuarioId(),clave).isEmpty()
            || pagos.existsByPedidoIdAndEstadoIn(pedidoId,ACTIVOS)
            && !pagos.existsByTenantIdAndPedidoIdAndEstadoIn(actor.tenantId(),pedidoId,ACTIVOS))
            throw new PagoException(HttpStatus.NOT_FOUND,"Recurso no encontrado.");
    }

    /**
     * Resolución del pago simulado.
     * TARJETA se aprueba (simulado); EFECTIVO queda PENDIENTE hasta el cobro en la entrega.
     */
    private EstadoPago resolverEstadoInicial(MetodoPago metodo) {
        return metodo == MetodoPago.TARJETA ? EstadoPago.APROBADO : EstadoPago.PENDIENTE;
    }

    private PagoResponse toResponse(Pago pago) {
        return new PagoResponse(pago.getId(), pago.getPedidoId(), pago.getUsuarioId(), pago.getMonto(),
                pago.getMoneda(), pago.getMetodo().name(), pago.getEstado().name(), pago.getCreadoEn());
    }
}
