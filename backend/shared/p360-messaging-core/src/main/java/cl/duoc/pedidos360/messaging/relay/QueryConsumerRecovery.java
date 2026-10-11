package cl.duoc.pedidos360.messaging.relay;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * Recuperacion activa del consumidor de consultas cuando un handoff queda sin confirmar.
 *
 * <p>Problema que resuelve: con {@code AcknowledgeMode.MANUAL} y {@code prefetch=1}, un mensaje que
 * no se confirma ni se rechaza ocupa la unica ventana de prefetch, el canal sigue sano y el
 * container no se reinicia solo. Sin este ciclo el consumidor queda detenido indefinidamente.
 *
 * <p>Ciclo de recuperacion:
 *
 * <pre>
 * IDLE --sinConfirmar--> STOPPING --container detenido--> BACKOFF --espera--> STARTING --> IDLE
 * </pre>
 *
 * <p>Al detener el container se cierra el canal; el broker reentrega el mensaje sin confirmar. La
 * espera de {@code BACKOFF} evita el bucle caliente: el mensaje vuelve a la cola, pero el consumidor
 * no lo vuelve a tomar hasta cumplido el backoff.
 *
 * <p>Invariantes:
 *
 * <ul>
 *   <li>un solo ciclo de recuperacion a la vez: las solicitudes concurrentes se acumulan en
 *       {@code pending} y no se pierden;</li>
 *   <li>ninguna operacion de container se ejecuta con el lock tomado, para que un listener no
 *       bloquee la recuperacion ni la recuperacion bloquee al listener;</li>
 *   <li>los callbacks de un ciclo anterior se descartan por generacion: no reinician nada;</li>
 *   <li>{@code close()} es terminal y se ejecuta antes de que Spring detenga los containers;</li>
 *   <li>nunca se usa {@code requeue=true} ni {@code basicNack}: la redelivery la produce el cierre de
 *       canal.</li>
 * </ul>
 */
public class QueryConsumerRecovery implements HandoffRecovery, AutoCloseable {

    /** Identificador estable del listener de la cola funcional de consultas. */
    public static final String QUERY_LISTENER_ID = "queryFunctionalListener";

    private static final Logger log = LoggerFactory.getLogger(QueryConsumerRecovery.class);

    private enum Estado { IDLE, STOPPING, BACKOFF, STARTING, CLOSED }

    private final Object lock = new Object();
    private final RabbitListenerEndpointRegistry registry;
    private final Duration backoff;
    private final List<String> listenerIds;
    private final ScheduledExecutorService executor;

    // Todo el estado vive bajo lock. Las operaciones de container se ejecutan fuera del lock.
    private Estado estado = Estado.IDLE;
    private boolean pendiente;
    private long generacion;

    public QueryConsumerRecovery(RabbitListenerEndpointRegistry registry, Duration backoff) {
        this(registry, backoff, List.of(QUERY_LISTENER_ID), defaultExecutor());
    }

    /**
     * Variante para escenarios con varios listeners o con un executor propio, usada por pruebas y por
     * servicios que agrupen mas de un consumidor de consultas.
     *
     * @param listenerIds identificadores estables de los listeners a recuperar.
     */
    public QueryConsumerRecovery(RabbitListenerEndpointRegistry registry, Duration backoff,
            List<String> listenerIds, ScheduledExecutorService executor) {
        if (registry == null) throw new IllegalArgumentException("registro de listeners requerido");
        if (backoff == null || backoff.isNegative()) throw new IllegalArgumentException("backoff no puede ser negativo");
        if (listenerIds == null || listenerIds.isEmpty())
            throw new IllegalArgumentException("se requiere al menos un listener a recuperar");
        this.registry = registry;
        this.backoff = backoff;
        this.listenerIds = List.copyOf(new LinkedHashSet<>(listenerIds));
        this.executor = executor;
    }

    private static ScheduledExecutorService defaultExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            var hilo = new Thread(r, "p360-query-recovery");
            hilo.setDaemon(true);
            return hilo;
        });
    }

    /** Solicita recuperar los listeners. Nunca lanza: se invoca desde el hilo del listener. */
    @Override
    public void sinConfirmar(String messageId, String correlationId, String destino, Exception causa) {
        log.error("Handoff no confirmado hacia {} messageId={} correlationId={}; se recupera el consumidor: {}",
                destino, messageId, correlationId, causa == null ? "sin causa" : causa.getMessage());
        solicitarRecuperacion();
    }

    /** Solicita un ciclo de recuperacion. Idempotente mientras haya uno en curso. */
    public void solicitarRecuperacion() {
        synchronized (lock) {
            if (estado == Estado.CLOSED) return;
            if (estado != Estado.IDLE) {
                pendiente = true;
                return;
            }
            iniciarCiclo();
        }
    }

    /**
     * Verdadero mientras haya un ciclo en curso o una solicitud acumulada.
     *
     * <p>El estado {@code CLOSED} no cuenta como recuperacion en curso: es un cierre terminal.
     */
    public boolean enRecuperacion() {
        synchronized (lock) {
            return (estado != Estado.IDLE && estado != Estado.CLOSED) || pendiente;
        }
    }

    private void iniciarCiclo() {
        pendiente = false;
        estado = Estado.STOPPING;
        long token = ++generacion;
        ejecutar(() -> detener(token));
    }

    /**
     * Detiene los listeners. Se ejecuta siempre en el executor, nunca en el hilo del listener ni con
     * el lock tomado.
     *
     * <p>Antes de aceptar el stop se avanza la generacion y se pasa el token nuevo al callback: asi el
     * callback de una detencion que si ocurrio no se confunde con el camino de error, y un callback
     * tardio de un intento anterior queda invalidado.
     */
    private void detener(long token) {
        synchronized (lock) {
            if (!vigente(token, Estado.STOPPING)) return;
        }
        for (String listenerId : listenerIds) {
            MessageListenerContainer container = registry.getListenerContainer(listenerId);
            if (container == null) {
                log.error("Recuperacion: listener {} no registrado", listenerId);
                reintentarDetencion(token);
                return;
            }
            long tokenDeEstaDetencion;
            synchronized (lock) {
                if (!vigente(token, Estado.STOPPING)) return;
                tokenDeEstaDetencion = ++generacion;
            }
            try {
                // El callback se reencola en el executor para no ejecutar trabajo bajo el lock.
                container.stop(() -> ejecutar(() -> detenido(tokenDeEstaDetencion, container)));
            } catch (RuntimeException fallo) {
                log.error("Recuperacion: no se pudo detener {} ({})", listenerId, fallo.getClass().getSimpleName());
                reintentarDetencion(tokenDeEstaDetencion);
                return;
            }
        }
    }

    private void detenido(long token, MessageListenerContainer container) {
        synchronized (lock) {
            // Los callbacks tardios de un ciclo anterior no reinician nada.
            if (!vigente(token, Estado.STOPPING)) return;
            estado = Estado.BACKOFF;
            ejecutarTrasBackoff(() -> reiniciar(token, container));
        }
    }

    /** Reinicia el listener. Si el arranque falla, vuelve a detener con backoff: nunca en caliente. */
    private void reiniciar(long token, MessageListenerContainer container) {
        synchronized (lock) {
            if (!vigente(token, Estado.BACKOFF)) return;
            estado = Estado.STARTING;
        }
        try {
            container.start();
        } catch (RuntimeException fallo) {
            log.error("Recuperacion: no se pudo reiniciar el listener ({})", fallo.getClass().getSimpleName());
            synchronized (lock) {
                if (vigente(token, Estado.STARTING)) {
                    // start() pudo dejar un consumidor a medias: cerrarlo antes de reintentar.
                    estado = Estado.STOPPING;
                    long siguiente = ++generacion;
                    ejecutarTrasBackoff(() -> detener(siguiente));
                    return;
                }
            }
            detenerContainerSeguro(container);
            return;
        }
        boolean cerrado;
        synchronized (lock) {
            cerrado = estado == Estado.CLOSED;
            if (!cerrado) {
                if (pendiente) iniciarCiclo();
                else estado = Estado.IDLE;
            }
        }
        if (cerrado) {
            // close() pudo admitir un arranque en vuelo: un consumidor vivo no debe sobrevivir al cierre.
            detenerContainerSeguro(container);
        }
    }

    /**
     * Reintenta la detencion cuando no puede completarse: no deja el consumidor sin recuperar ni gira
     * en vacio. Invalida el token del intento fallido para que un callback tardio no reinicie nada.
     */
    private void reintentarDetencion(long token) {
        synchronized (lock) {
            if (!vigente(token, Estado.STOPPING)) return;
            long siguiente = ++generacion;
            log.warn("Recuperacion: se reintenta el ciclo en {} ms", backoff.toMillis());
            ejecutarTrasBackoff(() -> detener(siguiente));
        }
    }

    private boolean vigente(long token, Estado esperado) {
        return generacion == token && estado == esperado;
    }

    private void ejecutar(Runnable tarea) {
        try {
            executor.execute(tarea);
        } catch (java.util.concurrent.RejectedExecutionException cerrado) {
            log.error("Recuperacion no admitida: el executor esta cerrado");
        }
    }

    private void ejecutarTrasBackoff(Runnable tarea) {
        long espera = Math.max(0, backoff.toMillis());
        try {
            executor.schedule(tarea, espera, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException cerrado) {
            log.error("Recuperacion no admitida: el executor esta cerrado");
        }
    }

    private void detenerContainerSeguro(MessageListenerContainer container) {
        try {
            container.stop();
        } catch (RuntimeException fallo) {
            log.error("Recuperacion: no se pudo cerrar el listener ({})", fallo.getClass().getSimpleName());
        }
    }

    /** Antes de que Spring detenga los lifecycle beans: el cierre debe ser terminal. */
    @EventListener(ContextClosedEvent.class)
    public void aplicacionCerrando() {
        close();
    }

    /** Terminal: un apagado operativo debe llamar a {@code close()} antes de {@code registry.stop()}. */
    @Override
    public void close() {
        synchronized (lock) {
            if (estado == Estado.CLOSED) return;
            estado = Estado.CLOSED;
            pendiente = false;
            ++generacion;
            executor.shutdownNow();
        }
    }
}
