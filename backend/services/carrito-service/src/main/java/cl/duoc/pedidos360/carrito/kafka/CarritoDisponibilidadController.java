package cl.duoc.pedidos360.carrito.kafka;

import cl.duoc.pedidos360.carrito.service.CarritoService;
import cl.duoc.pedidos360.kafka.KafkaStores;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "p360.kafka.enabled", havingValue = "true")
public class CarritoDisponibilidadController {
  private final CarritoService carritos;
  private final KafkaStores stores;

  public CarritoDisponibilidadController(CarritoService carritos, KafkaStores stores) {
    this.carritos = carritos;
    this.stores = stores;
  }

  @GetMapping("/carrito/disponibilidad")
  public ResponseEntity<Map<String, Object>> obtener() {
    // obtener() resolves authenticated tid/oid; never accept a cart/user ID from the caller.
    var cart = carritos.obtener();
    var items = cart.items().stream().map(item -> stores.observation(item.productoId())).toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Map.of("items", items, "informativo", true));
  }
}
