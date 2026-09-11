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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PagoService {

    /** Estados que se consideran "activos": impiden un segundo pago para el mismo pedido. */
    private static final List<EstadoPago> ACTIVOS = List.of(EstadoPago.PENDIENTE, EstadoPago.APROBADO);

    private final PagoRepository pagos;
    private final PedidosClient pedidos;

    public PagoService(PagoRepository pagos, PedidosClient pedidos) {
        this.pagos = pagos;
        this.pedidos = pedidos;
    }

    /**
     * Registra un pago simulado.
     * - Idempotente por {@code claveIdempotencia}: si ya existe, devuelve el pago existente.
     * - Un solo pago activo por pedido.
     * - TARJETA: se resuelve al registrar y, si se aprueba, confirma el pedido.
     * - EFECTIVO: queda PENDIENTE ("por cobrar") y el pedido se confirma igualmente.
     */
    @Transactional
    public PagoResponse registrar(Long usuarioId, String claveIdempotencia, CrearPagoRequest request) {
        var existente = pagos.findByClaveIdempotencia(claveIdempotencia);
        if (existente.isPresent()) {
            return toResponse(existente.get());
        }

        PedidoResumen pedido = pedidos.obtener(request.pedidoId());
        if (pedido == null) {
            throw new PagoException(HttpStatus.NOT_FOUND, "Pedido no encontrado: " + request.pedidoId());
        }
        if (pagos.existsByPedidoIdAndEstadoIn(request.pedidoId(), ACTIVOS)) {
            throw new PagoException(HttpStatus.CONFLICT,
                    "El pedido " + request.pedidoId() + " ya tiene un pago activo.");
        }

        EstadoPago estadoInicial = resolverEstadoInicial(request.metodo());
        Pago pago = new Pago(pedido.pedidoId(), usuarioId, pedido.total(), pedido.moneda(),
                request.metodo(), estadoInicial, claveIdempotencia);
        pago = pagos.save(pago);

        if (request.metodo() == MetodoPago.TARJETA && pago.getEstado() == EstadoPago.APROBADO) {
            pedidos.confirmar(pedido.pedidoId());
        } else if (request.metodo() == MetodoPago.EFECTIVO) {
            // El pedido se confirma igual para preparación/despacho; el cobro ocurre al entregar.
            pedidos.confirmar(pedido.pedidoId());
        }
        return toResponse(pago);
    }

    @Transactional(readOnly = true)
    public PagoResponse obtener(Long id) {
        return toResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public List<PagoResponse> listarPorPedido(Long pedidoId) {
        return pagos.findByPedidoId(pedidoId).stream().map(this::toResponse).toList();
    }

    /** Marca como APROBADO un pago pendiente (p. ej. cobro en efectivo al entregar). */
    @Transactional
    public PagoResponse aprobar(Long id) {
        Pago pago = buscar(id);
        if (pago.getEstado() != EstadoPago.PENDIENTE) {
            throw new PagoException(HttpStatus.CONFLICT,
                    "Solo un pago PENDIENTE puede aprobarse. Estado actual: " + pago.getEstado());
        }
        pago.aprobar();
        return toResponse(pagos.save(pago));
    }

    private Pago buscar(Long id) {
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
