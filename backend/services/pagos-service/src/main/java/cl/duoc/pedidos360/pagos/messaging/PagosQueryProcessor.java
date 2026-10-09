package cl.duoc.pedidos360.pagos.messaging;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.identity.IdentityProofVerifier;
import cl.duoc.pedidos360.messaging.relay.*;
import cl.duoc.pedidos360.pagos.exception.*;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario;
import cl.duoc.pedidos360.pagos.service.PagoService;
import java.time.Clock;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only local adapter. No HTTP identity, remote identity lookup, or coordination side effects. */
public final class PagosQueryProcessor implements QueryProcessor {
    private final PagoService pagos;
    private final IdentityProofVerifier verifier;
    private final JsonMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PagosQueryProcessor(PagoService pagos, IdentityProofVerifier verifier, JsonMapper json,
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.pagos = pagos; this.verifier = verifier; this.json = json; this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        tx.setReadOnly(true);
    }

    @Override public JsonNode procesar(ActorContext actor, RequestEnvelope request) {
        return procesar(actor, request, new QueryDeadlineGuard(request.expiresAt(), Clock.systemUTC(), System::nanoTime));
    }

    @Override public JsonNode procesar(ActorContext actor, RequestEnvelope request, QueryDeadlineGuard guard) {
        guard.narrow(actor.expiraEn());
        var payload = PagoQueryRequest.read(request.payload());
        var proof = verifier.verifyForPagos(payload.pruebaIdentidad(), actor, request);
        guard.narrow(verifier.usableUntil(proof));
        var roles = actor.roles().stream().filter(r -> r.equals("CLIENTE") || r.equals("ADMIN"))
                .map(IdentidadUsuario.Rol::valueOf).collect(Collectors.toSet());
        if (!actor.tieneScope("access_as_user") || roles.isEmpty())
            throw QueryBusinessException.prohibido("No tienes permiso para esta operación.");
        var identidad = new IdentidadUsuario(proof.tenantId(), proof.usuarioId(), roles);
        try {
            var response = tx.execute(status -> {
                guard.remainingNanos();
                // Transaction-local milliseconds: floor, never round up or alter global DB timeouts.
                long millis = guard.remainingNanos() / 1_000_000;
                if (millis <= 0) { guard.invalidate(); throw new QueryDeadlineGuard.Expired(); }
                jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, millis + "ms");
                guard.remainingNanos(); // includes acquiring the connection and setting the limit
                var result = pagos.obtener(identidad, payload.pagoId());
                guard.remainingNanos();
                return result;
            });
            guard.remainingNanos();
            JsonNode projected = json.valueToTree(PagoQueryResponse.from(response));
            guard.remainingNanos();
            return projected;
        } catch (PagoNoEncontradoException missing) {
            guard.remainingNanos();
            throw QueryBusinessException.noEncontrado("Recurso no encontrado.");
        } catch (PagoException business) {
            guard.remainingNanos();
            throw switch (business.getStatus()) {
                case FORBIDDEN -> QueryBusinessException.prohibido("No tienes acceso a este pago.");
                case NOT_FOUND -> QueryBusinessException.noEncontrado("Recurso no encontrado.");
                default -> business;
            };
        } catch (RuntimeException failure) {
            guard.remainingNanos();
            throw failure;
        }
    }
}
