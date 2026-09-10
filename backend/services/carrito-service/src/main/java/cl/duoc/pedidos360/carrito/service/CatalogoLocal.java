package cl.duoc.pedidos360.carrito.service;

import java.util.Map;

import cl.duoc.pedidos360.carrito.exception.ApiException;
import org.springframework.http.HttpStatus;

// No es @Component: solo se instancia tras habilitar y validar el modo local.
public final class CatalogoLocal implements CatalogoProductos {
    private final Map<Long, Producto> productos = Map.of(
            101L, new Producto(101L, 20L, "Hamburguesa de prueba", 6990L, true),
            102L, new Producto(102L, 20L, "Bebida de prueba", 1500L, true),
            103L, new Producto(103L, 20L, "Producto no disponible de prueba", 3000L, false),
            201L, new Producto(201L, 21L, "Pizza de otro restaurante de prueba", 8990L, true));

    @Override
    public Producto obtener(Long productoId) {
        var producto = productos.get(productoId);
        if (producto == null) throw new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado.");
        return producto;
    }
}
