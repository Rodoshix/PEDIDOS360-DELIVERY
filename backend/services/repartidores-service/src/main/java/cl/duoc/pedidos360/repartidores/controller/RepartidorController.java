package cl.duoc.pedidos360.repartidores.controller;

import java.net.URI;
import java.util.List;

import cl.duoc.pedidos360.repartidores.dto.AsignacionResponse;
import cl.duoc.pedidos360.repartidores.dto.AsignarPedidoRequest;
import cl.duoc.pedidos360.repartidores.dto.CambioEstadoAsignacionRequest;
import cl.duoc.pedidos360.repartidores.dto.DisponibilidadRequest;
import cl.duoc.pedidos360.repartidores.dto.PaginaRepartidores;
import cl.duoc.pedidos360.repartidores.dto.PerfilRepartidorRequest;
import cl.duoc.pedidos360.repartidores.dto.RepartidorResponse;
import cl.duoc.pedidos360.repartidores.service.RepartidorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/repartidores")
public class RepartidorController {
    private final RepartidorService repartidores;

    public RepartidorController(RepartidorService repartidores) {
        this.repartidores = repartidores;
    }

    @GetMapping
    public PaginaRepartidores listar(
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanio) {
        return repartidores.listar(pagina, tamanio);
    }

    @GetMapping("/me")
    public RepartidorResponse actual() {
        return repartidores.obtenerActual();
    }

    @GetMapping("/{id}")
    public RepartidorResponse obtener(@PathVariable @Positive Long id) {
        return repartidores.obtener(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RepartidorResponse> crear(
            @Valid @RequestBody PerfilRepartidorRequest request) {
        var response = repartidores.crear(request);
        return ResponseEntity.created(URI.create("/repartidores/" + response.id())).body(response);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RepartidorResponse actualizar(@PathVariable @Positive Long id,
                                         @Valid @RequestBody PerfilRepartidorRequest request) {
        return repartidores.actualizar(id, request);
    }

    @PutMapping(value = "/{id}/disponibilidad", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RepartidorResponse cambiarDisponibilidad(@PathVariable @Positive Long id,
                                                    @Valid @RequestBody DisponibilidadRequest request) {
        return repartidores.cambiarDisponibilidad(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desactivar(@PathVariable @Positive Long id) {
        repartidores.desactivar(id);
    }

    @GetMapping("/{id}/asignaciones")
    public List<AsignacionResponse> asignaciones(@PathVariable @Positive Long id) {
        return repartidores.listarAsignaciones(id);
    }

    @PostMapping(value = "/{id}/asignaciones", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AsignacionResponse> asignarPedido(@PathVariable @Positive Long id,
                                                            @Valid @RequestBody AsignarPedidoRequest request) {
        var response = repartidores.asignarPedido(id, request);
        return ResponseEntity.created(URI.create("/repartidores/" + id + "/asignaciones"))
                .body(response);
    }

    @PutMapping(value = "/{id}/asignaciones/{pedidoId}/estado", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AsignacionResponse cambiarEstadoAsignacion(@PathVariable @Positive Long id,
                                                      @PathVariable @Positive Long pedidoId,
                                                      @Valid @RequestBody CambioEstadoAsignacionRequest request) {
        return repartidores.cambiarEstadoAsignacion(id, pedidoId, request);
    }
}
