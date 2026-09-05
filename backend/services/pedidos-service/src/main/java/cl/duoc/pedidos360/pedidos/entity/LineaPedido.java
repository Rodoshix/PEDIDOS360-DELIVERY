package cl.duoc.pedidos360.pedidos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "lineas_pedido", schema = "pedidos")
public class LineaPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id", nullable = false)
    private Pedido pedido;

    @Column(name = "producto_id", nullable = false)
    private Long productoId;

    @Column(nullable = false)
    private int cantidad;

    @Column(name = "precio_unitario", nullable = false)
    private Long precioUnitario;

    @Column(nullable = false)
    private Long subtotal;

    protected LineaPedido() {
    }

    public LineaPedido(Long productoId, int cantidad, Long precioUnitario) {
        this.productoId = productoId;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
        this.subtotal = (long) cantidad * precioUnitario;
    }

    void asignarPedido(Pedido pedido) {
        this.pedido = pedido;
    }

    public Long getId() { return id; }
    public Pedido getPedido() { return pedido; }
    public Long getProductoId() { return productoId; }
    public int getCantidad() { return cantidad; }
    public Long getPrecioUnitario() { return precioUnitario; }
    public Long getSubtotal() { return subtotal; }
}
