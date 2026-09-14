package cl.duoc.pedidos360.carrito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "lineas_carrito", schema = "carrito", uniqueConstraints =
        @UniqueConstraint(name = "uk_lineas_carrito_producto", columnNames = {"carrito_id", "producto_id"}))
public class LineaCarrito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carrito_id", nullable = false, updatable = false)
    private Carrito carrito;

    @Column(name = "producto_id", nullable = false, updatable = false)
    private Long productoId;

    @Column(name = "nombre_producto", nullable = false, length = 200)
    private String nombreProducto;

    @Column(name = "precio_unitario", nullable = false)
    private long precioUnitario;

    @Column(nullable = false)
    private int cantidad;

    protected LineaCarrito() { }

    // Solo Carrito modifica sus líneas: toda mutación debe versionar la raíz.
    LineaCarrito(Carrito carrito, Long productoId, String nombre, long precio, int cantidad) {
        this.carrito = carrito;
        this.productoId = productoId;
        actualizar(nombre, precio, cantidad);
    }

    void actualizar(String nombre, long precio, int cantidad) {
        this.nombreProducto = nombre;
        this.precioUnitario = precio;
        this.cantidad = cantidad;
    }

    public Long getId() { return id; }
    public Long getProductoId() { return productoId; }
    public String getNombreProducto() { return nombreProducto; }
    public long getPrecioUnitario() { return precioUnitario; }
    public int getCantidad() { return cantidad; }
    public long getSubtotal() { return Math.multiplyExact(precioUnitario, cantidad); }
}
