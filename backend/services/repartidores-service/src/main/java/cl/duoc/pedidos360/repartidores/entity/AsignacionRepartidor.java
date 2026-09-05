package cl.duoc.pedidos360.repartidores.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "repartidor_asignaciones", uniqueConstraints =
        @UniqueConstraint(name = "uk_asignaciones_pedido", columnNames = {"pedido_id"}))
public class AsignacionRepartidor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "repartidor_id", nullable = false, updatable = false)
    private Long repartidorId;

    @NotNull
    @Column(name = "pedido_id", nullable = false, updatable = false)
    private Long pedidoId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoAsignacion estado = EstadoAsignacion.ASIGNADA;

    @Column(name = "asignada_en", nullable = false, updatable = false)
    private Instant asignadaEn;

    @Size(max = 500)
    @Column(length = 500)
    private String nota;

    protected AsignacionRepartidor() {
    }

    public AsignacionRepartidor(Long repartidorId, Long pedidoId, String nota) {
        this.repartidorId = repartidorId;
        this.pedidoId = pedidoId;
        this.nota = nota == null || nota.isBlank() ? null : nota.strip();
    }

    public void cambiarEstado(EstadoAsignacion nuevoEstado) {
        if (nuevoEstado != null) {
            this.estado = nuevoEstado;
        }
    }

    @PrePersist
    private void alCrear() {
        asignadaEn = Instant.now();
    }

    public Long getId() { return id; }
    public Long getRepartidorId() { return repartidorId; }
    public Long getPedidoId() { return pedidoId; }
    public EstadoAsignacion getEstado() { return estado; }
    public Instant getAsignadaEn() { return asignadaEn; }
    public String getNota() { return nota; }
}
