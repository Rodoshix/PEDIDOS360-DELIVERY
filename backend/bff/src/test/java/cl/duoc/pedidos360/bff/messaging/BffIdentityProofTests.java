package cl.duoc.pedidos360.bff.messaging;

import static org.assertj.core.api.Assertions.*;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.fixture.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.relay.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.json.JsonMapper;

class BffIdentityProofTests {
    final Instant now=Instant.parse("2026-10-08T10:00:00Z");
    final UUID tenant=UUID.randomUUID(),oid=UUID.randomUUID();
    final JsonMapper json=JsonMapper.builder().build();
    final AtomicLong nanos=new AtomicLong();
    JwtAuthenticationToken token(Instant expiry) { return new JwtAuthenticationToken(Jwt.withTokenValue("test-only")
            .header("alg","RS256").issuedAt(now.minusSeconds(1)).expiresAt(expiry).claim("tid",tenant.toString()).claim("oid",oid.toString()).build(),
            List.of(new SimpleGrantedAuthority("ROLE_CLIENTE"),new SimpleGrantedAuthority("SCOPE_access_as_user"))); }
    QueryOperationBudget budget(Clock clock) { return QueryOperationBudget.start(token(now.plusSeconds(10)),Duration.ofSeconds(5),clock,nanos::get); }
    RequestPlan plan(QueryOperationBudget b) { return new RequestPlan(RequestEnvelope.crear(UUID.randomUUID(),"usuario.consultar-actual.v1",json.createObjectNode(),"actor",now,b.originalDeadline()),UUID.randomUUID().toString()); }
    IdentityProof proof(RequestPlan p,QueryOperationBudget b) { return new IdentityProof(tenant,oid,42,now,now,now.plusSeconds(4),b.originalDeadline(),p.envelope().messageId(),UUID.randomUUID()); }
    BffIdentityProofValidator validator(Instant time) { return new BffIdentityProofValidator(new IdentityProofVerifier(FixtureIdentityKeys.keys(),Clock.fixed(time,ZoneOffset.UTC),Duration.ofMillis(250))); }
    tools.jackson.databind.node.ObjectNode profile(String signed) {
        return json.createObjectNode().put("id",42).put("nombre","Ana").put("apellido","Perez")
                .put("email","ana@example.test").putNull("telefono").put("activo",true)
                .put("creadoEn",now.toString()).put("actualizadoEn",now.plusNanos(123456789).toString())
                .put("pruebaIdentidad",signed);
    }
    @Test void validResponseYieldsLocalIdentityAndHttpProjectionWithoutProof() {
        var b=budget(Clock.fixed(now,ZoneOffset.UTC)); var p=plan(b); var proof=proof(p,b);
        var result=validator(now).validate(profile(FixtureIdentityKeys.sign(proof)),p,b);
        assertThat(result.perfil().has("pruebaIdentidad")).isFalse(); assertThat(result.prueba()).isEqualTo(proof);
    }
    @ParameterizedTest @ValueSource(strings={"tenant","oid","request","deadline","id","active","missing","signature"})
    void crossValidationRejectsSwappingEvenWithValidUsersSignature(String mutation) {
        var b=budget(Clock.fixed(now,ZoneOffset.UTC)); var p=plan(b); var proof=proof(p,b);
        var modified=new IdentityProof(mutation.equals("tenant")?UUID.randomUUID():tenant,mutation.equals("oid")?UUID.randomUUID():oid,42,now,now,now.plusSeconds(4),
                mutation.equals("deadline")?now.plusSeconds(6):b.originalDeadline(),mutation.equals("request")?UUID.randomUUID():p.envelope().messageId(),UUID.randomUUID());
        String signed=FixtureIdentityKeys.sign(modified);
        if(mutation.equals("signature")) signed=signed.substring(0,signed.lastIndexOf('.')+1)+IdentityProofCodec.encode(new byte[64]);
        var payload=profile(signed).put("id",mutation.equals("id")?999:42).put("activo",!mutation.equals("active"));
        if(mutation.equals("missing")) payload.remove("pruebaIdentidad");
        assertThatThrownBy(()->validator(now).validate(payload,p,b)).isInstanceOf(QueryUnavailableException.class);
    }
    @Test void authenticatedButExpiredProofIsFunctionalTimeout() {
        var b=budget(Clock.fixed(now,ZoneOffset.UTC)); var p=plan(b);
        var payload=profile(FixtureIdentityKeys.sign(proof(p,b)));
        assertThatThrownBy(()->validator(now.plusSeconds(4)).validate(payload,p,b)).isInstanceOf(QueryTimeoutException.class);
    }
    @Test void jwtNearExpiryClipsOriginalDeadlineAndRequiresValidExpiry() {
        var jwt=token(now.plusMillis(900)); var b=QueryOperationBudget.start(jwt,Duration.ofSeconds(5),Clock.fixed(now,ZoneOffset.UTC),nanos::get);
        assertThat(b.originalDeadline()).isEqualTo(now.plusMillis(900));
        var actorFactory=new BffActorContextFactory(FixtureActorKeys.signer(),new BffActorProperties(tenant.toString(),Duration.ofSeconds(4),Set.of("p360.usuarios.consultas.q")));
        String actor=actorFactory.emitir(jwt,"p360.usuarios.consultas.q",now,b.originalDeadline());
        var verified=new cl.duoc.pedidos360.messaging.actor.ActorContextSigner(FixtureActorKeys.provider(),Clock.fixed(now,ZoneOffset.UTC),Duration.ZERO)
                .verificar(actor,tenant.toString(),List.of("p360.usuarios.consultas.q"),b.originalDeadline());
        assertThat(verified.expiraEn()).isEqualTo(b.originalDeadline());
        assertThatThrownBy(()->QueryOperationBudget.start(token(now),Duration.ofSeconds(5),Clock.fixed(now,ZoneOffset.UTC),nanos::get)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void monotonicBudgetPreventsClockRollbackAndSubdeadlineRenewal() {
        var clock=new MutableClock(now); var b=budget(clock);
        nanos.set(Duration.ofSeconds(3).toNanos()); clock.now=now.minusSeconds(60);
        assertThat(b.requireRemaining(b.originalDeadline())).isEqualTo(Duration.ofSeconds(2).toNanos());
        assertThat(b.requireRemaining(now.plusSeconds(4))).isEqualTo(Duration.ofSeconds(1).toNanos());
        nanos.set(Duration.ofSeconds(4).toNanos());
        assertThatThrownBy(()->b.requireRemaining(now.plusSeconds(4))).isInstanceOf(QueryTimeoutException.class);
        nanos.set(Duration.ofSeconds(5).toNanos());
        assertThatThrownBy(()->b.requireRemaining(b.originalDeadline())).isInstanceOf(QueryTimeoutException.class);
        assertThatThrownBy(()->b.requireRemaining(now.plusSeconds(6))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void secondUsersQueryCannotRefreshAndWallClockJumpOnlyShortens() {
        var clock=new MutableClock(now); var b=budget(clock); b.claimUsuarios();
        assertThatThrownBy(b::claimUsuarios).isInstanceOf(IllegalStateException.class);
        clock.now=now.plusSeconds(5); assertThatThrownBy(()->b.requireRemaining(b.originalDeadline())).isInstanceOf(QueryTimeoutException.class);
    }
    @Test void conservativeMarginAlsoAppliesToMonotonicBudgetAfterClockRollback() {
        var clock=new MutableClock(now); var b=budget(clock); var p=plan(b);
        var payload=profile(FixtureIdentityKeys.sign(proof(p,b)));
        nanos.set(Duration.ofMillis(3750).toNanos()); clock.now=now.minusMillis(100);
        var verifier=new IdentityProofVerifier(FixtureIdentityKeys.keys(),clock,Duration.ofMillis(250));
        assertThatThrownBy(()->new BffIdentityProofValidator(verifier).validate(payload,p,b)).isInstanceOf(QueryTimeoutException.class);
    }
    @Test void publicOnlyConfigurationRejectsPrivateMissingAndSharedActorMaterial() {
        var env=new org.springframework.mock.env.MockEnvironment()
                .withProperty("pedidos360.messaging.actor.public-jwks",FixtureActorKeys.publicJwks())
                .withProperty("pedidos360.messaging.identity-proof.public-jwks",FixtureIdentityKeys.publicJwks());
        assertThat(new BffQueryConfiguration().bffIdentityProofValidator(env)).isNotNull();
        assertThatThrownBy(()->new BffQueryConfiguration().bffIdentityProofValidator(env.withProperty("pedidos360.messaging.identity-proof.private-jwk","untrusted")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new BffQueryConfiguration().bffIdentityProofValidator(new org.springframework.mock.env.MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(BffQueryConfiguration.class)
                .withPropertyValues("pedidos360.messaging.relay-mode=DISABLED","pedidos360.messaging.identity-proof.enabled=true")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(BffIdentityProofValidator.class));
    }
    static class MutableClock extends Clock {
        Instant now; MutableClock(Instant now) { this.now=now; }
        public java.time.ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(java.time.ZoneId zone){return this;} public Instant instant(){return now;}
    }
}
