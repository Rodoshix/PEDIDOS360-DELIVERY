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
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "seguimiento_eventos")
public class SeguimientoEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "seguimiento_id", nullable = false, updatable = false)
    private Long seguimientoId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EstadoSeguimiento estado;

    @Column(name = "ocurrido_en", nullable = false, updatable = false)
    private Instant ocurridoEn;

    @Size(max = 500)
    @Column(length = 500)
    private String nota;

    protected SeguimientoEvento() {
    }

    public SeguimientoEvento(Long seguimientoId, EstadoSeguimiento estado, String nota) {
        this.seguimientoId = seguimientoId;
        this.estado = estado;
        this.nota = nota == null || nota.isBlank() ? null : nota.strip();
    }

    @PrePersist
    private void alCrear() {
        ocurridoEn = Instant.now();
    }

    public Long getId() { return id; }
    public Long getSeguimientoId() { return seguimientoId; }
    public EstadoSeguimiento getEstado() { return estado; }
    public Instant getOcurridoEn() { return ocurridoEn; }
    public String getNota() { return nota; }
}
