package cl.duoc.pedidos360.pedidos.service;

import java.util.List;

import cl.duoc.pedidos360.pedidos.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.pedidos.dto.LineaPedidoRequest;
import cl.duoc.pedidos360.pedidos.dto.LineaPedidoResponse;
import cl.duoc.pedidos360.pedidos.dto.PedidoResponse;
import cl.duoc.pedidos360.pedidos.entity.EstadoPedido;
import cl.duoc.pedidos360.pedidos.entity.LineaPedido;
import cl.duoc.pedidos360.pedidos.entity.Pedido;
import cl.duoc.pedidos360.pedidos.exception.PedidoNoEncontradoException;
import cl.duoc.pedidos360.pedidos.repository.LineaPedidoRepository;
import cl.duoc.pedidos360.pedidos.repository.PedidoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PedidoService {

    private final PedidoRepository pedidos;
    private final LineaPedidoRepository lineas;

    public PedidoService(PedidoRepository pedidos, LineaPedidoRepository lineas) {
        this.pedidos = pedidos;
        this.lineas = lineas;
    }

    @Transactional
    public PedidoResponse crear(Long usuarioId, CrearPedidoRequest request) {
        Pedido pedido = new Pedido(usuarioId, request.restauranteId(), request.direccionEntrega(), "CLP");
        for (LineaPedidoRequest item : request.items()) {
            Long precio = precioDeCatalogoMock(item.productoId());
            pedido.agregarLinea(new LineaPedido(item.productoId(), item.cantidad(), precio));
        }
        return toResponse(pedidos.save(pedido));
    }

    @Transactional(readOnly = true)
    public PedidoResponse obtener(Long id) {
        return toResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public List<PedidoResponse> listarPorUsuario(Long usuarioId) {
        return pedidos.findByUsuarioId(usuarioId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PedidoResponse> listar() {
        return pedidos.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public PedidoResponse cambiarEstado(Long id, EstadoPedido destino) {
        Pedido pedido = buscar(id);
        pedido.transicionarA(destino);
        return toResponse(pedidos.save(pedido));
    }

    private Pedido buscar(Long id) {
        return pedidos.findById(id)
                .orElseThrow(() -> new PedidoNoEncontradoException("Pedido no encontrado: " + id));
    }

    /** TODO(integracion): resolver el precio real desde catálogo (I2). Mock en desarrollo. */
    private Long precioDeCatalogoMock(Long productoId) {
        return 6990L;
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
