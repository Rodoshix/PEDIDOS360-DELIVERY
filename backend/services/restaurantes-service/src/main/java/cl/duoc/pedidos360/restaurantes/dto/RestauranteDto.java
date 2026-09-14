package cl.duoc.pedidos360.restaurantes.dto;

import cl.duoc.pedidos360.restaurantes.entity.EstadoRestaurante;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class RestauranteDto {

    private Long id;

    @NotBlank(message = "El nombre del restaurante es obligatorio")
    @Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
    private String nombre;

    @Size(max = 500, message = "La descripción no puede superar los 500 caracteres")
    private String descripcion;

    @Size(max = 255, message = "La dirección no puede superar los 255 caracteres")
    private String direccion;

    @NotNull(message = "El estado del restaurante es obligatorio")
    private EstadoRestaurante estado;

    public RestauranteDto() {
    }

    public RestauranteDto(
            Long id,
            String nombre,
            String descripcion,
            String direccion,
            EstadoRestaurante estado) {
        this.id = id;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.direccion = direccion;
        this.estado = estado;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public EstadoRestaurante getEstado() {
        return estado;
    }

    public void setEstado(EstadoRestaurante estado) {
        this.estado = estado;
    }
}