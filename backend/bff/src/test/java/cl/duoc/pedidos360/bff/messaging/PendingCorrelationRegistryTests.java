package cl.duoc.pedidos360.bff.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import cl.duoc.pedidos360.messaging.relay.PendingCorrelationRegistry;

/**
 * Registro de correlaciones del BFF.
 *
 * <p>Cubre los casos 3, 4 y 5 del issue #77: varios requests simultaneos, correlationId desconocido y
 * respuesta tardia.
 */
class PendingCorrelationRegistryTests {

    private static byte[] cuerpo(String valor) {
        return valor.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void resuelveLaEsperaRegistrada() {
        var registro = new PendingCorrelationRegistry(8);
        CompletableFuture<byte[]> espera = registro.registrar("corr-1");
        assertThat(registro.enVuelo()).isEqualTo(1);
        assertThat(registro.completar("corr-1", cuerpo("respuesta"))).isTrue();
        assertThat(espera.join()).isEqualTo(cuerpo("respuesta"));
        assertThat(registro.enVuelo()).isZero();
    }

    @Test
    void unaCorrelacionDesconocidaSeDescarta() {
        var registro = new PendingCorrelationRegistry(8);
        assertThat(registro.completar("no-existe", cuerpo("x"))).isFalse();
        assertThat(registro.pendiente("no-existe")).isEmpty();
        assertThat(registro.enVuelo()).isZero();
    }

    @Test
    void unaRespuestaTardiaSeDescartaYLaEsperaSigueViva() {
        var registro = new PendingCorrelationRegistry(8);
        registro.registrar("corr-tarde");
        assertThat(registro.descartar("corr-tarde")).isTrue();
        assertThat(registro.completar("corr-tarde", cuerpo("tardia"))).isFalse();
        assertThat(registro.enVuelo()).isZero();
    }

    @Test
    void unaRespuestaDuplicadaSeDescartaSinAfectarALaPrimera() {
        var registro = new PendingCorrelationRegistry(8);
        CompletableFuture<byte[]> espera = registro.registrar("corr-dup");
        assertThat(registro.completar("corr-dup", cuerpo("primera"))).isTrue();
        assertThat(registro.completar("corr-dup", cuerpo("segunda"))).isFalse();
        assertThat(espera.join()).isEqualTo(cuerpo("primera"));
        assertThat(registro.enVuelo()).isZero();
    }

    @Test
    void soportaMultiplesRequestsSimultaneosSinCruzarse() throws Exception {
        var registro = new PendingCorrelationRegistry(64);
        int total = 32;
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<byte[]>> esperas = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                esperas.add(registro.registrar("corr-" + i));
            }
            assertThat(registro.enVuelo()).isEqualTo(total);
            List<CompletableFuture<?>> respuestas = new ArrayList<>();
            for (int i = total - 1; i >= 0; i--) {
                int indice = i;
                respuestas.add(CompletableFuture.runAsync(
                        () -> registro.completar("corr-" + indice, cuerpo("respuesta-" + indice)), executor));
            }
            CompletableFuture.allOf(respuestas.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            for (int i = 0; i < total; i++) {
                assertThat(esperas.get(i).join()).isEqualTo(cuerpo("respuesta-" + i));
            }
            assertThat(registro.enVuelo()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rechazaNuevasEsperasAlSuperarElLimite() {
        var registro = new PendingCorrelationRegistry(2);
        registro.registrar("a");
        registro.registrar("b");
        assertThatThrownBy(() -> registro.registrar("c")).isInstanceOf(IllegalStateException.class);
        assertThat(registro.enVuelo()).isEqualTo(2);
        registro.completar("a", cuerpo("x"));
        assertThat(registro.registrar("c")).isNotNull();
    }

    @Test
    void descartarUnaCorrelacionDesconocidaNoAfectaElRegistro() {
        var registro = new PendingCorrelationRegistry(2);
        assertThat(registro.descartar("nada")).isFalse();
        assertThat(registro.descartar(null)).isFalse();
        assertThat(registro.completar(null, cuerpo("x"))).isFalse();
    }
}
