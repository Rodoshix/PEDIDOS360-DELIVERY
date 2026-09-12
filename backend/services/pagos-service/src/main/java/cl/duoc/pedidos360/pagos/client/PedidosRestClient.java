package cl.duoc.pedidos360.pagos.client;

import java.util.Map;
import java.util.Set;

import cl.duoc.pedidos360.pagos.exception.PagoException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente HTTP hacia pedidos-service.
 * La identidad se propaga según lo acordado con I4; en local se usa el perfil de identidad local.
 */
@Component
public class PedidosRestClient implements PedidosClient {

    /** Estados del pedido que implican que la confirmación ya está aplicada. */
    private static final Set<String> ESTADOS_CONFIRMADOS =
            Set.of("CONFIRMADO", "PREPARANDO", "LISTO", "EN_REPARTO", "ENTREGADO");

    private final RestClient restClient;

    public PedidosRestClient(RestClient.Builder builder, PedidosClientProperties properties) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public PedidoResumen obtener(Long pedidoId) {
        try {
            return restClient.get()
                    .uri("/pedidos/{id}", pedidoId)
                    .retrieve()
                    .body(PedidoResumen.class);
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo consultar el pedido " + pedidoId + " en Pedidos.");
        } catch (RestClientException error) {
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo consultar el pedido " + pedidoId + " en Pedidos.");
        }
    }

    /**
     * Confirma el pedido (CREADO → CONFIRMADO).
     *
     * <p>Un 400/409 <b>no</b> se interpreta automáticamente como éxito: podría tratarse de una
     * transición inválida desde un estado como CANCELADO. En ese caso se consulta el estado real
     * del pedido y solo se considera aplicada la confirmación si el pedido ya está en un estado
     * confirmado o posterior. Si el pedido está CANCELADO u otro estado no confirmado, o si la
     * consulta falla, se propaga un error para que la coordinación quede pendiente y se reintente.
     */
    @Override
    public void confirmar(Long pedidoId) {
        try {
            restClient.put()
                    .uri("/pedidos/{id}/estado", pedidoId)
                    .body(Map.of("estado", "CONFIRMADO"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode() == HttpStatus.BAD_REQUEST
                    || error.getStatusCode() == HttpStatus.CONFLICT) {
                verificarEstadoTrasRechazo(pedidoId);
                return;
            }
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo confirmar el pedido " + pedidoId + " en Pedidos.");
        } catch (RestClientException error) {
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo confirmar el pedido " + pedidoId + " en Pedidos.");
        }
    }

    /** Confirma el rechazo consultando el estado real: solo se acepta si ya está confirmado. */
    private void verificarEstadoTrasRechazo(Long pedidoId) {
        PedidoResumen actual = obtener(pedidoId);
        if (actual != null && ESTADOS_CONFIRMADOS.contains(actual.estado())) {
            return;
        }
        throw new PagoException(HttpStatus.BAD_GATEWAY,
                "Pedidos rechazó la confirmación del pedido " + pedidoId
                        + " y su estado no es confirmado"
                        + (actual == null ? " (no se pudo consultar)." : ": " + actual.estado() + "."));
    }
}
