package cl.duoc.pedidos360.seguimiento.controller;

import java.net.URI;

import cl.duoc.pedidos360.seguimiento.dto.CambioEstadoRequest;
import cl.duoc.pedidos360.seguimiento.dto.CrearSeguimientoRequest;
import cl.duoc.pedidos360.seguimiento.dto.PaginaSeguimientos;
import cl.duoc.pedidos360.seguimiento.dto.SeguimientoHistorialResponse;
import cl.duoc.pedidos360.seguimiento.dto.SeguimientoResponse;
import cl.duoc.pedidos360.seguimiento.service.SeguimientoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/seguimientos")
public class SeguimientoController {
    private final SeguimientoService seguimientos;

    public SeguimientoController(SeguimientoService seguimientos) {
        this.seguimientos = seguimientos;
    }

    @GetMapping
    public PaginaSeguimientos listar(
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanio) {
        return seguimientos.listar(pagina, tamanio);
    }

    @GetMapping("/{pedidoId}")
    public SeguimientoResponse obtener(@PathVariable @Positive Long pedidoId) {
        return seguimientos.obtener(pedidoId);
    }

    @GetMapping("/{pedidoId}/historial")
    public SeguimientoHistorialResponse historial(@PathVariable @Positive Long pedidoId) {
        return seguimientos.obtenerHistorial(pedidoId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SeguimientoResponse> crear(
            @Valid @RequestBody CrearSeguimientoRequest request) {
        var response = seguimientos.crear(request);
        return ResponseEntity.created(URI.create("/seguimientos/" + response.pedidoId()))
                .body(response);
    }

    @PutMapping(value = "/{pedidoId}/estado", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SeguimientoResponse cambiarEstado(@PathVariable @Positive Long pedidoId,
                                             @Valid @RequestBody CambioEstadoRequest request) {
        return seguimientos.cambiarEstado(pedidoId, request);
    }
}
