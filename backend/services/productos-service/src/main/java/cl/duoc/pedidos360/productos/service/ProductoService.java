package cl.duoc.pedidos360.productos.service;

import cl.duoc.pedidos360.productos.dto.ProductoRequest;
import cl.duoc.pedidos360.productos.dto.ProductoResponse;
import cl.duoc.pedidos360.productos.entity.Producto;
import cl.duoc.pedidos360.productos.exception.ProductoNoEncontradoException;
import cl.duoc.pedidos360.productos.repository.ProductoRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductoService {

    private final ProductoRepository productoRepository;

    public ProductoService(ProductoRepository productoRepository) {
        this.productoRepository = productoRepository;
    }

    public List<ProductoResponse> listarTodos() {
        return productoRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<ProductoResponse> listarPorRestaurante(Long restauranteId) {
        return productoRepository.findByRestauranteId(restauranteId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<ProductoResponse> listarDisponiblesPorRestaurante(Long restauranteId) {
        return productoRepository.findByRestauranteIdAndDisponibleTrue(restauranteId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ProductoResponse buscarPorId(Long id) {
        Producto producto = buscarEntidad(id);
        return toResponse(producto);
    }

    public ProductoResponse crear(ProductoRequest request) {
        Producto producto = new Producto(
                request.getRestauranteId(),
                request.getNombre(),
                request.getDescripcion(),
                request.getPrecio(),
                request.getCategoria(),
                request.getDisponible()
        );

        return toResponse(productoRepository.save(producto));
    }

    public ProductoResponse actualizar(Long id, ProductoRequest request) {
        Producto producto = buscarEntidad(id);

        producto.setRestauranteId(request.getRestauranteId());
        producto.setNombre(request.getNombre());
        producto.setDescripcion(request.getDescripcion());
        producto.setPrecio(request.getPrecio());
        producto.setCategoria(request.getCategoria());
        producto.setDisponible(request.getDisponible());

        return toResponse(productoRepository.save(producto));
    }

    public ProductoResponse cambiarDisponibilidad(Long id, boolean disponible) {
        Producto producto = buscarEntidad(id);
        producto.setDisponible(disponible);

        return toResponse(productoRepository.save(producto));
    }

    private Producto buscarEntidad(Long id) {
        return productoRepository.findById(id)
                .orElseThrow(() -> new ProductoNoEncontradoException(id));
    }

    private ProductoResponse toResponse(Producto producto) {
        return new ProductoResponse(
                producto.getId(),
                producto.getRestauranteId(),
                producto.getNombre(),
                producto.getDescripcion(),
                producto.getPrecio(),
                producto.getCategoria(),
                producto.isDisponible()
        );
    }
}