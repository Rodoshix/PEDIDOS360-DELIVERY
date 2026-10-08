package cl.duoc.pedidos360.pagos.client;

import java.util.Set;
import java.util.function.Supplier;

import cl.duoc.pedidos360.pagos.exception.PagoException;
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
 *   <li><b>Proyección interna de siete campos y creación de pago</b>: Bearer delegado del usuario (lo aporta la capa de seguridad).</li>
 *   <li><b>Confirmación por pago</b>: token de <b>aplicación</b> (client_credentials) contra el
 *       endpoint interno {@code PUT /internal/pedidos/{id}/confirmacion-pago} (acuerdo issue #47).</li>
 * </ul>
 *
 * <p>Mientras el endpoint interno no esté habilitado (falta el worker en Entra), se usa el
 * comportamiento anterior ({@code PUT /pedidos/{id}/estado} con Bearer delegado + verificación de
 * estado) para no romper el flujo.
 */
@Component
public class PedidosRestClient implements PedidosClient, AutoCloseable {
    private final java.net.http.HttpClient http;

    /** Estados del pedido que implican que la confirmación ya está aplicada. */
    private static final Set<String> ESTADOS_CONFIRMADOS =
            Set.of("CONFIRMADO", "PREPARANDO", "LISTO", "EN_REPARTO", "ENTREGADO");

    private final RestClient restClient;
    private final boolean internoHabilitado;
    private final Supplier<String> tokenAplicacion;

    /**
     * Si el modo interno está habilitado, exige el proveedor de token de aplicación:
     * no se degrada silenciosamente al flujo delegado (evita confirmar con la identidad
     * equivocada). Sin el modo interno, el flujo delegado es el comportamiento previsto.
     */
    public PedidosRestClient(RestClient.Builder builder, PedidosClientProperties properties,
            ObjectProvider<TokenAplicacionProvider> tokenAplicacionProvider) {
        var origin = cl.duoc.pedidos360.pagos.security.UpstreamSeguro.origen(properties.baseUrl());
        this.http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        this.restClient = builder.baseUrl(origin.toString()).requestFactory(factory).build();
        var provider = tokenAplicacionProvider.getIfAvailable();
        if (properties.internoHabilitado() && provider == null) {
            throw new IllegalStateException(
                    "pagos.pedidos.interno-habilitado=true requiere un TokenAplicacionProvider; "
                            + "no se admite el flujo delegado como respaldo.");
        }
        this.internoHabilitado = properties.internoHabilitado();
        this.tokenAplicacion = provider == null ? null : provider::token;
    }

    @Override
    public PedidoResumen obtener(Long pedidoId) {
        try {
            String body = restClient.get()
                    .uri("/internal/pedidos/{id}/resumen-pago", pedidoId)
                    .headers(PedidosRestClient::identidadDelegada)
                    .retrieve()
                    .body(String.class);
            return validarResumen(body,pedidoId);
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new PagoException(HttpStatus.FORBIDDEN,
                        "No tienes permiso para consultar los pagos de este pedido.");
            }
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

    private PedidoResumen validarResumen(String body,Long pedidoId) {
        try {
            var json=tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            var tree=json.readTree(body);
            if (!tree.isObject() || tree.size()!=7
                || !tree.path("pedidoId").isIntegralNumber() || !tree.path("pedidoId").canConvertToLong()
                || !tree.path("usuarioId").isIntegralNumber() || !tree.path("usuarioId").canConvertToLong()
                || !tree.path("total").isIntegralNumber() || !tree.path("total").canConvertToLong()
                || !tree.path("tenantId").isString() || !tree.path("estado").isString()
                || !tree.path("moneda").isString() || !tree.path("tenantOrigin").isString()) throw new IllegalArgumentException();
            var tenantId=java.util.UUID.fromString(tree.path("tenantId").stringValue());
            var estado=tree.path("estado").stringValue();
            if (!tenantId.toString().equals(tree.path("tenantId").stringValue())
                || tree.path("pedidoId").longValue()!=pedidoId || pedidoId<1
                || tree.path("usuarioId").longValue()<1 || tree.path("total").longValue()<0
                || !"CLP".equals(tree.path("moneda").stringValue())
                || !Set.of("AUTHENTICATED_NEW","RECONCILED_LEGACY").contains(tree.path("tenantOrigin").stringValue())
                || !Set.of("CREADO","CONFIRMADO","PREPARANDO","LISTO","EN_REPARTO","ENTREGADO","CANCELADO").contains(estado))
                throw new IllegalArgumentException();
            var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (!(auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)
                || !jwt.isAuthenticated()) throw new IllegalArgumentException();
            if (!tenantId.equals(java.util.UUID.fromString(jwt.getToken().getClaimAsString("tid"))))
                throw new PagoException(HttpStatus.NOT_FOUND,"Recurso no encontrado.");
            return new PedidoResumen(pedidoId,tenantId,tree.path("usuarioId").longValue(),estado,
                tree.path("total").longValue(),tree.path("moneda").stringValue(),tree.path("tenantOrigin").stringValue());
        } catch (PagoException error) { throw error; }
        catch (RuntimeException invalid) { throw new PagoException(HttpStatus.BAD_GATEWAY,"Resumen inválido."); }
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
                    .headers(PedidosRestClient::identidadDelegada)
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

    private static void identidadDelegada(org.springframework.http.HttpHeaders headers) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt
            && jwt.isAuthenticated()) headers.setBearerAuth(jwt.getToken().getTokenValue());
        // Sin token nunca se usa el worker como identidad del usuario.
    }

    @Override public void close() { http.close(); }

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
