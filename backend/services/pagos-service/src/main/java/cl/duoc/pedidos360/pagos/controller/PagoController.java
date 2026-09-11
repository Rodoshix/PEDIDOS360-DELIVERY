package cl.duoc.pedidos360.pagos.controller;

import java.util.List;
import java.util.UUID;

import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
import cl.duoc.pedidos360.pagos.dto.PagoResponse;
import cl.duoc.pedidos360.pagos.security.IdentidadActual;
import cl.duoc.pedidos360.pagos.service.PagoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PagoController {

    private final PagoService pagos;
    private final IdentidadActual identidad;

    public PagoController(PagoService pagos, IdentidadActual identidad) {
        this.pagos = pagos;
        this.identidad = identidad;
    }

    @PostMapping("/pagos")
    @ResponseStatus(HttpStatus.CREATED)
    public PagoResponse registrar(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CrearPagoRequest request) {
        String clave = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKey.strip();
        return pagos.registrar(identidad.obtener().usuarioId(), clave, request);
    }

    @GetMapping("/pagos/{id}")
    public PagoResponse obtener(@PathVariable Long id) {
        return pagos.obtener(id);
    }

    @GetMapping("/pagos/pedido/{pedidoId}")
    public List<PagoResponse> listarPorPedido(@PathVariable Long pedidoId) {
        return pagos.listarPorPedido(pedidoId);
    }

    /** Cobra un pago pendiente (p. ej. efectivo recibido en la entrega). */
    @PutMapping("/pagos/{id}/aprobar")
    public PagoResponse aprobar(@PathVariable Long id) {
        return pagos.aprobar(id);
    }
}
