package cl.duoc.pedidos360.pedidos.controller;

import cl.duoc.pedidos360.pedidos.exception.PedidoException;
import cl.duoc.pedidos360.pedidos.exception.PedidoNoEncontradoException;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint interno de confirmación de pago (acuerdo issue #47).
 *
 * <p>No se expone por BFF/CORS. Solo lo invoca el worker de Pagos con un token de aplicación
 * (client_credentials) que lleva el rol {@code Pedidos.Confirmar} y ausencia de {@code scp};
 * la autorización se aplica en {@code SeguridadInternaConfiguration}, separada de las rutas
 * delegadas de usuario.
 *
 * <p>Respuestas: 204 si aplica o ya estaba confirmado; 409 si el pedido está CANCELADO;
 * 404 si no existe.
 */
@RestController
public class PedidoInternoController {

    private final PedidoService pedidos;

    public PedidoInternoController(PedidoService pedidos) {
        this.pedidos = pedidos;
    }

    @PutMapping("/internal/pedidos/{id}/confirmacion-pago")
    public ResponseEntity<Void> confirmacionPago(@PathVariable Long id) {
        try {
            pedidos.confirmarPorPago(id);
            return ResponseEntity.noContent().build();
        } catch (PedidoNoEncontradoException error) {
            return ResponseEntity.notFound().build();
        } catch (PedidoException error) {
            if (error.getStatus() == HttpStatus.CONFLICT) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            throw error;
        }
    }
}
