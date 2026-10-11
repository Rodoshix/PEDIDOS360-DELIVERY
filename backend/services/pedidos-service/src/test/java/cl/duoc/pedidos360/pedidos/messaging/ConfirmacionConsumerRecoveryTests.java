package cl.duoc.pedidos360.pedidos.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfirmacionConsumerRecoveryTests {
    final RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
    final MessageListenerContainer container = mock(MessageListenerContainer.class);
    final ManualExecutor clock = new ManualExecutor();
    final ConfirmacionConsumerRecovery recovery = new ConfirmacionConsumerRecovery(registry,
        new ConfirmacionReliabilityProperties("retry", "dlx", "dlq", "failed", "q5", "q30", "q120",
            "k5", "k30", "k120", Duration.ofSeconds(5), true), clock.executor);

    ConfirmacionConsumerRecoveryTests() {
        when(registry.getListenerContainer(ConfirmacionConsumerRecovery.LISTENER_ID)).thenReturn(container);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(container).stop(any(Runnable.class));
    }

    @Test void failureDuranteStartSeConservaConBackoffYSinCiclosConcurrentes() throws Exception {
        var starts = new AtomicInteger();
        doAnswer(inv -> {
            if (starts.incrementAndGet() == 1) {
                // A different consumer thread finishes recover() BEFORE start() returns.
                var failure = new FutureTask<Void>(() -> { recovery.recover(); return null; });
                var listener = new Thread(failure);
                listener.start();
                failure.get(2, TimeUnit.SECONDS);
                listener.join(2000);
            }
            return null;
        }).when(container).start();
        recovery.recover(); clock.runReady();
        clock.advance(4999); verify(container, never()).start();
        clock.advance(1); verify(container).start(); verify(container, times(2)).stop(any(Runnable.class));
        clock.advance(4999); verify(container, times(1)).start();
        clock.advance(1); verify(container, times(2)).start();
        clock.advance(50000); verify(container, times(2)).start();
        assertThat(clock.tasks).isEmpty(); recovery.close();
    }

    @Test void solicitudesDuranteStopYBackoffSeAgrupanSinPerderlas() {
        var callbacks = new ArrayList<Runnable>();
        doAnswer(inv -> { callbacks.add(inv.getArgument(0)); return null; }).when(container).stop(any(Runnable.class));
        recovery.recover(); clock.runReady(); recovery.recover(); recovery.recover();
        callbacks.getFirst().run(); recovery.recover(); clock.advance(5000);
        verify(container).start(); verify(container, times(2)).stop(any(Runnable.class));
        callbacks.getFirst().run(); // Late callback from generation 1 must not restart generation 2.
        clock.advance(5000); verify(container, times(1)).start();
        callbacks.get(1).run(); clock.advance(5000); verify(container, times(2)).start();
        assertThat(clock.tasks).isEmpty(); recovery.close();
    }

    @Test void closeDuranteBackoffCancelaArranqueYCallbackTardio() {
        var callbacks = new ArrayList<Runnable>();
        doAnswer(inv -> { callbacks.add(inv.getArgument(0)); return null; }).when(container).stop(any(Runnable.class));
        recovery.recover(); clock.runReady(); callbacks.getFirst().run();
        recovery.close(); callbacks.getFirst().run(); recovery.recover(); clock.advance(50000);
        verify(container, never()).start(); assertThat(clock.tasks).isEmpty();
        verify(clock.executor).shutdownNow();
    }

    @Test void closeDuranteStartNoDejaConsumerActivoNiProgramaOtroRestart() {
        doAnswer(inv -> { recovery.recover(); recovery.close(); return null; }).when(container).start();
        recovery.recover(); clock.runReady(); clock.advance(5000); clock.advance(50000);
        verify(container).start(); verify(container).stop(); assertThat(clock.tasks).isEmpty();
    }

    @Test void shutdownDelContextoCierraRecoveryAntesDelLifecycleStop() {
        try(var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(ConfirmacionConsumerRecovery.class, () -> recovery);
            context.refresh();
            recovery.recover(); clock.runReady();
            // Actual ContextClosedEvent wiring, not a direct invocation of the handler.
        }
        clock.advance(50000);
        recovery.recover(); clock.runReady(); verify(container, never()).start();
        assertThat(clock.tasks).isEmpty();
    }

    @Test void fallosStartCierranConsumerParcialYReintentanConBackoff() {
        doThrow(new IllegalStateException()).doNothing().when(container).start();
        recovery.recover(); clock.runReady(); clock.advance(5000);
        verify(container).start(); verify(container, times(2)).stop(any(Runnable.class));
        clock.advance(4999); verify(container, times(1)).start();
        clock.advance(1); verify(container, times(2)).start(); recovery.close();
    }

    @Test void fallosStopReintentanConBackoffYCloseLosCancela() {
        var callbacks = new ArrayList<Runnable>();
        doAnswer(inv -> { callbacks.add(inv.getArgument(0)); throw new IllegalStateException(); }).when(container).stop(any(Runnable.class));
        recovery.recover(); clock.runReady(); clock.advance(4999);
        callbacks.getFirst().run(); // Old failed stop's callback must not schedule a start.
        verify(container).stop(any(Runnable.class));
        clock.advance(1); verify(container, times(2)).stop(any(Runnable.class));
        recovery.close(); clock.advance(50000); verify(container, never()).start();
    }

    /** Virtual time controls the exact interleaving; no probabilistic sleeps. */
    static class ManualExecutor {
        record Task(long due, long sequence, Runnable runnable) {}
        final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparingLong(Task::due).thenComparingLong(Task::sequence));
        long now, sequence;
        ManualExecutor() {
            doAnswer(inv -> { tasks.add(new Task(now, sequence++, inv.getArgument(0))); return null; }).when(executor).execute(any(Runnable.class));
            doAnswer(inv -> {
                tasks.add(new Task(now + inv.getArgument(2, TimeUnit.class).toMillis(inv.getArgument(1, Long.class)), sequence++, inv.getArgument(0)));
                return mock(ScheduledFuture.class);
            }).when(executor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
            when(executor.shutdownNow()).thenAnswer(inv -> { tasks.clear(); return List.of(); });
        }
        void advance(long millis) { now += millis; runReady(); }
        void runReady() {
            int executions = 0;
            while (!tasks.isEmpty() && tasks.peek().due() <= now) {
                assertThat(++executions).as("No hot loop in a single clock tick").isLessThan(20);
                tasks.remove().runnable().run();
            }
        }
    }
}
