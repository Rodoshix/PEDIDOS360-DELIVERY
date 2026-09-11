package cl.duoc.pedidos360.pagos.entity;

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

@Entity
@Table(name = "pagos", schema = "pagos", uniqueConstraints =
        @UniqueConstraint(name = "uk_pagos_idempotencia", columnNames = "clave_idempotencia"))
public class Pago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pedido_id", nullable = false)
    private Long pedidoId;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(nullable = false)
    private Long monto;

    @Column(nullable = false, length = 3)
    private String moneda;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MetodoPago metodo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EstadoPago estado;

    @Column(name = "clave_idempotencia", nullable = false, length = 80, updatable = false)
    private String claveIdempotencia;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Pago() {
    }

    public Pago(Long pedidoId, Long usuarioId, Long monto, String moneda,
                MetodoPago metodo, EstadoPago estado, String claveIdempotencia) {
        this.pedidoId = pedidoId;
        this.usuarioId = usuarioId;
        this.monto = monto;
        this.moneda = moneda;
        this.metodo = metodo;
        this.estado = estado;
        this.claveIdempotencia = claveIdempotencia;
    }

    public void aprobar() {
        this.estado = EstadoPago.APROBADO;
    }

    public void rechazar() {
        this.estado = EstadoPago.RECHAZADO;
    }

    public boolean estaActivo() {
        return estado == EstadoPago.PENDIENTE || estado == EstadoPago.APROBADO;
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
    public Long getPedidoId() { return pedidoId; }
    public Long getUsuarioId() { return usuarioId; }
    public Long getMonto() { return monto; }
    public String getMoneda() { return moneda; }
    public MetodoPago getMetodo() { return metodo; }
    public EstadoPago getEstado() { return estado; }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public Long getVersion() { return version; }
}
