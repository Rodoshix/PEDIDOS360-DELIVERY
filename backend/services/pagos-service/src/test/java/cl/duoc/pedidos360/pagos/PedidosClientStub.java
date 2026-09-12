package cl.duoc.pedidos360.pagos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import cl.duoc.pedidos360.pagos.client.PedidoResumen;
import cl.duoc.pedidos360.pagos.client.PedidosClient;
import cl.duoc.pedidos360.pagos.exception.PagoException;
import org.springframework.http.HttpStatus;

/**
 * Stub de {@link PedidosClient} para pruebas.
 * Permite registrar pedidos con dueño/estado y simular la pérdida de respuesta en la confirmación
 * (el pedido sí queda confirmado en Pedidos, pero el cliente recibe un error).
 * Replica el rechazo de transiciones inválidas (p. ej. CANCELADO → CONFIRMADO).
 */
public class PedidosClientStub implements PedidosClient {

    public static final long PEDIDO_EXISTENTE = 500L;
    public static final long PEDIDO_INEXISTENTE = 999L;

    private static final Set<String> ESTADOS_CONFIRMADOS =
            Set.of("CONFIRMADO", "PREPARANDO", "LISTO", "EN_REPARTO", "ENTREGADO");

    private final Map<Long, PedidoResumen> pedidos = new ConcurrentHashMap<>();
    private int confirmaciones = 0;
    private int fallosRespuestaConfirmacion = 0;

    public PedidosClientStub() {
        registrarPedido(PEDIDO_EXISTENTE, 10L, "CREADO", 13980L, "CLP");
    }

    public void registrarPedido(long pedidoId, long usuarioId, String estado, long total, String moneda) {
        pedidos.put(pedidoId, new PedidoResumen(pedidoId, usuarioId, estado, total, moneda));
    }

    /** Simula que las próximas N confirmaciones se aplican en Pedidos pero pierden la respuesta. */
    public void simularPerdidaDeRespuesta(int veces) {
        this.fallosRespuestaConfirmacion = veces;
    }

    @Override
    public PedidoResumen obtener(Long pedidoId) {
        return pedidos.get(pedidoId);
    }

    @Override
    public void confirmar(Long pedidoId) {
        confirmaciones++;
        PedidoResumen actual = pedidos.get(pedidoId);
        if (actual == null) {
            throw new PagoException(HttpStatus.NOT_FOUND, "Pedido no encontrado.");
        }
        boolean yaConfirmado = ESTADOS_CONFIRMADOS.contains(actual.estado());
        if (!yaConfirmado && !"CREADO".equals(actual.estado())) {
            // Transición inválida (p. ej. CANCELADO → CONFIRMADO): Pedidos responde conflicto.
            throw new PagoException(HttpStatus.CONFLICT,
                    "Transición inválida desde " + actual.estado() + ".");
        }
        if (!yaConfirmado) {
            pedidos.put(pedidoId, new PedidoResumen(actual.pedidoId(), actual.usuarioId(),
                    "CONFIRMADO", actual.total(), actual.moneda()));
        }
        if (!yaConfirmado && fallosRespuestaConfirmacion > 0) {
            fallosRespuestaConfirmacion--;
            throw new PagoException(HttpStatus.BAD_GATEWAY, "Respuesta perdida de Pedidos.");
        }
        // Si el pedido ya estaba confirmado, la operación es idempotente (no falla).
    }

    public int confirmaciones() {
        return confirmaciones;
    }

    public boolean estaConfirmado(long pedidoId) {
        PedidoResumen pedido = pedidos.get(pedidoId);
        return pedido != null && "CONFIRMADO".equals(pedido.estado());
    }

    public void reiniciar() {
        confirmaciones = 0;
        fallosRespuestaConfirmacion = 0;
        pedidos.clear();
        registrarPedido(PEDIDO_EXISTENTE, 10L, "CREADO", 13980L, "CLP");
    }
}
