package cl.duoc.pedidos360.restaurantes.controller;

import cl.duoc.pedidos360.restaurantes.dto.RestauranteDto;
import cl.duoc.pedidos360.restaurantes.exception.ApiError;
import cl.duoc.pedidos360.restaurantes.service.RestauranteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/restaurantes")
@Tag(
        name = "Restaurantes",
        description = "Operaciones para administrar los restaurantes de Pedidos360"
)
public class RestauranteController {

    private final RestauranteService restauranteService;

    public RestauranteController(RestauranteService restauranteService) {
        this.restauranteService = restauranteService;
    }

    @GetMapping
    @Operation(
            summary = "Listar restaurantes",
            description = "Obtiene todos los restaurantes registrados en el sistema."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Listado de restaurantes obtenido correctamente"
    )
    public ResponseEntity<List<RestauranteDto>> listar() {
        return ResponseEntity.ok(restauranteService.listar());
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Obtener restaurante por ID",
            description = "Obtiene los datos de un restaurante utilizando su identificador."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Restaurante encontrado"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Restaurante no encontrado",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)
                    )
            )
    })
    public ResponseEntity<RestauranteDto> obtenerPorId(
            @Parameter(
                    description = "Identificador del restaurante",
                    example = "1"
            )
            @PathVariable Long id) {

        return ResponseEntity.ok(
                restauranteService.obtenerPorId(id)
        );
    }

    @PostMapping
    @Operation(
            summary = "Crear restaurante",
            description = "Registra un nuevo restaurante en Pedidos360."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Restaurante creado correctamente"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Datos del restaurante inválidos",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)
                    )
            )
    })
    public ResponseEntity<RestauranteDto> crear(
            @Valid @RequestBody RestauranteDto dto) {

        RestauranteDto creado = restauranteService.crear(dto);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(creado);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Actualizar restaurante",
            description = "Actualiza los datos de un restaurante existente."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Restaurante actualizado correctamente"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Datos del restaurante inválidos",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Restaurante no encontrado",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)
                    )
            )
    })
    public ResponseEntity<RestauranteDto> actualizar(
            @Parameter(
                    description = "Identificador del restaurante",
                    example = "1"
            )
            @PathVariable Long id,
            @Valid @RequestBody RestauranteDto dto) {

        return ResponseEntity.ok(
                restauranteService.actualizar(id, dto)
        );
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Desactivar restaurante",
            description = "Desactiva un restaurante utilizando su identificador."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Restaurante desactivado correctamente"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Restaurante no encontrado",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)
                    )
            )
    })
    public ResponseEntity<Void> desactivar(
            @Parameter(
                    description = "Identificador del restaurante",
                    example = "1"
            )
            @PathVariable Long id) {

        restauranteService.desactivar(id);

        return ResponseEntity.noContent().build();
    }
}