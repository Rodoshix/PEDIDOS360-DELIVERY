package cl.duoc.pedidos360.seguimiento.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "seguimientos", uniqueConstraints =
        @UniqueConstraint(name = "uk_seguimientos_pedido", columnNames = {"pedido_id"}))
public class Seguimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "pedido_id", nullable = false, updatable = false)
    private Long pedidoId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "estado_actual", nullable = false, length = 30)
    private EstadoSeguimiento estadoActual;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Seguimiento() {
    }

    public Seguimiento(Long pedidoId, EstadoSeguimiento estadoInicial) {
        this.pedidoId = pedidoId;
        this.estadoActual = estadoInicial;
    }

    public void cambiarEstado(EstadoSeguimiento nuevoEstado) {
        if (nuevoEstado != null) {
            this.estadoActual = nuevoEstado;
        }
    }

    @PrePersist
    private void alCrear() {
        Instant ahora = Instant.now();
        creadoEn = ahora;
        actualizadoEn = ahora;
    }

    @PreUpdate
    private void alActualizar() {
        actualizadoEn = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPedidoId() { return pedidoId; }
    public EstadoSeguimiento getEstadoActual() { return estadoActual; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public Long getVersion() { return version; }
}
