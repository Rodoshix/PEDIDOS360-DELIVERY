package cl.duoc.pedidos360.bff.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.LongSupplier;
import cl.duoc.pedidos360.messaging.identity.IdentityProofCodec;
import cl.duoc.pedidos360.messaging.relay.QueryTimeoutException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Un presupuesto por operación, atado a su identidad. Ningún paso/retry reinicia sus relojes. */
public final class QueryOperationBudget {
    private final Instant originalDeadline;
    private final UUID tenant;
    private final UUID oid;
    private final Clock clock;
    private final LongSupplier ticks;
    private final long started;
    private final long initialNanos;
    private boolean usuariosSolicitado;
    private QueryOperationBudget(Instant deadline, UUID tenant, UUID oid, Clock clock, LongSupplier ticks, long started, long nanos) {
        this.originalDeadline = deadline; this.tenant = tenant; this.oid = oid;
        this.clock = clock; this.ticks = ticks; this.started = started; this.initialNanos = nanos;
    }
    public static QueryOperationBudget start(JwtAuthenticationToken token, Duration maximum, Clock clock, LongSupplier ticks) {
        long started = ticks.getAsLong();
        Instant now = clock.instant();
        if (token == null || !token.isAuthenticated() || token.getToken().getExpiresAt() == null
                || !token.getToken().getExpiresAt().isAfter(now))
            throw new org.springframework.security.access.AccessDeniedException("JWT autenticado vigente requerido");
        if (maximum == null || maximum.isNegative() || maximum.isZero() || maximum.compareTo(Duration.ofSeconds(5)) > 0)
            throw new IllegalArgumentException("presupuesto máximo de consulta debe estar entre cero y cinco segundos");
        Instant configured = now.plus(maximum);
        Instant jwtExpiry = token.getToken().getExpiresAt();
        Instant deadline = IdentityProofCodec.millis(configured.isBefore(jwtExpiry) ? configured : jwtExpiry);
        var result = new QueryOperationBudget(deadline, UUID.fromString(token.getToken().getClaimAsString("tid")),
                UUID.fromString(token.getToken().getClaimAsString("oid")), clock, ticks, started, Duration.between(now, deadline).toNanos());
        result.requireRemaining(deadline); return result;
    }
    public Instant originalDeadline() { return originalDeadline; }
    public UUID tenant() { return tenant; }
    public UUID oid() { return oid; }
    public Instant now() { return clock.instant(); }
    public long requireRemaining(Instant effectiveDeadline) {
        if (effectiveDeadline == null || effectiveDeadline.isAfter(originalDeadline)) throw new IllegalArgumentException("plazo no puede renovarse");
        long elapsed = Math.max(0, ticks.getAsLong() - started);
        long functional = initialNanos - elapsed;
        // También limita un subplazo con el reloj monotónico original, aunque el reloj de pared retroceda.
        long narrowed = functional - Duration.between(effectiveDeadline, originalDeadline).toNanos();
        long remaining = Math.min(narrowed, Duration.between(clock.instant(), effectiveDeadline).toNanos());
        if (remaining <= 0) throw new QueryTimeoutException("presupuesto global agotado"); return remaining;
    }
    public synchronized void claimUsuarios() {
        if (usuariosSolicitado) throw new IllegalStateException("Usuarios ya fue solicitado para esta operación; no se renueva la prueba");
        usuariosSolicitado = true;
    }
    public void requireIdentity(JwtAuthenticationToken token) {
        if (token == null || !token.isAuthenticated()
                || !tenant.toString().equals(token.getToken().getClaimAsString("tid"))
                || !oid.toString().equals(token.getToken().getClaimAsString("oid"))
                || token.getToken().getExpiresAt() == null || token.getToken().getExpiresAt().isBefore(originalDeadline))
            throw new org.springframework.security.access.AccessDeniedException("identidad no corresponde a la operación");
    }
    @Override public String toString() { return "QueryOperationBudget[redacted]"; }
}
