package cl.duoc.pedidos360.messaging;

/**
 * Dominios con consulta request/reply aprobada (#78, #79, #80, #81).
 *
 * <p>Cada dominio aporta una cola funcional, un retry corto y una DLQ. Los nombres concretos
 * provienen de configuracion central; este enum solo los identifica, nunca los contiene.
 */
public enum Domain {
    USUARIOS("usuario.consultar-actual.v1"),
    RESTAURANTES("restaurante.listar.v1"),
    PRODUCTOS("producto.listar-disponibles.v1"),
    PAGOS("pago.consultar.v1");

    private final String routingKeyInicial;

    Domain(String routingKeyInicial) {
        this.routingKeyInicial = routingKeyInicial;
    }

    /** Routing key aprobada inicialmente para la operacion principal del dominio. */
    public String routingKeyInicial() {
        return routingKeyInicial;
    }

    public String propiedad() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
