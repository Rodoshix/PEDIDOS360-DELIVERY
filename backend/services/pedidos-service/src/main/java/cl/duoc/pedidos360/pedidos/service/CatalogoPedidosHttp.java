package cl.duoc.pedidos360.pedidos.service;

import java.net.URI;
import cl.duoc.pedidos360.pedidos.security.UpstreamSeguro;
import cl.duoc.pedidos360.pedidos.exception.PedidoException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

/** Precio y pertenencia obtenidos de Productos, nunca de la petición del comprador. */
@Component
public class CatalogoPedidosHttp implements CatalogoPedidos {
    private final UpstreamSeguro http;
    private final URI productos;
    public CatalogoPedidosHttp(UpstreamSeguro http, Environment env) {
        this.http = http;
        productos = UpstreamSeguro.origen(env.getProperty("pedidos.productos-url", "http://localhost:8083"));
    }
    @Override public long precio(Long productoId, Long restauranteId) {
        if (productoId == null || productoId < 1 || restauranteId == null || restauranteId < 1)
            throw new PedidoException(HttpStatus.BAD_REQUEST, "Producto o restaurante inválido.");
        var json = http.get(productos.resolve("/productos/" + productoId), null);
        var id = json.get("id");
        var restaurant = json.get("restauranteId");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != productoId
            || restaurant == null || !restaurant.isIntegralNumber() || !restaurant.canConvertToLong()
            || restaurant.longValue() < 1 || !json.path("disponible").isBoolean()
            || !json.path("precio").isNumber())
            throw new PedidoException(HttpStatus.SERVICE_UNAVAILABLE, "Respuesta de catálogo inválida.");
        if (restaurant.longValue() != restauranteId || !json.path("disponible").booleanValue())
            throw new PedidoException(HttpStatus.CONFLICT, "Producto no disponible para este restaurante.");
        try {
            long price = new java.math.BigDecimal(json.get("precio").asText()).longValueExact();
            if (price < 0 || price > 100000000L) throw new ArithmeticException();
            return price;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new PedidoException(HttpStatus.SERVICE_UNAVAILABLE, "Precio CLP inválido.");
        }
    }
}
