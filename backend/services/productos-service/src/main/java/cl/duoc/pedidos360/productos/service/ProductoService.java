package cl.duoc.pedidos360.productos.service;

import cl.duoc.pedidos360.productos.dto.ProductoRequest;
import cl.duoc.pedidos360.productos.dto.ProductoResponse;
import cl.duoc.pedidos360.productos.entity.Producto;
import cl.duoc.pedidos360.productos.exception.ProductoNoEncontradoException;
import cl.duoc.pedidos360.productos.repository.ProductoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import cl.duoc.pedidos360.kafka.EventCodec;
import cl.duoc.pedidos360.kafka.KafkaStores;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ProductoService {

    private final ProductoRepository productoRepository;

    private KafkaStores kafka;
    @Autowired
    void configureKafka(ObjectProvider<KafkaStores> stores) {
        kafka = stores.getIfAvailable();
    }

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

    @Transactional
    public ProductoResponse crear(ProductoRequest request) {
        Producto producto = new Producto(
                request.getRestauranteId(),
                request.getNombre(),
                request.getDescripcion(),
                request.getPrecio(),
                request.getCategoria(),
                request.getDisponible()
        );

        Producto guardado = productoRepository.save(producto);
        if (kafka != null) {
            kafka.record(EventCodec.PRODUCTOS, guardado.getId(), Boolean.toString(guardado.isDisponible()), false);
        }
        return toResponse(guardado);
    }

    @Transactional
    public ProductoResponse actualizar(Long id, ProductoRequest request) {
        Producto producto = buscarParaModificar(id);

        producto.setRestauranteId(request.getRestauranteId());
        producto.setNombre(request.getNombre());
        producto.setDescripcion(request.getDescripcion());
        producto.setPrecio(request.getPrecio());
        producto.setCategoria(request.getCategoria());
        producto.setDisponible(request.getDisponible());

        Producto guardado = productoRepository.save(producto);
        if (kafka != null) {
            kafka.record(EventCodec.PRODUCTOS, guardado.getId(), Boolean.toString(guardado.isDisponible()), false);
        }
        return toResponse(guardado);
    }

    @Transactional
    public ProductoResponse cambiarDisponibilidad(Long id, boolean disponible) {
        Producto producto = buscarParaModificar(id);
        producto.setDisponible(disponible);

        Producto guardado = productoRepository.save(producto);
        if (kafka != null) {
            kafka.record(EventCodec.PRODUCTOS, guardado.getId(), Boolean.toString(guardado.isDisponible()), false);
        }
        return toResponse(guardado);
    }

    private Producto buscarParaModificar(Long id) {
        if (kafka == null) return buscarEntidad(id);
        return productoRepository.findForUpdate(id)
                .orElseThrow(() -> new ProductoNoEncontradoException(id));
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