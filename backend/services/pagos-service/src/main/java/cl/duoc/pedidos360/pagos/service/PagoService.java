package cl.duoc.pedidos360.pagos.service;

import java.util.List;

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

    private final PagoRepository pagos;
    private final PedidosClient pedidos;
    private final TransactionTemplate transaccion;

    public PagoService(PagoRepository pagos, PedidosClient pedidos, PlatformTransactionManager txManager) {
        this.pagos = pagos;
        this.pedidos = pedidos;
        this.transaccion = new TransactionTemplate(txManager);
    }

    /**
     * Registra un pago simulado de forma recuperable.
     *
     * <p>No usa una transacción que abarque la llamada remota: primero persiste el pago (commit local)
     * y luego intenta confirmar el pedido. Si la confirmación falla o se pierde la respuesta, el pago
     * queda persistido con {@code pedido_confirmado=false} para que la reconciliación lo reintente.
     *
     * <p>Autorización: el pedido debe pertenecer a la identidad autenticada (o ser ADMIN).
     * Idempotencia: la clave tiene alcance por identidad y debe corresponder a la misma operación.
     */
    public PagoResponse registrar(IdentidadUsuario identidad, String claveIdempotencia, CrearPagoRequest request) {
        var existente = pagos.findByUsuarioIdAndClaveIdempotencia(identidad.usuarioId(), claveIdempotencia);
        if (existente.isPresent()) {
            return resolverReintento(existente.get(), request);
        }

        PedidoResumen pedido = pedidos.obtener(request.pedidoId());
        if (pedido == null) {
            throw new PagoException(HttpStatus.NOT_FOUND, "Pedido no encontrado: " + request.pedidoId());
        }
        if (!identidad.puedeAccederA(pedido.usuarioId())) {
            throw new PagoException(HttpStatus.FORBIDDEN,
                    "No puedes registrar un pago para un pedido de otro usuario.");
        }
        if (pagos.existsByPedidoIdAndEstadoIn(request.pedidoId(), ACTIVOS)) {
            throw new PagoException(HttpStatus.CONFLICT,
                    "El pedido " + request.pedidoId() + " ya tiene un pago activo.");
        }

        Pago pago;
        try {
            pago = transaccion.execute(status -> {
                EstadoPago estadoInicial = resolverEstadoInicial(request.metodo());
                return pagos.saveAndFlush(new Pago(request.pedidoId(), identidad.usuarioId(),
                        pedido.total(), pedido.moneda(), request.metodo(), estadoInicial, claveIdempotencia));
            });
        } catch (DataIntegrityViolationException error) {
            // Carrera: la misma clave (identidad) o un pago activo ya fue insertado por otra transacción.
            var porClave = pagos.findByUsuarioIdAndClaveIdempotencia(identidad.usuarioId(), claveIdempotencia);
            if (porClave.isPresent()) {
                return resolverReintento(porClave.get(), request);
            }
            throw new PagoException(HttpStatus.CONFLICT,
                    "El pedido " + request.pedidoId() + " ya tiene un pago activo.");
        }

        intentarConfirmacion(pago);
        return toResponse(recargar(pago.getId()));
    }

    @Transactional(readOnly = true)
    public PagoResponse obtener(IdentidadUsuario identidad, Long id) {
        Pago pago = buscar(id);
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
        if (!identidad.puedeAccederA(pedido.usuarioId())) {
            throw new PagoException(HttpStatus.FORBIDDEN, "No tienes acceso a los pagos de este pedido.");
        }
        return pagos.findByPedidoId(pedidoId).stream().map(this::toResponse).toList();
    }

    /** Aprueba/cobra un pago pendiente. Requiere permiso explícito (REPARTIDOR o ADMIN). */
    @Transactional
    public PagoResponse aprobar(IdentidadUsuario identidad, Long id) {
        if (!identidad.puedeAprobarCobros()) {
            throw new PagoException(HttpStatus.FORBIDDEN, "No tienes permiso para aprobar cobros.");
        }
        Pago pago = buscar(id);
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
        for (Pago pago : pagos.findByPedidoConfirmadoFalseAndEstadoIn(ACTIVOS)) {
            if (intentarConfirmacion(pago)) {
                recuperados++;
            }
        }
        return recuperados;
    }

    /** Valida que la clave reutilizada corresponda a la misma operación (pedido y método). */
    private PagoResponse resolverReintento(Pago existente, CrearPagoRequest request) {
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
            var actual = pagos.findById(pago.getId()).orElseThrow();
            actual.marcarPedidoConfirmado();
            pagos.saveAndFlush(actual);
        });
        return true;
    }

    private Pago buscar(Long id) {
        return pagos.findById(id)
                .orElseThrow(() -> new PagoNoEncontradoException("Pago no encontrado: " + id));
    }

    private Pago recargar(Long id) {
        return pagos.findById(id)
                .orElseThrow(() -> new PagoNoEncontradoException("Pago no encontrado: " + id));
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
