package cl.duoc.pedidos360.carrito.service;

public interface CatalogoProductos {
    Producto obtener(Long productoId);

    record Producto(Long id, Long restauranteId, String nombre, long precio, boolean disponible) { }
}
