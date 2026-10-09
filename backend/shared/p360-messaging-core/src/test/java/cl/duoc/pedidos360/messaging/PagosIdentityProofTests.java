package cl.duoc.pedidos360.messaging;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.fixture.*;
import cl.duoc.pedidos360.messaging.identity.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PagosIdentityProofTests {
    final Instant now = Instant.parse("2026-10-08T12:00:00Z");
    final UUID tenant = UUID.randomUUID(), oid = UUID.randomUUID(), usuarios = UUID.randomUUID();
    IdentityProof proof() { return new IdentityProof(tenant, oid, 10, now, now, now.plusSeconds(4),
            now.plusSeconds(5), usuarios, UUID.randomUUID()); }
    ActorContext actor(UUID t, UUID o, Instant exp) { return new ActorContext(t,o,Set.of("CLIENTE"),
            Set.of("access_as_user"),now,exp,IdentityProof.AUDIENCE,FixtureActorKeys.ID); }
    RequestEnvelope request(Instant r) { return RequestEnvelope.crear(UUID.randomUUID(),IdentityProof.OPERATION,
            JsonMapper.builder().build().createObjectNode(),"verified-elsewhere",now,r); }
    IdentityProofVerifier verifier(Instant time) { return new IdentityProofVerifier(FixtureIdentityKeys.keys(),
            Clock.fixed(time,ZoneOffset.UTC),Duration.ofMillis(250)); }
    @Test void signedOriginalDeadlineAndUsuariosIdDifferFromPagosEnvelope() {
        var p = proof(); var r = request(now.plusMillis(3750));
        assertThat(verifier(now).verifyForPagos(FixtureIdentityKeys.sign(p),actor(tenant,oid,r.expiresAt()),r)).isEqualTo(p);
        assertThat(r.messageId()).isNotEqualTo(p.usuariosRequestId());
        assertThat(r.expiresAt()).isBefore(p.deadlineOriginal());
        assertThatThrownBy(() -> verifier(now).verify(FixtureIdentityKeys.sign(p),tenant,oid,r.messageId(),r.expiresAt()))
                .extracting("reason").isEqualTo(IdentityProofException.Reason.VINCULO);
    }
    @ParameterizedTest @ValueSource(strings={"tenant","oid","R","actor"})
    void rejectsMismatchedIdentityOrTemporalRelationship(String change) {
        var r = request(now.plusMillis(change.equals("R") ? 3751 : 3750));
        var a = actor(change.equals("tenant") ? UUID.randomUUID() : tenant,
                change.equals("oid") ? UUID.randomUUID() : oid,
                change.equals("actor") ? now.plusMillis(3751) : r.expiresAt());
        assertThatThrownBy(() -> verifier(now).verifyForPagos(FixtureIdentityKeys.sign(proof()),a,r))
                .isInstanceOf(IdentityProofException.class);
    }
    @ParameterizedTest @ValueSource(longs={3749,3750,4000,5000})
    void exactConservativeBoundary(long elapsed) {
        var r = request(now.plusMillis(3750)); var p = proof();
        if (elapsed == 3749) assertThat(verifier(now.plusMillis(elapsed)).verifyForPagos(FixtureIdentityKeys.sign(p),actor(tenant,oid,r.expiresAt()),r)).isEqualTo(p);
        else assertThatThrownBy(() -> verifier(now.plusMillis(elapsed)).verifyForPagos(FixtureIdentityKeys.sign(p),actor(tenant,oid,r.expiresAt()),r))
                .extracting("reason").isEqualTo(IdentityProofException.Reason.VENCIDA);
    }
}
