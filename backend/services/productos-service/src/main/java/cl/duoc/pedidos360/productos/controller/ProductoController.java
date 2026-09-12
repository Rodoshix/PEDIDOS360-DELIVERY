package cl.duoc.pedidos360.productos.controller;

import cl.duoc.pedidos360.productos.dto.ProductoRequest;
import cl.duoc.pedidos360.productos.dto.ProductoResponse;
import cl.duoc.pedidos360.productos.service.ProductoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/productos")
@Tag(
        name = "Productos",
        description = "Operaciones para administrar el catálogo de productos de Pedidos360"
)
public class ProductoController {

    private final ProductoService productoService;

    public ProductoController(ProductoService productoService) {
        this.productoService = productoService;
    }

    @GetMapping
    @Operation(
            summary = "Listar productos",
            description = "Obtiene todos los productos registrados en el catálogo."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Listado de productos obtenido correctamente"
    )
    public List<ProductoResponse> listarTodos() {
        return productoService.listarTodos();
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Obtener producto por ID",
            description = "Obtiene los datos de un producto utilizando su identificador."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Producto encontrado"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Producto no encontrado"
            )
    })
    public ProductoResponse buscarPorId(
            @Parameter(
                    description = "Identificador del producto",
                    example = "1"
            )
            @PathVariable Long id) {

        return productoService.buscarPorId(id);
    }

    @GetMapping("/restaurante/{restauranteId}")
    @Operation(
            summary = "Listar productos por restaurante",
            description = "Obtiene todos los productos asociados a un restaurante."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Productos del restaurante obtenidos correctamente"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Restaurante o productos asociados no encontrados"
            )
    })
    public List<ProductoResponse> listarPorRestaurante(
            @Parameter(
                    description = "Identificador del restaurante",
                    example = "1"
            )
            @PathVariable Long restauranteId) {

        return productoService.listarPorRestaurante(restauranteId);
    }

    @GetMapping("/restaurante/{restauranteId}/disponibles")
    @Operation(
            summary = "Listar productos disponibles por restaurante",
            description = "Obtiene únicamente los productos disponibles de un restaurante."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Productos disponibles obtenidos correctamente"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Restaurante o productos asociados no encontrados"
            )
    })
    public List<ProductoResponse> listarDisponiblesPorRestaurante(
            @Parameter(
                    description = "Identificador del restaurante",
                    example = "1"
            )
            @PathVariable Long restauranteId) {

        return productoService.listarDisponiblesPorRestaurante(restauranteId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Crear producto",
            description = "Registra un nuevo producto en el catálogo de Pedidos360."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Producto creado correctamente"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Datos del producto inválidos"
            )
    })
    public ProductoResponse crear(
            @Valid @RequestBody ProductoRequest request) {

        return productoService.crear(request);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Actualizar producto",
            description = "Actualiza los datos de un producto existente."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Producto actualizado correctamente"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Datos del producto inválidos"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Producto no encontrado"
            )
    })
    public ProductoResponse actualizar(
            @Parameter(
                    description = "Identificador del producto",
                    example = "1"
            )
            @PathVariable Long id,
            @Valid @RequestBody ProductoRequest request) {

        return productoService.actualizar(id, request);
    }

    @PatchMapping("/{id}/disponibilidad")
    @Operation(
            summary = "Cambiar disponibilidad del producto",
            description = "Activa o desactiva la disponibilidad de un producto."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Disponibilidad actualizada correctamente"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Producto no encontrado"
            )
    })
    public ProductoResponse cambiarDisponibilidad(
            @Parameter(
                    description = "Identificador del producto",
                    example = "1"
            )
            @PathVariable Long id,

            @Parameter(
                    description = "Nuevo estado de disponibilidad del producto",
                    example = "true"
            )
            @RequestParam boolean disponible) {

        return productoService.cambiarDisponibilidad(id, disponible);
    }
}