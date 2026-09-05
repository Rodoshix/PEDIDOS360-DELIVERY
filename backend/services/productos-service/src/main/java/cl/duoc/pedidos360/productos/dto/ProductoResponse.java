package cl.duoc.pedidos360.productos.dto;

import java.math.BigDecimal;

public class ProductoResponse {

    private Long id;
    private Long restauranteId;
    private String nombre;
    private String descripcion;
    private BigDecimal precio;
    private String categoria;
    private boolean disponible;

    public ProductoResponse() {
    }

    public ProductoResponse(
            Long id,
            Long restauranteId,
            String nombre,
            String descripcion,
            BigDecimal precio,
            String categoria,
            boolean disponible) {
        this.id = id;
        this.restauranteId = restauranteId;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.precio = precio;
        this.categoria = categoria;
        this.disponible = disponible;
    }

    public Long getId() {
        return id;
    }

    public Long getRestauranteId() {
        return restauranteId;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public BigDecimal getPrecio() {
        return precio;
    }

    public String getCategoria() {
        return categoria;
    }

    public boolean isDisponible() {
        return disponible;
    }
}