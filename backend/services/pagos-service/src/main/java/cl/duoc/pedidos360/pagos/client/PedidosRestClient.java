package cl.duoc.pedidos360.pagos.client;

import java.util.Set;
import java.util.function.Supplier;

import cl.duoc.pedidos360.pagos.exception.PagoException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente HTTP hacia pedidos-service.
 *
 * <p>Hay dos usos con identidades distintas:
 * <ul>
 *   <li><b>Consultas y creación de pago</b>: Bearer delegado del usuario (lo aporta la capa de seguridad).</li>
 *   <li><b>Confirmación por pago</b>: token de <b>aplicación</b> (client_credentials) contra el
 *       endpoint interno {@code PUT /internal/pedidos/{id}/confirmacion-pago} (acuerdo issue #47).</li>
 * </ul>
 *
 * <p>Mientras el endpoint interno no esté habilitado (falta el worker en Entra), se usa el
 * comportamiento anterior ({@code PUT /pedidos/{id}/estado} con Bearer delegado + verificación de
 * estado) para no romper el flujo.
 */
@Component
public class PedidosRestClient implements PedidosClient {

    /** Estados del pedido que implican que la confirmación ya está aplicada. */
    private static final Set<String> ESTADOS_CONFIRMADOS =
            Set.of("CONFIRMADO", "PREPARANDO", "LISTO", "EN_REPARTO", "ENTREGADO");

    private final RestClient restClient;
    private final boolean internoHabilitado;
    private final Supplier<String> tokenAplicacion;

    public PedidosRestClient(RestClient.Builder builder, PedidosClientProperties properties,
            ObjectProvider<TokenAplicacionProvider> tokenAplicacionProvider) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        var provider = tokenAplicacionProvider.getIfAvailable();
        this.internoHabilitado = properties.internoHabilitado() && provider != null;
        this.tokenAplicacion = provider == null ? () -> null : provider::token;
        if (properties.internoHabilitado() && provider == null) {
            LoggerFactory.getLogger(PedidosRestClient.class).warn(
                    "Endpoint interno habilitado pero sin proveedor de token de aplicación: se usa el flujo delegado.");
        }
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

    @Override
    public void confirmar(Long pedidoId) {
        if (internoHabilitado) {
            confirmarConEndpointInterno(pedidoId);
        } else {
            confirmarConFlujoDelegado(pedidoId);
        }
    }

    /**
     * Confirmación con token de aplicación contra el endpoint interno.
     * 204 = aplicada o ya confirmada; 409 = pedido CANCELADO; 404 = no existe.
     */
    private void confirmarConEndpointInterno(Long pedidoId) {
        String token = tokenAplicacion.get();
        if (token == null || token.isBlank()) {
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo obtener el token de aplicación para confirmar el pedido " + pedidoId + ".");
        }
        try {
            restClient.put()
                    .uri("/internal/pedidos/{id}/confirmacion-pago", pedidoId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode() == HttpStatus.CONFLICT) {
                throw new PagoException(HttpStatus.BAD_GATEWAY,
                        "El pedido " + pedidoId + " está CANCELADO: no se confirma.");
            }
            if (error.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new PagoException(HttpStatus.BAD_GATEWAY,
                        "El pedido " + pedidoId + " no existe en Pedidos.");
            }
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo confirmar el pedido " + pedidoId + " en Pedidos.");
        } catch (RestClientException error) {
            throw new PagoException(HttpStatus.BAD_GATEWAY,
                    "No se pudo confirmar el pedido " + pedidoId + " en Pedidos.");
        }
    }

    /**
     * Flujo anterior (Bearer delegado). Un 400/409 no se interpreta automáticamente como éxito:
     * se consulta el estado real del pedido y solo se acepta si ya está confirmado o posterior.
     */
    private void confirmarConFlujoDelegado(Long pedidoId) {
        try {
            restClient.put()
                    .uri("/pedidos/{id}/estado", pedidoId)
                    .body(java.util.Map.of("estado", "CONFIRMADO"))
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
