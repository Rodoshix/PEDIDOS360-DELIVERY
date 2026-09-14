package cl.duoc.pedidos360.usuarios.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "usuarios", schema = "usuarios", uniqueConstraints =
        @UniqueConstraint(name = "uk_usuarios_identidad", columnNames = {"tenant_id", "entra_object_id"}))
public class Usuario {

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
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String nombre;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String apellido;

    @NotBlank
    @Email
    @Size(max = 254)
    @Column(nullable = false, length = 254)
    private String email;

    @Size(max = 30)
    @Column(length = 30)
    private String telefono;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Usuario() {
    }

    public Usuario(UUID tenantId, UUID entraObjectId, String nombre, String apellido,
                   String email, String telefono) {
        this.tenantId = tenantId;
        this.entraObjectId = entraObjectId;
        actualizarPerfil(nombre, apellido, email, telefono);
    }

    public void actualizarPerfil(String nombre, String apellido, String email, String telefono) {
        this.nombre = nombre == null ? null : nombre.strip();
        this.apellido = apellido == null ? null : apellido.strip();
        this.email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        this.telefono = telefono == null || telefono.isBlank() ? null : telefono.strip();
    }

    public void desactivar() {
        this.activo = false;
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
    public String getApellido() { return apellido; }
    public String getEmail() { return email; }
    public String getTelefono() { return telefono; }
    public boolean isActivo() { return activo; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public Long getVersion() { return version; }
}
