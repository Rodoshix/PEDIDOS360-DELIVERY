package cl.duoc.pedidos360.messaging;

/**
 * Modo de operacion del request/reply interno.
 *
 * <p>HTTP sigue siendo el transporte predeterminado. {@code DISABLED} no declara topologia ni
 * levanta listeners; el corte por flujo corresponde a #70.
 */
public enum RelayMode {
    DISABLED,
    ACTIVE;

    /**
     * Papel del proceso en el request/reply.
     *
     * <p>{@code BFF} es el solicitante: mantiene el registro de correlaciones y consume la cola
     * tecnica de respuestas. {@code SERVICE} es el consumidor de una cola funcional y responde. El
     * valor predeterminado es {@code SERVICE}: un proceso que no declara lo contrario no levanta el
     * consumidor de respuestas compartido.
     */
    public enum Role {
        BFF,
        SERVICE
    }
}
