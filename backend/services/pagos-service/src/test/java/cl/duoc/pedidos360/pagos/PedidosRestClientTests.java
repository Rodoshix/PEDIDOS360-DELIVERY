package cl.duoc.pedidos360.pagos;

import cl.duoc.pedidos360.pagos.client.PedidosClientProperties;
import cl.duoc.pedidos360.pagos.client.PedidosRestClient;
import cl.duoc.pedidos360.pagos.client.TokenAplicacionProvider;
import cl.duoc.pedidos360.pagos.exception.PagoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas del cliente HTTP real contra un servidor local.
 * Cubren tanto el flujo delegado (400/409 no equivalen a éxito) como el endpoint interno
 * con token de aplicación (204/409/404).
 */
class PedidosRestClientTests {

    private PedidosHttpServerStub stub;

    @AfterEach
    void detener() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        stub.close();
    }

    @Test
    void consultaPropagaUsuarioPeroConfirmacionUsaWorker() throws Exception {
        var cliente = conInterno("worker-prueba");
        var jwt = cl.duoc.pedidos360.pagos.security.EntraTestTokens.decoder().decode(
            cl.duoc.pedidos360.pagos.security.EntraTestTokens.token(java.util.Map.of()));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt,
                cl.duoc.pedidos360.pagos.security.EntraConfiguration.authorities(jwt)));
        stub.responderGetCon(200, "CREADO");
        cliente.obtener(1L);
        assertThat(stub.ultimoAuthorization()).isEqualTo("Bearer " + jwt.getTokenValue());
        stub.responderInternoCon(204);
        cliente.confirmar(1L);
        assertThat(stub.ultimoAuthorization()).isEqualTo("Bearer worker-prueba");
    }

    private PedidosRestClient delegado() throws Exception {
        stub = new PedidosHttpServerStub();
        return new PedidosRestClient(RestClient.builder(),
                new PedidosClientProperties(stub.baseUrl(), false), sinProveedor());
    }

    private PedidosRestClient conInterno(String token) throws Exception {
        stub = new PedidosHttpServerStub();
        return new PedidosRestClient(RestClient.builder(),
                new PedidosClientProperties(stub.baseUrl(), true), proveedor(token));
    }

    @Test
    void confirmacionExitosaNoLanzaError() throws Exception {
        var cliente = delegado();
        stub.responderPutCon(200);

        assertThatCode(() -> cliente.confirmar(1L)).doesNotThrowAnyException();
        assertThat(stub.confirmacionesPut()).isEqualTo(1);
    }

    @Test
    void http400NoSeConsideraExitoSiElPedidoNoEstaConfirmado() throws Exception {
        var cliente = delegado();
        stub.responderPutCon(400);
        stub.responderGetCon(200, "CREADO");

        assertThatThrownBy(() -> cliente.confirmar(1L))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void http409PorPedidoCanceladoNoSeConsideraExito() throws Exception {
        var cliente = delegado();
        stub.responderPutCon(409);
        stub.responderGetCon(200, "CANCELADO");

        assertThatThrownBy(() -> cliente.confirmar(1L)).isInstanceOf(PagoException.class);
    }

    @Test
    void http409SeAceptaSiElPedidoYaEstaConfirmado() throws Exception {
        var cliente = delegado();
        stub.responderPutCon(409);
        stub.responderGetCon(200, "CONFIRMADO");

        assertThatCode(() -> cliente.confirmar(1L)).doesNotThrowAnyException();
    }

    @Test
    void http400NoSeConsideraExitoSiLaConsultaDeEstadoFalla() throws Exception {
        var cliente = delegado();
        stub.responderPutCon(400);
        stub.responderGetCon(500, "CREADO");

        assertThatThrownBy(() -> cliente.confirmar(1L)).isInstanceOf(PagoException.class);
    }

    @Test
    void http500DePedidosSePropagaComoError() throws Exception {
        var cliente = delegado();
        stub.responderPutCon(500);

        assertThatThrownBy(() -> cliente.confirmar(1L)).isInstanceOf(PagoException.class);
    }

    // --- Endpoint interno (token de aplicación) ---

    @Test
    void interno204SeConsideraConfirmadoYEnviaElToken() throws Exception {
        var cliente = conInterno("token-app");
        stub.responderInternoCon(204);

        assertThatCode(() -> cliente.confirmar(1L)).doesNotThrowAnyException();
        assertThat(stub.confirmacionesInternas()).isEqualTo(1);
        assertThat(stub.confirmacionesPut()).isZero();
        assertThat(stub.ultimoAuthorization()).isEqualTo("Bearer token-app");
    }

    @Test
    void interno409PorCanceladoSeReportaComoError() throws Exception {
        var cliente = conInterno("token-app");
        stub.responderInternoCon(409);

        assertThatThrownBy(() -> cliente.confirmar(1L)).isInstanceOf(PagoException.class);
    }

    @Test
    void interno404PorPedidoInexistenteSeReportaComoError() throws Exception {
        var cliente = conInterno("token-app");
        stub.responderInternoCon(404);

        assertThatThrownBy(() -> cliente.confirmar(1L)).isInstanceOf(PagoException.class);
    }

    @Test
    void internoSinTokenDisponibleFalla() throws Exception {
        var cliente = conInterno(null);
        stub.responderInternoCon(204);

        assertThatThrownBy(() -> cliente.confirmar(1L)).isInstanceOf(PagoException.class);
    }

    @Test
    void internoHabilitadoSinProveedorFallaAlConstruirYNoUsaLaRutaDelegada() throws Exception {
        stub = new PedidosHttpServerStub();
        // Modo interno habilitado sin proveedor: debe fallar explícitamente, no degradar al flujo delegado.
        assertThatThrownBy(() -> new PedidosRestClient(RestClient.builder(),
                new PedidosClientProperties(stub.baseUrl(), true), sinProveedor()))
                .isInstanceOf(IllegalStateException.class);

        // Y no se envió ninguna solicitud por la ruta alternativa.
        assertThat(stub.confirmacionesPut()).isZero();
    }

    private ObjectProvider<TokenAplicacionProvider> proveedor(String token) {
        return new ObjectProvider<>() {
            @Override public TokenAplicacionProvider getObject(Object... args) { return () -> token; }
            @Override public TokenAplicacionProvider getObject() { return () -> token; }
            @Override public TokenAplicacionProvider getIfAvailable() { return () -> token; }
            @Override public TokenAplicacionProvider getIfUnique() { return () -> token; }
        };
    }

    private ObjectProvider<TokenAplicacionProvider> sinProveedor() {
        return new ObjectProvider<>() {
            @Override public TokenAplicacionProvider getObject(Object... args) { return null; }
            @Override public TokenAplicacionProvider getObject() { return null; }
            @Override public TokenAplicacionProvider getIfAvailable() { return null; }
            @Override public TokenAplicacionProvider getIfUnique() { return null; }
        };
    }
}
