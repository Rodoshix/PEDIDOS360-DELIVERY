package cl.duoc.pedidos360.pagos;

import cl.duoc.pedidos360.messaging.actor.*;
import cl.duoc.pedidos360.messaging.identity.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.time.*;
import java.util.*;

/** Ephemeral test authorities only. No private material is written to disk. */
public final class PagosQueryKeys {
    static final UUID ACTOR_ID = UUID.randomUUID(), PROOF_ID = UUID.randomUUID();
    static final ECKey ACTOR = generate(ACTOR_ID), PROOF = generate(PROOF_ID);
    static ECKey generate(UUID id) {
        try { return new ECKeyGenerator(Curve.P_256).keyID(id.toString()).generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String actors() { return new JWKSet(ACTOR.toPublicJWK()).toString(); }
    public static String proofs() { return new JWKSet(PROOF.toPublicJWK()).toString(); }
    static ActorContextSigner signer() {
        var key = new SigningKey(ACTOR_ID, ACTOR);
        return new ActorContextSigner(new SigningKeyProvider() {
            public Optional<SigningKey> claveParaFirmar() { return Optional.of(key); }
            public Optional<SigningKey> clavePorId(UUID id) { return ACTOR_ID.equals(id) ? Optional.of(key.publica()) : Optional.empty(); }
        });
    }
    static String proof(IdentityProof proof) {
        try {
            var jws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(PROOF_ID.toString())
                    .type(new JOSEObjectType(IdentityProof.TYPE)).build(),
                    new Payload(new String(Base64.getUrlDecoder().decode(IdentityProofCodec.payload(proof)),java.nio.charset.StandardCharsets.UTF_8)));
            // The approved codec supplies canonical header as well as canonical payload.
            String input = IdentityProofCodec.header(PROOF_ID) + "." + IdentityProofCodec.payload(proof);
            return input + "." + new ECDSASigner(PROOF).sign(jws.getHeader(),input.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
