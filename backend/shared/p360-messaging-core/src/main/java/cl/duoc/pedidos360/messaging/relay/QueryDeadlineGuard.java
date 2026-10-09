package cl.duoc.pedidos360.messaging.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/** Delivery-local guard for an existing absolute deadline; it never allocates a fresh operation budget. */
public final class QueryDeadlineGuard {
    private final Clock clock;
    private final LongSupplier ticks;
    private final Instant received;
    private final long started;
    private Instant limit;
    private boolean exhausted;

    public QueryDeadlineGuard(Instant deadline, Clock clock, LongSupplier ticks) {
        this(deadline, clock, ticks, ticks.getAsLong(), clock.instant());
    }

    public QueryDeadlineGuard(Instant deadline, Clock clock, LongSupplier ticks, long started, Instant received) {
        this.clock = java.util.Objects.requireNonNull(clock);
        this.ticks = java.util.Objects.requireNonNull(ticks);
        this.started = started;
        this.received = java.util.Objects.requireNonNull(received);
        this.limit = java.util.Objects.requireNonNull(deadline);
    }

    public synchronized void narrow(Instant deadline) {
        if (deadline.isBefore(limit)) limit = deadline;
        remainingNanos();
    }

    public synchronized long remainingNanos() {
        if (exhausted) throw new Expired();
        long elapsed = Math.max(0, ticks.getAsLong() - started);
        long remaining = Math.min(Duration.between(clock.instant(), limit).toNanos(),
                Duration.between(received, limit).toNanos() - elapsed);
        if (remaining <= 0) { exhausted = true; throw new Expired(); }
        return remaining;
    }

    public synchronized boolean exhausted() {
        try { remainingNanos(); return false; }
        catch (Expired ignored) { return true; }
    }

    public synchronized void invalidate() { exhausted = true; }

    public static final class Expired extends org.springframework.security.access.AccessDeniedException {
        public Expired() { super("consulta sin presupuesto funcional"); }
    }
}
