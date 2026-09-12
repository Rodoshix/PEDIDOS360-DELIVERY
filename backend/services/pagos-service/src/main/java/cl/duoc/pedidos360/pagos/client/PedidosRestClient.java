package cl.duoc.pedidos360.pagos.client;

import java.util.Map;

import cl.duoc.pedidos360.pagos.exception.PagoException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente HTTP hacia pedidos-service.
 * Nota: la identidad se propaga según lo acordado con I4; en local se usa el perfil de identidad local.
 */
@Component
public class PedidosRestClient implements PedidosClient {

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
        } catch (RestClientException error) {
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo consultar el pedido " + pedidoId + " en Pedidos.");
        }
    }

    @Override
    public void confirmar(Long pedidoId) {
        try {
            restClient.put()
                    .uri("/pedidos/{id}/estado", pedidoId)
                    .body(Map.of("estado", "CONFIRMADO"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException error) {
            // Pedidos ya tenía el pedido confirmado (transición inválida): confirmación idempotente.
            if (error.getStatusCode().value() == 409 || error.getStatusCode().value() == 400) {
                return;
            }
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo confirmar el pedido " + pedidoId + " en Pedidos.");
        } catch (RestClientException error) {
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo confirmar el pedido " + pedidoId + " en Pedidos.");
        }
    }
}
