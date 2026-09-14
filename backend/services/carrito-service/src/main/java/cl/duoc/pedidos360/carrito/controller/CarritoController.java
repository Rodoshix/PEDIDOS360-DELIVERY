package cl.duoc.pedidos360.carrito.controller;

import cl.duoc.pedidos360.carrito.dto.AgregarProductoRequest;
import cl.duoc.pedidos360.carrito.dto.CambiarCantidadRequest;
import cl.duoc.pedidos360.carrito.dto.CarritoResponse;
import cl.duoc.pedidos360.carrito.service.CarritoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/carrito")
public class CarritoController {
    private final CarritoService service;

    public CarritoController(CarritoService service) { this.service = service; }

    @GetMapping
    CarritoResponse obtener() { return service.obtener(); }

    @PostMapping(value = "/items", consumes = MediaType.APPLICATION_JSON_VALUE)
    CarritoResponse agregar(@Valid @RequestBody AgregarProductoRequest request) { return service.agregar(request); }

    @PutMapping(value = "/items/{productoId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    CarritoResponse cambiar(@PathVariable @Positive Long productoId, @Valid @RequestBody CambiarCantidadRequest request) {
        return service.cambiarCantidad(productoId, request.cantidad());
    }

    @DeleteMapping("/items/{productoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void quitar(@PathVariable @Positive Long productoId) { service.quitar(productoId); }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void vaciar() { service.vaciar(); }
}
