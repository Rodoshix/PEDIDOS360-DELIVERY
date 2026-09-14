package cl.duoc.pedidos360.pedidos.entity;

import java.util.Map;
import java.util.Set;

/**
 * Estados de un pedido y la máquina de transiciones permitidas.
 * El recorrido base es CREADO → CONFIRMADO → PREPARANDO → LISTO → EN_REPARTO → ENTREGADO.
 * CANCELADO es un estado terminal. Las reglas de actor autorizado son de la capa de servicio.
 */
public enum EstadoPedido {

    CREADO, CONFIRMADO, PREPARANDO, LISTO, EN_REPARTO, ENTREGADO, CANCELADO;

    private static final Map<EstadoPedido, Set<EstadoPedido>> TRANSICIONES = Map.of(
            CREADO, Set.of(CONFIRMADO, CANCELADO),
            CONFIRMADO, Set.of(PREPARANDO, CANCELADO),
            PREPARANDO, Set.of(LISTO, CANCELADO),
            LISTO, Set.of(EN_REPARTO),
            EN_REPARTO, Set.of(ENTREGADO),
            ENTREGADO, Set.of(),
            CANCELADO, Set.of());

    public boolean puedeTransicionarA(EstadoPedido destino) {
        return TRANSICIONES.getOrDefault(this, Set.of()).contains(destino);
    }
}
