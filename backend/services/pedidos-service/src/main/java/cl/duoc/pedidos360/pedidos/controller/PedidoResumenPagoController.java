package cl.duoc.pedidos360.pedidos.controller;
import cl.duoc.pedidos360.pedidos.dto.PedidoResumenPagoResponse;
import cl.duoc.pedidos360.pedidos.security.IdentidadActual;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class PedidoResumenPagoController {
    private final PedidoService pedidos;
    private final IdentidadActual identidad;
    public PedidoResumenPagoController(PedidoService pedidos,IdentidadActual identidad) {
        this.pedidos=pedidos; this.identidad=identidad;
    }
    @GetMapping("/internal/pedidos/{id}/resumen-pago")
    public ResponseEntity<PedidoResumenPagoResponse> resumen(@PathVariable @jakarta.validation.constraints.Positive Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(pedidos.resumenParaPago(identidad.obtener(),id));
    }
}
