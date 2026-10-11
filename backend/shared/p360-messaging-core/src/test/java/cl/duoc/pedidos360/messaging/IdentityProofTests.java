package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.*;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.fixture.*;
import com.nimbusds.jose.jwk.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class IdentityProofTests {
    final Instant now = Instant.parse("2026-10-08T10:00:00Z");
    final UUID tenant = UUID.randomUUID(), oid = UUID.randomUUID(), request = UUID.randomUUID();
    final JsonMapper json = JsonMapper.builder().build();
    IdentityProof proof() { return new IdentityProof(tenant,oid,Long.MAX_VALUE,now,now,now.plusSeconds(4),now.plusSeconds(5),request,UUID.randomUUID()); }
    IdentityProofVerifier verifier(Instant time, Duration margin) { return new IdentityProofVerifier(FixtureIdentityKeys.keys(),Clock.fixed(time,ZoneOffset.UTC),margin); }
    IdentityProof verify(String jws) { return verifier(now,Duration.ofMillis(250)).verify(jws,tenant,oid,request,now.plusSeconds(5)); }
    String encode(String raw) { return IdentityProofCodec.encode(raw.getBytes(StandardCharsets.UTF_8)); }
    String canonical(ObjectNode n) {
        var m = new TreeMap<String, tools.jackson.databind.JsonNode>(); n.properties().forEach(e -> m.put(e.getKey(),e.getValue()));
        return json.writeValueAsString(m);
    }
    ObjectNode claims() { return (ObjectNode)json.readTree(Base64.getUrlDecoder().decode(IdentityProofCodec.payload(proof()))); }
    String mutated(ObjectNode n) { return FixtureIdentityKeys.signRaw(IdentityProofCodec.header(FixtureIdentityKeys.ID),encode(canonical(n))); }
    @Test void publicOnlyVerifiesAndPreservesPositiveLongWithoutRolesOrPii() {
        var p=proof(); var jws=FixtureIdentityKeys.sign(p);
        assertThat(verify(jws)).isEqualTo(p);
        assertThat(jws.length()).isLessThan(4096);
        assertThat(claims().propertyNames()).doesNotContain("roles","scopes","email","pagoId","jwt");
        assertThat(FixtureIdentityKeys.keys().get(FixtureIdentityKeys.ID).material().isPrivate()).isFalse();
        assertThat(p.toString()).doesNotContain(tenant.toString());
    }
    @ParameterizedTest @ValueSource(strings={"none","HS256","HS384","RS256","ES384","ES256K"})
    void rejectsAlternateAlgorithms(String algorithm) {
        var h=json.createObjectNode().put("alg",algorithm).put("kid",FixtureIdentityKeys.ID.toString()).put("typ",IdentityProof.TYPE);
        assertThatThrownBy(()->verify(FixtureIdentityKeys.signRaw(encode(canonical(h)),IdentityProofCodec.payload(proof())))).isInstanceOf(IdentityProofException.class);
    }
    @ParameterizedTest @ValueSource(strings={"jwk","jku","x5u","crit","b64","extra"})
    void rejectsAdditionalProtectedHeaders(String field) {
        var h=json.createObjectNode().put("alg","ES256").put("kid",FixtureIdentityKeys.ID.toString()).put("typ",IdentityProof.TYPE).put(field,"untrusted");
        assertThatThrownBy(()->verify(FixtureIdentityKeys.signRaw(encode(canonical(h)),IdentityProofCodec.payload(proof())))).isInstanceOf(IdentityProofException.class);
    }
    @ParameterizedTest @ValueSource(strings={"extra","roles","missing","vString","vFloat","vOverflow","idString","idFloat","idZero","idOverflow","inactive","activeString","issuer","audience","operation","uuid","upperUuid","timeOffset","timePrecision","future","ordering","lifetime","deadline"})
    void rejectsInvalidClaimsWithCorrectSignature(String mutation) {
        var n=claims();
        switch(mutation) {
            case "extra","roles" -> n.put(mutation,"untrusted");
            case "missing" -> n.remove("jti");
            case "vString" -> n.put("v","1");
            case "vFloat" -> n.put("v",1.0);
            case "vOverflow" -> n.set("v",json.readTree("18446744073709551617"));
            case "idString" -> n.put("usuarioId","1");
            case "idFloat" -> n.put("usuarioId",1.0);
            case "idZero" -> n.put("usuarioId",0);
            case "idOverflow" -> n.set("usuarioId",json.readTree("9223372036854775808"));
            case "inactive" -> n.put("activo",false);
            case "activeString" -> n.put("activo","true");
            case "issuer" -> n.put("iss","bff");
            case "audience" -> n.put("aud","p360.usuarios.consultas.q");
            case "operation" -> n.put("operacion","pago.aprobar.v1");
            case "uuid" -> n.put("jti","1-1-1-1-1");
            case "upperUuid" -> n.put("tenantId",tenant.toString().toUpperCase(Locale.ROOT));
            case "timeOffset" -> n.put("emitidoEn","2026-10-08T10:00:00.000+00:00");
            case "timePrecision" -> n.put("emitidoEn",now.toString());
            case "future" -> { n.put("perfilVerificadoEn",IdentityProofCodec.timestamp(now.plusSeconds(1))); n.put("emitidoEn",IdentityProofCodec.timestamp(now.plusSeconds(1))); n.put("expiraEn",IdentityProofCodec.timestamp(now.plusSeconds(5))); }
            case "ordering" -> n.put("emitidoEn",IdentityProofCodec.timestamp(now.minusMillis(1)));
            case "lifetime" -> n.put("expiraEn",IdentityProofCodec.timestamp(now.plusSeconds(5)));
            case "deadline" -> n.put("deadlineOriginal",IdentityProofCodec.timestamp(now.plusSeconds(6)));
        }
        assertThatThrownBy(()->verify(mutated(n))).isInstanceOf(IdentityProofException.class);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","whitespace","order","utf8"})
    void rejectsNonCanonicalJson(String mutation) {
        String raw=canonical(claims());
        switch(mutation) {
            case "duplicate" -> raw=raw.replace("\"v\":1","\"v\":1,\"v\":1");
            case "trailing" -> raw+="{}";
            case "whitespace" -> raw=" "+raw;
            case "order" -> raw=json.writeValueAsString(Map.of("v",1,"iss","usuarios-service"));
            case "utf8" -> { assertThatThrownBy(()->verify(FixtureIdentityKeys.signRaw(IdentityProofCodec.header(FixtureIdentityKeys.ID),IdentityProofCodec.encode(new byte[]{(byte)0xff})))).isInstanceOf(IdentityProofException.class); return; }
        }
        String token=FixtureIdentityKeys.signRaw(IdentityProofCodec.header(FixtureIdentityKeys.ID),encode(raw));
        assertThatThrownBy(()->verify(token)).isInstanceOf(IdentityProofException.class);
    }
    @Test void rejectsBoundsPaddingSegmentsAndWrongSignatureLength() {
        var parts=FixtureIdentityKeys.sign(proof()).split("\\.");
        for(String bad:List.of("x".repeat(4097),parts[0]+"="+"."+parts[1]+"."+parts[2],"..",String.join(".",parts)+".extra",
                encode("x".repeat(257))+"."+parts[1]+"."+parts[2],parts[0]+"."+encode("x".repeat(2049))+"."+parts[2],
                parts[0]+"."+parts[1]+"."+IdentityProofCodec.encode(new byte[63]),parts[0]+"."+parts[1]+"."+IdentityProofCodec.encode(new byte[65])))
            assertThatThrownBy(()->verify(bad)).isInstanceOf(IdentityProofException.class);
    }
    @Test void rejectsSignatureMutationAndWrongPublicKey() {
        String token=FixtureIdentityKeys.sign(proof()); var p=token.split("\\."); p[2]=IdentityProofCodec.encode(new byte[64]);
        assertThatThrownBy(()->verify(String.join(".",p))).extracting("reason").isEqualTo(IdentityProofException.Reason.FIRMA);
        var wrong=IdentityProofKeys.load(new JWKSet(FixtureActorKeys.generate(FixtureIdentityKeys.ID).toPublicJWK()).toString(),FixtureActorKeys.publicJwks());
        assertThatThrownBy(()->new IdentityProofVerifier(wrong,Clock.fixed(now,ZoneOffset.UTC),Duration.ofMillis(250)).verify(token,tenant,oid,request,now.plusSeconds(5)))
                .extracting("reason").isEqualTo(IdentityProofException.Reason.FIRMA);
    }
    @Test void bindsTenantOidOriginalRequestAndDeadline() {
        String token=FixtureIdentityKeys.sign(proof()); var v=verifier(now,Duration.ofMillis(250));
        assertThatThrownBy(()->v.verify(token,UUID.randomUUID(),oid,request,now.plusSeconds(5))).extracting("reason").isEqualTo(IdentityProofException.Reason.VINCULO);
        assertThatThrownBy(()->v.verify(token,tenant,UUID.randomUUID(),request,now.plusSeconds(5))).extracting("reason").isEqualTo(IdentityProofException.Reason.VINCULO);
        assertThatThrownBy(()->v.verify(token,tenant,oid,UUID.randomUUID(),now.plusSeconds(5))).extracting("reason").isEqualTo(IdentityProofException.Reason.VINCULO);
        assertThatThrownBy(()->v.verify(token,tenant,oid,request,now.plusSeconds(6))).extracting("reason").isEqualTo(IdentityProofException.Reason.VINCULO);
    }
    @Test void conservativeMarginRejectsExactBoundaryWithoutExpiryGraceAndAllowsReadReplay() {
        String token=FixtureIdentityKeys.sign(proof());
        assertThat(verifier(now.plusMillis(3749),Duration.ofMillis(250)).verify(token,tenant,oid,request,now.plusSeconds(5))).isEqualTo(proofWithJti(token));
        assertThatThrownBy(()->verifier(now.plusMillis(3750),Duration.ofMillis(250)).verify(token,tenant,oid,request,now.plusSeconds(5)))
                .extracting("reason").isEqualTo(IdentityProofException.Reason.VENCIDA);
        assertThatThrownBy(()->verifier(now.plusSeconds(4),Duration.ofMillis(250)).verify(token,tenant,oid,request,now.plusSeconds(5)))
                .extracting("reason").isEqualTo(IdentityProofException.Reason.VENCIDA);
        assertThat(verify(token)).isEqualTo(verify(token));
        assertThatThrownBy(()->verifier(now,Duration.ofMillis(249))).isInstanceOf(IllegalArgumentException.class);
    }
    IdentityProof proofWithJti(String token) { return verify(token); }
    @Test void rotationAndRemovalUnknownAndRevokedKeysFailClosed() {
        UUID nextId=UUID.randomUUID(); var next=FixtureActorKeys.generate(nextId).toPublicJWK();
        var rotating=IdentityProofKeys.load(new JWKSet(List.of(FixtureIdentityKeys.KEY.toPublicJWK(),next)).toString(),FixtureActorKeys.publicJwks());
        assertThat(rotating.get(nextId).material().isPrivate()).isFalse();
        var retired=IdentityProofKeys.load(new JWKSet(next).toString(),FixtureActorKeys.publicJwks());
        assertThatThrownBy(()->retired.get(FixtureIdentityKeys.ID)).extracting("reason").isEqualTo(IdentityProofException.Reason.CLAVE);
        var revoked=new ECKey.Builder(FixtureIdentityKeys.KEY.toPublicJWK()).keyRevocation(new KeyRevocation(new Date(),null)).build();
        assertThatThrownBy(()->IdentityProofKeys.load(new JWKSet(revoked).toString(),FixtureActorKeys.publicJwks())).isInstanceOf(IllegalStateException.class);
    }
    @Test void configurationRejectsPrivateDuplicateMissingWrongCurveAndActorKeyReuse() throws Exception {
        var p384=new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_384).keyID(FixtureIdentityKeys.ID.toString()).generate().toPublicJWK();
        for(String bad:List.of("", "{\"keys\":[]}",new JWKSet(FixtureIdentityKeys.KEY).toString(false),
                new JWKSet(List.of(FixtureIdentityKeys.KEY.toPublicJWK(),FixtureIdentityKeys.KEY.toPublicJWK())).toString(),FixtureActorKeys.publicJwks(),
                new JWKSet(p384).toString(),
                new JWKSet(List.of(FixtureIdentityKeys.KEY.toPublicJWK(),new ECKey.Builder(FixtureIdentityKeys.KEY.toPublicJWK()).keyID(UUID.randomUUID().toString()).build())).toString(),
                new JWKSet(new ECKey.Builder(FixtureActorKeys.KEY.toPublicJWK()).keyID(FixtureIdentityKeys.ID.toString()).build()).toString()))
            assertThatThrownBy(()->IdentityProofKeys.load(bad,FixtureActorKeys.publicJwks())).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsUnknownKidWrongTypeDuplicateHeaderAndNonCanonicalKid() {
        for(String raw:List.of("{\"alg\":\"ES256\",\"kid\":\""+UUID.randomUUID()+"\",\"typ\":\""+IdentityProof.TYPE+"\"}",
                "{\"alg\":\"ES256\",\"kid\":\""+FixtureIdentityKeys.ID+"\",\"typ\":\"p360act2\"}",
                "{\"alg\":\"ES256\",\"alg\":\"ES256\",\"kid\":\""+FixtureIdentityKeys.ID+"\",\"typ\":\""+IdentityProof.TYPE+"\"}",
                "{\"alg\":\"ES256\",\"kid\":\"1-1-1-1-1\",\"typ\":\""+IdentityProof.TYPE+"\"}"))
            assertThatThrownBy(()->verify(FixtureIdentityKeys.signRaw(encode(raw),IdentityProofCodec.payload(proof())))).isInstanceOf(IdentityProofException.class);
    }
}
