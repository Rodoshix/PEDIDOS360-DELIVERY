package cl.duoc.pedidos360.pedidos.dto;
public record PedidoResumenPagoResponse(Long pedidoId, java.util.UUID tenantId, Long usuarioId,
    String estado, Long total, String moneda, String tenantOrigin) {
    public PedidoResumenPagoResponse {
        if (pedidoId==null || pedidoId<1 || tenantId==null || usuarioId==null || usuarioId<1
            || estado==null || total==null || total<0 || !"CLP".equals(moneda))
            throw new IllegalArgumentException("Resumen inválido.");
        cl.duoc.pedidos360.pedidos.entity.EstadoPedido.valueOf(estado);
        if (!java.util.Set.of("AUTHENTICATED_NEW","RECONCILED_LEGACY").contains(tenantOrigin))
            throw new IllegalArgumentException("Procedencia inválida.");
    }
}
