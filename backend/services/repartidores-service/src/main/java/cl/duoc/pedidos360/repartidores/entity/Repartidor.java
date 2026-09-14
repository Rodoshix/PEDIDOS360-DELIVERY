package cl.duoc.pedidos360.repartidores.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "repartidores", uniqueConstraints =
        @UniqueConstraint(name = "uk_repartidores_identidad", columnNames = {"tenant_id", "entra_object_id"}))
public class Repartidor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotNull
    @Column(name = "entra_object_id", nullable = false, updatable = false)
    private UUID entraObjectId;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String nombre;

    @Size(max = 30)
    @Column(length = 30)
    private String telefono;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Vehiculo vehiculo;

    @Size(max = 100)
    @Column(length = 100)
    private String zona;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "estado_disponibilidad", nullable = false, length = 20)
    private EstadoDisponibilidad estadoDisponibilidad = EstadoDisponibilidad.DISPONIBLE;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Repartidor() {
    }

    public Repartidor(UUID tenantId, UUID entraObjectId, String nombre, String telefono,
                      Vehiculo vehiculo, String zona) {
        this.tenantId = tenantId;
        this.entraObjectId = entraObjectId;
        actualizarPerfil(nombre, telefono, vehiculo, zona);
    }

    public void actualizarPerfil(String nombre, String telefono, Vehiculo vehiculo, String zona) {
        this.nombre = nombre == null ? null : nombre.strip();
        this.telefono = telefono == null || telefono.isBlank() ? null : telefono.strip();
        this.vehiculo = vehiculo;
        this.zona = zona == null || zona.isBlank() ? null : zona.strip();
    }

    public void cambiarDisponibilidad(EstadoDisponibilidad nuevoEstado) {
        if (nuevoEstado != null) {
            this.estadoDisponibilidad = nuevoEstado;
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
    public UUID getTenantId() { return tenantId; }
    public UUID getEntraObjectId() { return entraObjectId; }
    public String getNombre() { return nombre; }
    public String getTelefono() { return telefono; }
    public Vehiculo getVehiculo() { return vehiculo; }
    public String getZona() { return zona; }
    public EstadoDisponibilidad getEstadoDisponibilidad() { return estadoDisponibilidad; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public Long getVersion() { return version; }
}
