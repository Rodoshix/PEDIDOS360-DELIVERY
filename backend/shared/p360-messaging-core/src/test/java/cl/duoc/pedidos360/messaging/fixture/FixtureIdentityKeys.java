package cl.duoc.pedidos360.messaging.fixture;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import cl.duoc.pedidos360.messaging.identity.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Solo tests: claves efímeras en memoria, independientes del actor; no material versionado. */
public final class FixtureIdentityKeys {
    public static final UUID ID = UUID.fromString("22222222-2222-4333-8444-555555555555");
    public static final ECKey KEY = FixtureActorKeys.generate(ID);
    public static String publicJwks() { return new JWKSet(KEY.toPublicJWK()).toString(); }
    public static IdentityProofKeys keys() { return IdentityProofKeys.load(publicJwks(), FixtureActorKeys.publicJwks()); }
    public static String sign(IdentityProof proof) { return signRaw(IdentityProofCodec.header(ID), IdentityProofCodec.payload(proof)); }
    public static String signRaw(String encodedHeader, String encodedPayload) {
        try {
            String input = encodedHeader + "." + encodedPayload;
            var header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ID.toString()).type(new JOSEObjectType(IdentityProof.TYPE)).build();
            return input + "." + new ECDSASigner(KEY).sign(header, input.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception invalid) { throw new IllegalStateException("fixture de prueba inválida"); }
    }
}
