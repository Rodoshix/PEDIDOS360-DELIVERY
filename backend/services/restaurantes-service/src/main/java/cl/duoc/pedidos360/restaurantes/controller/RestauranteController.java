package cl.duoc.pedidos360.restaurantes.controller;

import cl.duoc.pedidos360.restaurantes.dto.RestauranteDto;
import cl.duoc.pedidos360.restaurantes.service.RestauranteService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/restaurantes")
public class RestauranteController {

    private final RestauranteService restauranteService;

    public RestauranteController(RestauranteService restauranteService) {
        this.restauranteService = restauranteService;
    }

    @GetMapping
    public ResponseEntity<List<RestauranteDto>> listar() {
        return ResponseEntity.ok(restauranteService.listar());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RestauranteDto> obtenerPorId(@PathVariable Long id) {
        return ResponseEntity.ok(restauranteService.obtenerPorId(id));
    }

    @PostMapping
    public ResponseEntity<RestauranteDto> crear(
            @Valid @RequestBody RestauranteDto dto) {

        RestauranteDto creado = restauranteService.crear(dto);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(creado);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RestauranteDto> actualizar(
            @PathVariable Long id,
            @Valid @RequestBody RestauranteDto dto) {

        return ResponseEntity.ok(
                restauranteService.actualizar(id, dto)
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desactivar(@PathVariable Long id) {
        restauranteService.desactivar(id);

        return ResponseEntity.noContent().build();
    }
}