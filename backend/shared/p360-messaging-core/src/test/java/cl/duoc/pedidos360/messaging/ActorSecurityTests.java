package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import cl.duoc.pedidos360.messaging.actor.*;
import cl.duoc.pedidos360.messaging.fixture.FixtureActorKeys;
import com.nimbusds.jose.jwk.JWKSet;
import tools.jackson.databind.json.JsonMapper;

class ActorSecurityTests {
    final Instant now = Instant.parse("2026-10-08T10:00:00Z");
    final String tenant = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    final String audience = "p360.usuarios.consultas.q";
    final Instant deadline = now.plusSeconds(5);
    final JsonMapper json = JsonMapper.builder().build();
    ActorContext actor() { return new ActorContext(UUID.fromString(tenant), UUID.randomUUID(), Set.of("CLIENTE"),
            Set.of("access_as_user"), now, now.plusSeconds(4), audience, FixtureActorKeys.ID); }
    ActorContextSigner signer(SigningKeyProvider provider) {
        return new ActorContextSigner(provider, Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(5));
    }
    String token() { return signer(FixtureActorKeys.provider()).emitir(actor(), deadline); }
    ActorContext verify(String token) { return signer(FixtureActorKeys.provider()).verificar(token, tenant, List.of(audience), deadline); }
    String decoded(String token, int segment) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[segment]), StandardCharsets.UTF_8);
    }
    String replaceHeader(String token, String header) {
        var parts = token.split("\\.");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + parts[1] + "." + parts[2];
    }
    MockEnvironment service() { return new MockEnvironment().withProperty(QueryMessagingConfiguration.PUBLICAS_PROPERTY,
            FixtureActorKeys.publicJwks()); }

    @Test void publicOnlyConsumerVerifiesButCannotIssueAndKidIsNotAnIdentityClaim() {
        var provider = QueryMessagingConfiguration.proveedorDeClave(service());
        assertThat(provider.claveParaFirmar()).isEmpty();
        assertThat(provider.clavePorId(FixtureActorKeys.ID).orElseThrow().material().isPrivate()).isFalse();
        assertThat(signer(provider).verificar(token(), tenant, List.of(audience), deadline).tenantId().toString()).isEqualTo(tenant);
        assertThatThrownBy(() -> signer(provider).emitir(actor(), deadline)).isInstanceOf(ActorContextException.class);
        assertThat(json.readTree(decoded(token(), 0)).propertyNames()).containsExactlyInAnyOrder("alg", "typ", "kid");
        assertThat(json.readTree(decoded(token(), 1)).has("keyId")).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"none", "HS256", "RS256", "ES384", "ES256K"})
    void wrongAlgorithmsAreRejected(String alg) {
        var token=token(); var header=(tools.jackson.databind.node.ObjectNode)json.readTree(decoded(token,0));
        header.put("alg",alg);
        assertThatThrownBy(() -> verify(replaceHeader(token,json.writeValueAsString(header)))).isInstanceOf(ActorContextException.class);
    }

    @ParameterizedTest @ValueSource(strings={"jku", "jwk", "x5u", "crit", "b64", "extra"})
    void allUnexpectedHeadersIncludingRemoteOrEmbeddedKeysAreRejected(String field) {
        var token=token(); var header=(tools.jackson.databind.node.ObjectNode)json.readTree(decoded(token,0));
        header.put(field,"unexpected");
        assertThatThrownBy(() -> verify(replaceHeader(token,json.writeValueAsString(header)))).isInstanceOf(ActorContextException.class);
    }

    @Test void unknownKidWrongPublicKeyAndRetiredKeysAreRejected() {
        var token=token(); var header=(tools.jackson.databind.node.ObjectNode)json.readTree(decoded(token,0));
        header.put("kid",UUID.randomUUID().toString());
        assertThatThrownBy(() -> verify(replaceHeader(token,json.writeValueAsString(header)))).isInstanceOf(ActorContextException.class)
                .extracting("reason").isEqualTo(ActorContextException.Reason.CLAVE_DESCONOCIDA);
        var wrong=FixtureActorKeys.generate(FixtureActorKeys.ID).toPublicJWK();
        var provider=QueryMessagingConfiguration.proveedorDeClave(service().withProperty(QueryMessagingConfiguration.PUBLICAS_PROPERTY,
                new JWKSet(wrong).toString()));
        assertThatThrownBy(() -> signer(provider).verificar(token,tenant,List.of(audience),deadline))
                .isInstanceOf(ActorContextException.class).extracting("reason").isEqualTo(ActorContextException.Reason.FIRMA_INVALIDA);
        UUID secondId=UUID.randomUUID(); var second=FixtureActorKeys.generate(secondId).toPublicJWK();
        var rotating=service().withProperty(QueryMessagingConfiguration.PUBLICAS_PROPERTY,
                new JWKSet(List.of(FixtureActorKeys.KEY.toPublicJWK(),second)).toString());
        assertThat(QueryMessagingConfiguration.proveedorDeClave(rotating).clavePorId(secondId)).isPresent();
        rotating.withProperty(QueryMessagingConfiguration.PUBLICAS_PROPERTY,new JWKSet(second).toString());
        assertThatThrownBy(() -> signer(QueryMessagingConfiguration.proveedorDeClave(rotating))
                .verificar(token,tenant,List.of(audience),deadline)).isInstanceOf(ActorContextException.class);
    }

    @ParameterizedTest @ValueSource(strings={"extra", "keyId", "numericSubject", "rolesType", "duplicateRole", "tooManyRoles", "future", "lifetime", "missing", "duplicateJson"})
    void invalidClaimsAreRejectedEvenWithValidSignature(String mutation) {
        var token=token(); var claims=(tools.jackson.databind.node.ObjectNode)json.readTree(decoded(token,1));
        switch(mutation) {
            case "extra", "keyId" -> claims.put(mutation,"unexpected");
            case "numericSubject" -> claims.put("sujetoId",123);
            case "rolesType" -> claims.put("roles","CLIENTE");
            case "duplicateRole" -> claims.set("roles",json.createArrayNode().add("CLIENTE").add("CLIENTE"));
            case "tooManyRoles" -> { var arr=json.createArrayNode(); for(int i=0;i<65;i++) arr.add("ROLE"+i); claims.set("roles",arr); }
            case "future" -> { claims.put("emitidoEn",now.plusSeconds(6).toString()); claims.put("expiraEn",now.plusSeconds(7).toString()); }
            case "lifetime" -> claims.put("emitidoEn",now.minusSeconds(301).toString());
            case "missing" -> claims.remove("scopes");
        }
        String payload=json.writeValueAsString(claims);
        if(mutation.equals("duplicateJson")) payload=payload.substring(0,payload.length()-1)+",\"v\":\"p360act2\"}";
        String signed=FixtureActorKeys.signRaw(decoded(token,0),payload);
        assertThatThrownBy(() -> verify(signed)).isInstanceOf(ActorContextException.class);
    }

    @Test void duplicateHeaderMalformedSegmentsAndLegacyHmacAreRejected() {
        String token=token(); String header=decoded(token,0);
        String duplicate=header.substring(0,header.length()-1)+",\"alg\":\"ES256\"}";
        for(String invalid: List.of(replaceHeader(token,duplicate), "p360act1.e30.AAAA", token+".extra",
                replaceHeader(token,"null"), replaceHeader(token,"[]"), token.split("\\.")[0]+".!.AAAA"))
            assertThatThrownBy(() -> verify(invalid)).isInstanceOf(ActorContextException.class);
    }

    @Test void payloadTamperingWithoutResigningIsRejected() {
        String token=token(); var parts=token.split("\\.");
        var claims=(tools.jackson.databind.node.ObjectNode)json.readTree(decoded(token,1));
        claims.put("sujetoId",UUID.randomUUID().toString());
        String tampered=parts[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims))+"."+parts[2];
        assertThatThrownBy(() -> verify(tampered)).isInstanceOf(ActorContextException.class)
                .extracting("reason").isEqualTo(ActorContextException.Reason.FIRMA_INVALIDA);
    }

    @Test void validRedeliveryAllowedUntilExpiryButCrossAudienceAndExpiredReplayRejected() {
        String token=token(); assertThat(verify(token)).isEqualTo(verify(token));
        assertThatThrownBy(() -> signer(FixtureActorKeys.provider()).verificar(token,tenant,List.of("other"),deadline))
                .isInstanceOf(ActorContextException.class);
        var late=new ActorContextSigner(FixtureActorKeys.provider(),Clock.fixed(now.plusSeconds(4),ZoneOffset.UTC),Duration.ZERO);
        assertThatThrownBy(() -> late.verificar(token,tenant,List.of(audience),deadline)).isInstanceOf(ActorContextException.class)
                .extracting("reason").isEqualTo(ActorContextException.Reason.PLAZO_VENCIDO);
    }

    @Test void activeMissingKeysFailsClosedAndDisabledNeedsNoKeys() {
        assertThatThrownBy(() -> QueryMessagingConfiguration.proveedorDeClave(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
        // An old HMAC secret cannot satisfy the asymmetric configuration.
        assertThatThrownBy(() -> QueryMessagingConfiguration.proveedorDeClave(new MockEnvironment()
                .withProperty("pedidos360.messaging.actor.secreto","legacy-fixture-value-32-chars-long")))
                .isInstanceOf(IllegalStateException.class);
        new ApplicationContextRunner().withUserConfiguration(QueryMessagingConfiguration.class)
                .withPropertyValues("pedidos360.messaging.relay-mode=DISABLED")
                .run(ctx -> { assertThat(ctx).hasNotFailed(); assertThat(ctx).doesNotHaveBean(ActorContextSigner.class); });
        new ApplicationContextRunner().withUserConfiguration(QueryMessagingConfiguration.class)
                .withBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class,
                        () -> org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class))
                .withPropertyValues("pedidos360.messaging.relay-mode=ACTIVE", "pedidos360.messaging.actor.emisor="+tenant)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test void privateKeysAreRejectedInServiceAndBffRequiresMatchingPair() {
        assertThatThrownBy(() -> QueryMessagingConfiguration.proveedorDeClave(service()
                .withProperty(QueryMessagingConfiguration.PRIVADA_PROPERTY,FixtureActorKeys.privateJwk())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> QueryMessagingConfiguration.proveedorDeClave(service()
                .withProperty(QueryMessagingConfiguration.PUBLICAS_PROPERTY,new JWKSet(FixtureActorKeys.KEY).toString(false))))
                .isInstanceOf(IllegalStateException.class);
        var bff=service().withProperty("pedidos360.messaging.role","BFF")
                .withProperty(QueryMessagingConfiguration.CLAVE_ID_PROPERTY,FixtureActorKeys.ID.toString());
        assertThatThrownBy(() -> QueryMessagingConfiguration.proveedorDeClave(bff)).isInstanceOf(IllegalStateException.class);
        bff.withProperty(QueryMessagingConfiguration.PRIVADA_PROPERTY,FixtureActorKeys.privateJwk());
        assertThat(QueryMessagingConfiguration.proveedorDeClave(bff).claveParaFirmar()).isPresent();
        bff.withProperty(QueryMessagingConfiguration.PRIVADA_PROPERTY,FixtureActorKeys.generate(FixtureActorKeys.ID).toJSONString());
        assertThatThrownBy(() -> QueryMessagingConfiguration.proveedorDeClave(bff)).isInstanceOf(IllegalStateException.class);
    }

    @Test void malformedDuplicateRevokedAndWrongCurveConfigurationFailsClosed() throws Exception {
        var revoked=FixtureActorKeys.KEY.toPublicJWK().toRevokedJWK(
                new com.nimbusds.jose.jwk.KeyRevocation(new java.util.Date(), null));
        var p384=new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_384)
                .keyID(FixtureActorKeys.ID.toString()).generate().toPublicJWK();
        for(String invalid: List.of("{}", "{broken", "{\"keys\":[]}", new JWKSet(revoked).toString(), new JWKSet(p384).toString(),
                new JWKSet(List.of(FixtureActorKeys.KEY.toPublicJWK(),FixtureActorKeys.KEY.toPublicJWK())).toString()))
            assertThatThrownBy(() -> QueryMessagingConfiguration.proveedorDeClave(service()
                    .withProperty(QueryMessagingConfiguration.PUBLICAS_PROPERTY,invalid))).isInstanceOf(IllegalStateException.class);
    }
}
