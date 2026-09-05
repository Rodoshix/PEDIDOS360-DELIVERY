package cl.duoc.pedidos360.pedidos.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import cl.duoc.pedidos360.pedidos.exception.TransicionInvalidaException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "pedidos", schema = "pedidos")
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    @Column(name = "direccion_entrega", nullable = false, length = 255)
    private String direccionEntrega;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoPedido estado;

    @Column(nullable = false)
    private Long total;

    @Column(nullable = false, length = 3)
    private String moneda;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<LineaPedido> lineas = new ArrayList<>();

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Pedido() {
    }

    public Pedido(Long usuarioId, Long restauranteId, String direccionEntrega, String moneda) {
        this.usuarioId = usuarioId;
        this.restauranteId = restauranteId;
        this.direccionEntrega = direccionEntrega;
        this.moneda = moneda;
        this.estado = EstadoPedido.CREADO;
        this.total = 0L;
    }

    public void agregarLinea(LineaPedido linea) {
        lineas.add(linea);
        linea.asignarPedido(this);
        recalcularTotal();
    }

    public void transicionarA(EstadoPedido destino) {
        if (!estado.puedeTransicionarA(destino)) {
            throw new TransicionInvalidaException(
                    "Transición inválida: " + estado + " → " + destino);
        }
        this.estado = destino;
    }

    public void actualizarDireccionEntrega(String direccion) {
        if (direccion != null && !direccion.isBlank()) {
            this.direccionEntrega = direccion.strip();
        }
    }

    private void recalcularTotal() {
        this.total = lineas.stream().mapToLong(LineaPedido::getSubtotal).sum();
    }

    @PrePersist
    void alCrear() {
        Instant ahora = Instant.now();
        creadoEn = ahora;
        actualizadoEn = ahora;
    }

    @PreUpdate
    void alActualizar() {
        actualizadoEn = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public Long getRestauranteId() { return restauranteId; }
    public String getDireccionEntrega() { return direccionEntrega; }
    public EstadoPedido getEstado() { return estado; }
    public Long getTotal() { return total; }
    public String getMoneda() { return moneda; }
    public List<LineaPedido> getLineas() { return lineas; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public Long getVersion() { return version; }

    public void setEstado(EstadoPedido estado) { this.estado = estado; }
    public void setUsuarioId(Long usuarioId) { this.usuarioId = usuarioId; }
    public void setRestauranteId(Long restauranteId) { this.restauranteId = restauranteId; }
}
