package cl.duoc.pedidos360.restaurantes.exception;

public class RestauranteNoEncontradoException extends RuntimeException {

    public RestauranteNoEncontradoException(Long id) {
        super("No se encontró el restaurante con id " + id);
    }
}