package cl.duoc.pedidos360.pagos.entity;

/** Estados de un pago simulado. */
public enum EstadoPago {

    PENDIENTE, APROBADO, RECHAZADO;

    public boolean esFinal() {
        return this == APROBADO || this == RECHAZADO;
    }
}
