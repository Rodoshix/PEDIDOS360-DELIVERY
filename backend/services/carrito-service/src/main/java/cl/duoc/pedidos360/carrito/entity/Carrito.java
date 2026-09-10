package cl.duoc.pedidos360.carrito.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "carritos", schema = "carrito", uniqueConstraints =
        @UniqueConstraint(name = "uk_carritos_identidad", columnNames = {"tenant_id", "entra_object_id"}))
public class Carrito {

    public static final int MAX_CANTIDAD = 99;
    public static final int MAX_LINEAS = 50;
    public static final long MAX_PRECIO = 1_000_000_000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "entra_object_id", nullable = false, updatable = false)
    private UUID entraObjectId;

    @Column(name = "restaurante_id")
    private Long restauranteId;

    @Column(nullable = false, length = 3, updatable = false)
    private String moneda = "CLP";

    @OneToMany(mappedBy = "carrito", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productoId ASC")
    private List<LineaCarrito> lineas = new ArrayList<>();

    // Garantiza que cambiar solo una línea ensucie también la raíz y active @Version.
    @Column(name = "revision_contenido", nullable = false)
    private long revisionContenido;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected Carrito() { }

    public Carrito(UUID tenantId, UUID entraObjectId) {
        this.tenantId = Objects.requireNonNull(tenantId, "El directorio es obligatorio");
        this.entraObjectId = Objects.requireNonNull(entraObjectId, "La identidad es obligatoria");
    }

    public void agregarProducto(Long productoId, Long restauranteId, String nombre, long precio, int cantidad) {
        validarId(productoId);
        validarId(restauranteId);
        validarCantidad(cantidad);
        String nombreNormalizado = nombre == null ? "" : nombre.strip();
        if (nombreNormalizado.isBlank() || nombreNormalizado.length() > 200 || precio < 0 || precio > MAX_PRECIO) {
            throw new IllegalArgumentException("Datos de producto inválidos");
        }
        if (this.restauranteId != null && !this.restauranteId.equals(restauranteId)) {
            throw new IllegalStateException("El carrito solo admite un restaurante");
        }
        var existente = lineas.stream().filter(linea -> linea.getProductoId().equals(productoId)).findFirst();
        int nuevaCantidad = existente.map(linea -> Math.addExact(linea.getCantidad(), cantidad)).orElse(cantidad);
        validarCantidad(nuevaCantidad);
        if (existente.isEmpty() && lineas.size() >= MAX_LINEAS) {
            throw new IllegalStateException("Se alcanzó el límite de líneas del carrito");
        }
        registrarCambio();
        this.restauranteId = restauranteId;
        if (existente.isPresent()) {
            existente.get().actualizar(nombreNormalizado, precio, nuevaCantidad);
        } else {
            lineas.add(new LineaCarrito(this, productoId, nombreNormalizado, precio, nuevaCantidad));
        }
    }

    public void cambiarCantidad(Long productoId, int cantidad) {
        validarCantidad(cantidad);
        var linea = buscarLinea(productoId);
        if (linea.getCantidad() != cantidad) {
            registrarCambio();
            linea.actualizar(linea.getNombreProducto(), linea.getPrecioUnitario(), cantidad);
        }
    }

    public void quitarProducto(Long productoId) {
        var linea = buscarLinea(productoId);
        registrarCambio();
        lineas.remove(linea);
        if (lineas.isEmpty()) restauranteId = null;
    }

    public void vaciar() {
        if (!lineas.isEmpty()) {
            registrarCambio();
            lineas.clear();
            restauranteId = null;
        }
    }

    private LineaCarrito buscarLinea(Long productoId) {
        validarId(productoId);
        return lineas.stream().filter(linea -> linea.getProductoId().equals(productoId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Producto no encontrado en el carrito"));
    }

    private static void validarId(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("El identificador debe ser positivo");
    }

    private static void validarCantidad(int cantidad) {
        if (cantidad < 1 || cantidad > MAX_CANTIDAD) {
            throw new IllegalArgumentException("La cantidad debe estar entre 1 y " + MAX_CANTIDAD);
        }
    }

    private void registrarCambio() {
        revisionContenido = Math.incrementExact(revisionContenido);
    }

    @PrePersist
    private void alCrear() {
        creadoEn = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        actualizadoEn = creadoEn;
    }

    @PreUpdate
    private void alActualizar() {
        actualizadoEn = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public Long getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getEntraObjectId() { return entraObjectId; }
    public Long getRestauranteId() { return restauranteId; }
    public String getMoneda() { return moneda; }
    public List<LineaCarrito> getLineas() { return List.copyOf(lineas); }
    public long getTotal() { return lineas.stream().mapToLong(LineaCarrito::getSubtotal).reduce(0L, Math::addExact); }
    public Long getVersion() { return version; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
}
