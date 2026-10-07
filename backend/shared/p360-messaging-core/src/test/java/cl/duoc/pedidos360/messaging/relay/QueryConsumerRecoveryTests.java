package cl.duoc.pedidos360.messaging.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.Lifecycle;
import org.springframework.context.SmartLifecycle;

/**
 * Maquina de estados de la recuperacion del consumidor de consultas.
 *
 * <p>Cubre las propiedades exigidas al mecanismo reutilizable de #77: un solo ciclo a la vez,
 * solicitudes concurrentes que no se pierden, recovery pendiente durante STARTING, cierre terminal,
 * reintento cuando el listener no esta registrado y ausencia de bucles calientes.
 */
class QueryConsumerRecoveryTests {

    private static final String LISTENER = "listener-de-prueba";
    private static final Duration BACKOFF = Duration.ofMillis(40);
    private static final Duration ESPERA = Duration.ofSeconds(5);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var hilo = new Thread(r, "recuperacion-de-prueba");
        hilo.setDaemon(true);
        return hilo;
    });

    @AfterEach
    void cerrarExecutor() {
        executor.shutdownNow();
    }

    /**
     * Container simulado: cuenta detenciones e inicios y permite provocar una solicitud justo en
     * STARTING, que es el instante en el que el ciclo debe acumularla en lugar de perderla.
     *
     * <p>Implementa el contrato minimo de {@link MessageListenerContainer} de Spring AMQP 4.1.
     */
    static class ContenedorFalso implements MessageListenerContainer {

        private final AtomicInteger detenciones = new AtomicInteger();
        private final AtomicInteger inicios = new AtomicInteger();
        private final java.util.List<String> bitacora = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicBoolean avisoConsumido =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final boolean avisarDuranteElArranque;
        private QueryConsumerRecovery recuperacion;
        private MessageListener listener;
        private String listenerId = LISTENER;

        ContenedorFalso() {
            this(false);
        }

        ContenedorFalso(boolean avisarDuranteElArranque) {
            this.avisarDuranteElArranque = avisarDuranteElArranque;
        }

        void conectar(QueryConsumerRecovery recuperacion) {
            this.recuperacion = recuperacion;
        }

        int detenciones() {
            return detenciones.get();
        }

        int inicios() {
            return inicios.get();
        }

        String bitacora() {
            return String.join(",", bitacora);
        }

        MessageListener listener() {
            return listener;
        }

        @Override
        public void stop(Runnable callback) {
            detenciones.incrementAndGet();
            bitacora.add("stop#cb");
            callback.run();
        }

        @Override
        public void stop() {
            detenciones.incrementAndGet();
            bitacora.add("stop");
        }

        @Override
        public void start() {
            inicios.incrementAndGet();
            bitacora.add("start" + inicios.get());
            // Aviso unico: sin esta guarda cada reinicio pediria otro ciclo y la prueba mediria un
            // bucle, no la solicitud pendiente durante STARTING.
            if (avisarDuranteElArranque && avisoConsumido.compareAndSet(false, true) && recuperacion != null) {
                bitacora.add("solicitud-mid-start");
                recuperacion.sinConfirmar("msg-durante-starting", "corr-starting", "retry", null);
            }
        }
        @Override
        public boolean isRunning() {
            return inicios.get() > detenciones.get();
        }

        @Override
        public void setupMessageListener(MessageListener messageListener) {
            this.listener = messageListener;
        }

        @Override
        public void setQueueNames(String... queues) {
        }

        @Override
        public void setAutoStartup(boolean autoStart) {
        }

        @Override
        public Object getMessageListener() {
            return listener;
        }

        @Override
        public void setListenerId(String id) {
            this.listenerId = id;
        }

        public String getListenerId() {
            return listenerId;
        }

        public void destroy() {
        }

        public void afterPropertiesSet() {
        }

        public boolean isAutoStartup() {
            return true;
        }

        public boolean isActive() {
            return true;
        }
    }

    private RabbitListenerEndpointRegistry registro(MessageListenerContainer contenedor) {
        var registro = mock(RabbitListenerEndpointRegistry.class);
        when(registro.getListenerContainer(LISTENER)).thenReturn(contenedor);
        return registro;
    }

    private QueryConsumerRecovery recuperacion(RabbitListenerEndpointRegistry registro) {
        return new QueryConsumerRecovery(registro, BACKOFF, List.of(LISTENER), executor);
    }

    @Test
    void unHandoffSinConfirmarDetieneYReiniciaElListenerTrasElBackoff() {
        var contenedor = new ContenedorFalso();
        var recuperacion = recuperacion(registro(contenedor));

        long inicio = System.nanoTime();
        recuperacion.sinConfirmar("msg-1", "corr-1", "retry", new RuntimeException("sin broker"));
        assertThat(recuperacion.enRecuperacion()).as("el ciclo arranca en cuanto se solicita").isTrue();

        org.awaitility.Awaitility.await().atMost(ESPERA).until(() -> contenedor.inicios() == 1);
        long transcurrido = System.nanoTime() - inicio;
        assertThat(contenedor.detenciones()).as("se detiene exactamente una vez").isEqualTo(1);
        assertThat(Duration.ofNanos(transcurrido)).as("el reinicio respeta el backoff")
                .isGreaterThanOrEqualTo(BACKOFF);

        org.awaitility.Awaitility.await().atMost(ESPERA).until(() -> !recuperacion.enRecuperacion());
        assertThat(recuperacion.enRecuperacion()).as("el ciclo termina y vuelve a reposo").isFalse();
        assertThat(contenedor.inicios()).as("sin bucles adicionales").isEqualTo(1);
        assertThat(contenedor.detenciones()).as("una detencion por ciclo").isEqualTo(1);
    }

    @Test
    void lasSolicitudesConcurrentesNoSePierdenYNoSolapanCiclos() {
        var contenedor = new ContenedorFalso();
        var recuperacion = recuperacion(registro(contenedor));

        recuperacion.sinConfirmar("msg-1", "corr-1", "retry", null);
        // Solicitudes mientras el primer ciclo esta en curso: se acumulan, no se pierden.
        recuperacion.sinConfirmar("msg-2", "corr-2", "retry", null);
        recuperacion.sinConfirmar("msg-3", "corr-3", "retry", null);

        org.awaitility.Awaitility.await().atMost(ESPERA).until(() -> contenedor.inicios() == 2);
        org.awaitility.Awaitility.await().atMost(ESPERA).until(() -> !recuperacion.enRecuperacion());
        assertThat(contenedor.detenciones()).as("nunca hay dos ciclos simultaneos").isEqualTo(2);
        assertThat(contenedor.inicios()).as("un solo ciclo adicional para las solicitudes acumuladas").isEqualTo(2);
    }

    @Test
    void unaSolicitudDuranteStartingQuedaPendienteYNoSePierde() {
        var contenedor = new ContenedorFalso(true);
        var recuperacion = recuperacion(registro(contenedor));
        contenedor.conectar(recuperacion);

        recuperacion.sinConfirmar("msg-1", "corr-1", "retry", null);
        // El ciclo convierte la solicitud acumulada durante STARTING en un segundo ciclo, y termina.
        org.awaitility.Awaitility.await().atMost(ESPERA).alias("dos ciclos completos")
                .until(() -> contenedor.inicios() == 2 && contenedor.detenciones() == 2
                        && !recuperacion.enRecuperacion());
        assertThat(contenedor.inicios()).as("segundo ciclo (bitacora=%s)", contenedor.bitacora()).isEqualTo(2);
        assertThat(contenedor.detenciones()).as("una detencion por ciclo (bitacora=%s)", contenedor.bitacora())
                .isEqualTo(2);
        org.awaitility.Awaitility.await().atMost(ESPERA).until(() -> !recuperacion.enRecuperacion());
    }

    @Test
    void closeEsTerminalYNoPermiteNuevosCiclos() throws Exception {
        var contenedor = new ContenedorFalso();
        var recuperacion = recuperacion(registro(contenedor));

        recuperacion.sinConfirmar("msg-1", "corr-1", "retry", null);
        org.awaitility.Awaitility.await().atMost(ESPERA).until(() -> contenedor.inicios() == 1);
        org.awaitility.Awaitility.await().atMost(ESPERA).until(() -> !recuperacion.enRecuperacion());

        recuperacion.close();
        int iniciosTrasCierre = contenedor.inicios();
        recuperacion.sinConfirmar("msg-2", "corr-2", "retry", null);
        Thread.sleep(BACKOFF.toMillis() * 3);
        assertThat(contenedor.inicios()).as("un ciclo ya cerrado no reinicia nada").isEqualTo(iniciosTrasCierre);
        assertThat(contenedor.detenciones()).as("no se detiene nada mas (bitacora=%s)", contenedor.bitacora())
                .isEqualTo(1);
        assertThat(executor.isShutdown()).as("el executor queda cerrado").isTrue();
    }

    @Test
    void unListenerNoRegistradoReintentaLaDetencionConBackoffYNoGiraEnVacio() {
        var registro = mock(RabbitListenerEndpointRegistry.class);
        when(registro.getListenerContainer(LISTENER)).thenReturn(null);
        var recuperacion = recuperacion(registro);

        recuperacion.sinConfirmar("msg-1", "corr-1", "retry", null);
        org.awaitility.Awaitility.await().atMost(ESPERA).until(() -> busquedas(registro) >= 2);
        assertThat(busquedas(registro)).as("se reintenta la detencion sin bucle caliente").isGreaterThanOrEqualTo(2);
        recuperacion.close();
    }

    @Test
    void unContainerNuncaRegistradoNoRompeLaSolicitud() {
        var registro = mock(RabbitListenerEndpointRegistry.class);
        when(registro.getListenerContainer("otro")).thenReturn(null);
        var recuperacion = recuperacion(registro);
        assertThat(recuperacion.enRecuperacion()).isFalse();
        recuperacion.close();
        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    void elContainerFalsoCumpleElContratoQueUsaElCiclo() {
        var contenedor = new ContenedorFalso();
        assertThat(contenedor.getListenerId()).isEqualTo(LISTENER);
        assertThat(contenedor.isActive()).isTrue();
        assertThat(contenedor.isAutoStartup()).isTrue();
        assertThat(contenedor.getMessageListener()).isNull();
        contenedor.setupMessageListener(mensaje -> {
        });
        assertThat(contenedor.getMessageListener()).isNotNull();
        assertThat(contenedor).isInstanceOf(SmartLifecycle.class).isInstanceOf(Lifecycle.class);
    }

    private static long busquedas(RabbitListenerEndpointRegistry registro) {
        Collection<String> metodos = List.of("getListenerContainer");
        return org.mockito.Mockito.mockingDetails(registro).getInvocations().stream()
                .filter(i -> metodos.contains(i.getMethod().getName())).count();
    }
}
