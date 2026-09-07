package cl.duoc.pedidos360.productos.controller;

import cl.duoc.pedidos360.productos.dto.ProductoRequest;
import cl.duoc.pedidos360.productos.dto.ProductoResponse;
import cl.duoc.pedidos360.productos.service.ProductoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/productos")
public class ProductoController {

    private final ProductoService productoService;

    public ProductoController(ProductoService productoService) {
        this.productoService = productoService;
    }

    @GetMapping
    public List<ProductoResponse> listarTodos() {
        return productoService.listarTodos();
    }

    @GetMapping("/{id}")
    public ProductoResponse buscarPorId(@PathVariable Long id) {
        return productoService.buscarPorId(id);
    }

    @GetMapping("/restaurante/{restauranteId}")
    public List<ProductoResponse> listarPorRestaurante(
            @PathVariable Long restauranteId) {
        return productoService.listarPorRestaurante(restauranteId);
    }

    @GetMapping("/restaurante/{restauranteId}/disponibles")
    public List<ProductoResponse> listarDisponiblesPorRestaurante(
            @PathVariable Long restauranteId) {
        return productoService.listarDisponiblesPorRestaurante(restauranteId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductoResponse crear(
            @Valid @RequestBody ProductoRequest request) {
        return productoService.crear(request);
    }

    @PutMapping("/{id}")
    public ProductoResponse actualizar(
            @PathVariable Long id,
            @Valid @RequestBody ProductoRequest request) {
        return productoService.actualizar(id, request);
    }

    @PatchMapping("/{id}/disponibilidad")
    public ProductoResponse cambiarDisponibilidad(
            @PathVariable Long id,
            @RequestParam boolean disponible) {
        return productoService.cambiarDisponibilidad(id, disponible);
    }
}