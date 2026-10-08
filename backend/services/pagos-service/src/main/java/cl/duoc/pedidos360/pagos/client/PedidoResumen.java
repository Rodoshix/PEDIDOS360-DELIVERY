package cl.duoc.pedidos360.pagos.client;

/** Vista mínima del pedido necesaria para registrar el pago. */
public record PedidoResumen(
        Long pedidoId,
        java.util.UUID tenantId,
        Long usuarioId,
        String estado,
        Long total,
        String moneda,
        String tenantOrigin) {
    public PedidoResumen {
        if (pedidoId==null || pedidoId<1 || tenantId==null || usuarioId==null || usuarioId<1
            || total==null || total<0 || !"CLP".equals(moneda) || estado==null
            || !java.util.Set.of("CREADO","CONFIRMADO","PREPARANDO","LISTO","EN_REPARTO","ENTREGADO","CANCELADO").contains(estado)
            || tenantOrigin==null || !java.util.Set.of("AUTHENTICATED_NEW","RECONCILED_LEGACY").contains(tenantOrigin))
            throw new IllegalArgumentException("Resumen inválido.");
    }
}
