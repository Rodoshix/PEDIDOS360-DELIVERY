package cl.duoc.pedidos360.pedidos.messaging;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/** Serial recovery cycles; requests received during stop/start are never discarded. */
public class ConfirmacionConsumerRecovery implements AutoCloseable {
    public static final String LISTENER_ID = "pedidoConfirmacionListener";
    private enum State { IDLE, STOPPING, BACKOFF, STARTING, CLOSED }

    private final Object lock = new Object();
    private final RabbitListenerEndpointRegistry registry;
    private final ConfirmacionReliabilityProperties properties;
    private final ScheduledExecutorService executor;
    // All state is guarded by lock. Container operations run outside it so a listener
    // can request recovery while start() is still waiting for its consumer thread.
    private State state = State.IDLE;
    private boolean pending;
    private long generation;

    public ConfirmacionConsumerRecovery(RabbitListenerEndpointRegistry registry,
            ConfirmacionReliabilityProperties properties) {
        this(registry, properties, Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "confirmacion-recovery");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ConfirmacionConsumerRecovery(RabbitListenerEndpointRegistry registry,
            ConfirmacionReliabilityProperties properties, ScheduledExecutorService executor) {
        this.registry = registry;
        this.properties = properties;
        this.executor = executor;
    }

    public void recover() {
        synchronized (lock) {
            if (state == State.CLOSED) return;
            if (state != State.IDLE) {
                pending = true;
                return;
            }
            beginCycle();
        }
    }

    private void beginCycle() {
        pending = false;
        state = State.STOPPING;
        long token = ++generation;
        executor.execute(() -> stop(token));
    }

    private void stop(long token) {
        synchronized (lock) {
            if (!isCurrent(token, State.STOPPING)) return;
        }
        var container = registry.getListenerContainer(LISTENER_ID);
        try {
            if (container == null) throw new IllegalStateException("Confirmation listener not registered");
            container.stop(() -> stopped(token, container));
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).error("Recovery stop failed class={}", failure.getClass().getSimpleName());
            synchronized (lock) {
                if (isCurrent(token, State.STOPPING)) {
                    long next = ++generation; // A late callback from the failed stop cannot restart us.
                    executor.schedule(() -> stop(next), properties.recoveryBackoff().toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void stopped(long token, MessageListenerContainer container) {
        synchronized (lock) {
            // Ignore late/duplicate callbacks from a previous cycle, including shutdown.
            if (!isCurrent(token, State.STOPPING)) return;
            state = State.BACKOFF;
            executor.schedule(() -> restart(token, container), properties.recoveryBackoff().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void restart(long token, MessageListenerContainer container) {
        synchronized (lock) {
            if (!isCurrent(token, State.BACKOFF)) return;
            state = State.STARTING;
        }
        try {
            container.start();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).error("Recovery restart failed class={}", failure.getClass().getSimpleName());
            synchronized (lock) {
                if (isCurrent(token, State.STARTING)) {
                    // start() may have partially started a consumer. Close it before
                    // another attempt; stop callback enforces backoff for each restart.
                    state = State.STOPPING;
                    long next = ++generation;
                    executor.execute(() -> stop(next));
                    return;
                }
            }
            container.stop();
            return;
        }
        synchronized (lock) {
            if (state != State.CLOSED) {
                if (pending) beginCycle();
                else state = State.IDLE;
                return;
            }
        }
        // close() can race with an already admitted start(). It cannot schedule
        // further starts, and an in-flight start must not leave a running consumer.
        container.stop();
    }

    private boolean isCurrent(long token, State expected) {
        return generation == token && state == expected;
    }

    @EventListener(ContextClosedEvent.class)
    public void applicationClosing() {
        close(); // Before Spring stops lifecycle beans, not only at bean destruction.
    }

    /** Terminal: intentional operational shutdown must call close() before registry.stop(). */
    @Override public void close() {
        synchronized (lock) {
            if (state == State.CLOSED) return;
            state = State.CLOSED;
            pending = false;
            ++generation;
            executor.shutdownNow();
        }
    }
}
