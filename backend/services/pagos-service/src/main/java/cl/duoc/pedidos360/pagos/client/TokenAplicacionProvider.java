package cl.duoc.pedidos360.pagos.client;

/**
 * Provee el token de <b>aplicación</b> (client_credentials) para el endpoint interno de Pedidos.
 *
 * <p>Se implementa cuando exista el worker en Entra (client ID pendiente, issue #47). El token
 * vive solo en memoria hasta renovar; nunca se persiste ni se registra. Mientras no exista
 * implementación, PedidosRestClient usa el flujo delegado.
 */
public interface TokenAplicacionProvider {

    /** Token vigente (sin el prefijo "Bearer"). */
    String token();
}
