package cl.duoc.pedidos360.carrito.service;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;

import cl.duoc.pedidos360.carrito.dto.AgregarProductoRequest;
import cl.duoc.pedidos360.carrito.dto.CarritoResponse;
import cl.duoc.pedidos360.carrito.entity.Carrito;
import cl.duoc.pedidos360.carrito.exception.ApiException;
import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import cl.duoc.pedidos360.carrito.security.IdentidadActual;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CarritoService {
    private final CarritoRepository carritos;
    private final IdentidadActual identidad;
    private final ObjectProvider<CatalogoProductos> catalogos;

    public CarritoService(CarritoRepository carritos, IdentidadActual identidad, ObjectProvider<CatalogoProductos> catalogos) {
        this.carritos = carritos;
        this.identidad = identidad;
        this.catalogos = catalogos;
    }

    private Optional<Carrito> buscarPropio() {
        var usuario = identidad.obtener();
        return carritos.findByTenantIdAndEntraObjectId(usuario.tenantId(), usuario.objectId());
    }

    @Transactional(readOnly = true)
    public CarritoResponse obtener() {
        return buscarPropio().map(this::respuesta).orElseGet(CarritoResponse::vacio);
    }

    @Transactional
    public CarritoResponse agregar(AgregarProductoRequest request) {
        var usuario = identidad.obtener();
        var producto = productoDisponible(request.productoId());
        var carrito = buscarPropio().orElseGet(() -> new Carrito(usuario.tenantId(), usuario.objectId()));
        modificar(carrito, actual -> actual.agregarProducto(producto.id(), producto.restauranteId(),
                producto.nombre(), producto.precio(), request.cantidad()));
        return respuesta(carritos.saveAndFlush(carrito));
    }

    @Transactional
    public CarritoResponse cambiarCantidad(Long productoId, int cantidad) {
        var carrito = buscarPropio().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado en el carrito."));
        // Comprueba disponibilidad; cambiar cantidad conserva la referencia de precio guardada.
        if (carrito.getLineas().stream().noneMatch(linea -> linea.getProductoId().equals(productoId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado en el carrito.");
        }
        var producto = productoDisponible(productoId);
        if (!producto.restauranteId().equals(carrito.getRestauranteId())) {
            throw new ApiException(HttpStatus.CONFLICT, "El producto cambió de restaurante. Quita la línea y vuelve a agregarlo.");
        }
        modificar(carrito, actual -> actual.cambiarCantidad(productoId, cantidad));
        carritos.flush();
        return respuesta(carrito);
    }

    @Transactional
    public void quitar(Long productoId) {
        var carrito = buscarPropio().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado en el carrito."));
        modificar(carrito, actual -> actual.quitarProducto(productoId));
        carritos.flush();
    }

    @Transactional
    public void vaciar() {
        buscarPropio().ifPresent(carrito -> {
            carrito.vaciar();
            carritos.flush();
        });
    }

    private CatalogoProductos.Producto productoDisponible(Long id) {
        var catalogo = catalogos.getIfAvailable();
        if (catalogo == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Catálogo no configurado.");
        CatalogoProductos.Producto producto;
        try {
            producto = catalogo.obtener(id);
        } catch (ApiException error) {
            if (error.getStatus() == HttpStatus.NOT_FOUND) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado.");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo consultar el catálogo.");
        } catch (RuntimeException error) {
            // El mensaje/cause del adaptador puede contener URLs internas o credenciales.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo consultar el catálogo.");
        }
        if (producto == null || !id.equals(producto.id()) || producto.restauranteId() == null
                || producto.restauranteId() <= 0 || producto.nombre() == null || producto.nombre().isBlank()
                || producto.nombre().strip().length() > 200 || producto.precio() < 0 || producto.precio() > Carrito.MAX_PRECIO) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "El catálogo devolvió datos inválidos.");
        }
        if (!producto.disponible()) throw new ApiException(HttpStatus.CONFLICT, "El producto no está disponible.");
        return producto;
    }

    private void modificar(Carrito carrito, Consumer<Carrito> operacion) {
        try { operacion.accept(carrito); }
        catch (NoSuchElementException error) { throw new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado en el carrito."); }
        catch (IllegalArgumentException error) { throw new ApiException(HttpStatus.BAD_REQUEST, error.getMessage()); }
        catch (IllegalStateException error) { throw new ApiException(HttpStatus.CONFLICT, error.getMessage()); }
    }

    private CarritoResponse respuesta(Carrito carrito) {
        var items = carrito.getLineas().stream()
                .sorted(java.util.Comparator.comparing(cl.duoc.pedidos360.carrito.entity.LineaCarrito::getProductoId))
                .map(linea -> new CarritoResponse.LineaResponse(
                linea.getProductoId(), linea.getNombreProducto(), linea.getPrecioUnitario(),
                linea.getCantidad(), linea.getSubtotal())).toList();
        return new CarritoResponse(carrito.getId(), carrito.getRestauranteId(), carrito.getMoneda(),
                carrito.getTotal(), carrito.getVersion(), carrito.getActualizadoEn(), items);
    }
}
