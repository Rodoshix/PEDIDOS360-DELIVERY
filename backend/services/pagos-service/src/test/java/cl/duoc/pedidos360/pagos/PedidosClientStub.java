package cl.duoc.pedidos360.pagos;

import java.util.Map;

import cl.duoc.pedidos360.pagos.client.PedidoResumen;
import cl.duoc.pedidos360.pagos.client.PedidosClient;

/** Stub de PedidosClient para pruebas: pedido fijo y registro de confirmaciones. */
public class PedidosClientStub implements PedidosClient {

    public static final long PEDIDO_EXISTENTE = 500L;
    public static final long PEDIDO_INEXISTENTE = 999L;

    private int confirmaciones = 0;

    @Override
    public PedidoResumen obtener(Long pedidoId) {
        if (pedidoId == PEDIDO_INEXISTENTE) {
            return null;
        }
        return new PedidoResumen(pedidoId, 10L, "CREADO", 13980L, "CLP");
    }

    @Override
    public void confirmar(Long pedidoId) {
        confirmaciones++;
    }

    public int confirmaciones() {
        return confirmaciones;
    }

    public void reiniciar() {
        confirmaciones = 0;
    }
}
