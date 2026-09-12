package cl.duoc.pedidos360.pagos;

import cl.duoc.pedidos360.pagos.client.PedidosClientProperties;
import cl.duoc.pedidos360.pagos.client.PedidosRestClient;
import cl.duoc.pedidos360.pagos.exception.PagoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas del cliente HTTP real contra un servidor local: verifican que un 400/409 de Pedidos
 * no se interpreta automáticamente como confirmación exitosa.
 */
class PedidosRestClientTests {

    private PedidosHttpServerStub stub;
    private PedidosRestClient cliente;

    @BeforeEach
    void iniciar() throws Exception {
        stub = new PedidosHttpServerStub();
        cliente = new PedidosRestClient(RestClient.builder(),
                new PedidosClientProperties(stub.baseUrl()));
    }

    @AfterEach
    void detener() {
        stub.close();
    }

    @Test
    void confirmacionExitosaNoLanzaError() {
        stub.responderPutCon(200);

        assertThatCode(() -> cliente.confirmar(1L)).doesNotThrowAnyException();
        assertThat(stub.confirmacionesPut()).isEqualTo(1);
    }

    @Test
    void http400NoSeConsideraExitoSiElPedidoNoEstaConfirmado() {
        stub.responderPutCon(400);
        stub.responderGetCon(200, "CREADO");

        assertThatThrownBy(() -> cliente.confirmar(1L))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void http409PorPedidoCanceladoNoSeConsideraExito() {
        stub.responderPutCon(409);
        stub.responderGetCon(200, "CANCELADO");

        assertThatThrownBy(() -> cliente.confirmar(1L))
                .isInstanceOf(PagoException.class)
                .satisfies(error -> assertThat(((PagoException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void http409SeAceptaSiElPedidoYaEstaConfirmado() {
        stub.responderPutCon(409);
        stub.responderGetCon(200, "CONFIRMADO");

        assertThatCode(() -> cliente.confirmar(1L)).doesNotThrowAnyException();
    }

    @Test
    void http400NoSeConsideraExitoSiLaConsultaDeEstadoFalla() {
        stub.responderPutCon(400);
        stub.responderGetCon(500, "CREADO");

        assertThatThrownBy(() -> cliente.confirmar(1L)).isInstanceOf(PagoException.class);
    }

    @Test
    void http500DePedidosSePropagaComoError() {
        stub.responderPutCon(500);

        assertThatThrownBy(() -> cliente.confirmar(1L)).isInstanceOf(PagoException.class);
    }
}
