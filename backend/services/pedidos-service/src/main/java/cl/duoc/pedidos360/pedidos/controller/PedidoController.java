package cl.duoc.pedidos360.pedidos.controller;

import java.util.List;

import cl.duoc.pedidos360.pedidos.dto.CambiarEstadoRequest;
import cl.duoc.pedidos360.pedidos.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.pedidos.dto.PedidoResponse;
import cl.duoc.pedidos360.pedidos.security.IdentidadActual;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PedidoController {

    private final PedidoService pedidos;
    private final IdentidadActual identidad;

    public PedidoController(PedidoService pedidos, IdentidadActual identidad) {
        this.pedidos = pedidos;
        this.identidad = identidad;
    }

    @PostMapping("/pedidos")
    @ResponseStatus(HttpStatus.CREATED)
    public PedidoResponse crear(@Valid @RequestBody CrearPedidoRequest request) {
        return pedidos.crear(identidad.obtener().usuarioId(), request);
    }

    @GetMapping("/pedidos")
    public List<PedidoResponse> listar() {
        return pedidos.listar();
    }

    @GetMapping("/pedidos/{id}")
    public PedidoResponse obtener(@PathVariable Long id) {
        return pedidos.obtener(id);
    }

    @GetMapping("/usuarios/{id}/pedidos")
    public List<PedidoResponse> historial(@PathVariable Long id) {
        return pedidos.listarPorUsuario(id);
    }

    @PutMapping("/pedidos/{id}/estado")
    public PedidoResponse cambiarEstado(@PathVariable Long id,
            @Valid @RequestBody CambiarEstadoRequest request) {
        return pedidos.cambiarEstado(id, request.estado());
    }
}
