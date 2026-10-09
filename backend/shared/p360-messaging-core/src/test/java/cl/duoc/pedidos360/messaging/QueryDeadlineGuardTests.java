package cl.duoc.pedidos360.messaging;

import cl.duoc.pedidos360.messaging.relay.QueryDeadlineGuard;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QueryDeadlineGuardTests {
    static final Instant START = Instant.parse("2026-10-08T12:00:00Z");
    static final class MutableClock extends Clock {
        Instant now = START;
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    @Test void backwardClockCannotExtendBudgetOrReactivateIt() {
        var clock = new MutableClock(); var ticks = new AtomicLong();
        var guard = new QueryDeadlineGuard(START.plusSeconds(5), clock, ticks::get);
        ticks.set(4_000_000_000L); clock.now = START.minusSeconds(20);
        assertThat(guard.remainingNanos()).isEqualTo(1_000_000_000L);
        ticks.set(5_000_000_000L);
        assertThatThrownBy(guard::remainingNanos).isInstanceOf(QueryDeadlineGuard.Expired.class);
        ticks.set(0); clock.now = START;
        assertThatThrownBy(guard::remainingNanos).isInstanceOf(QueryDeadlineGuard.Expired.class);
    }
    @Test void narrowedDeadlineUsesOriginalTicksAndCannotBeExpanded() {
        var clock = new MutableClock(); var ticks = new AtomicLong(2);
        var guard = new QueryDeadlineGuard(START.plusSeconds(5), clock, ticks::get);
        ticks.addAndGet(1_000_000_000L);
        guard.narrow(START.plusMillis(3750));
        assertThat(guard.remainingNanos()).isEqualTo(2_750_000_000L);
        guard.narrow(START.plusSeconds(8));
        assertThat(guard.remainingNanos()).isEqualTo(2_750_000_000L);
        clock.now = START.plusMillis(3750);
        assertThat(guard.exhausted()).isTrue();
        clock.now = START.minusSeconds(10);
        assertThat(java.util.stream.IntStream.range(0, 32).parallel().mapToObj(i -> guard.exhausted()))
                .allMatch(Boolean::booleanValue);
    }
}
