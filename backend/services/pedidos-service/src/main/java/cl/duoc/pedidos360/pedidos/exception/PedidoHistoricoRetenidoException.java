package cl.duoc.pedidos360.pedidos.exception;
public class PedidoHistoricoRetenidoException extends PedidoException {
    public PedidoHistoricoRetenidoException() { super(org.springframework.http.HttpStatus.CONFLICT,"Recurso histórico retenido."); }
}
