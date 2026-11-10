package cl.duoc.pedidos360.productos.kafka;

import cl.duoc.pedidos360.kafka.KafkaStores;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "p360.kafka.enabled", havingValue = "true")
public class RestauranteEstadoController {
  private final KafkaStores stores;

  public RestauranteEstadoController(KafkaStores stores) {
    this.stores = stores;
  }

  @GetMapping("/productos/restaurante/{id}/estado-restaurante")
  public ResponseEntity<Map<String, Object>> obtener(@PathVariable long id) {
    if (id < 1)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stores.observation(id));
  }
}
