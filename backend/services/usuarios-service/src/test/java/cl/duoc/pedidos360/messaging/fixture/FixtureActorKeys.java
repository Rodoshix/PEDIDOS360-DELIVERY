package cl.duoc.pedidos360.messaging.fixture;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import cl.duoc.pedidos360.messaging.actor.*;

/** Exclusivamente tests: material efímero generado en memoria, nunca exportado ni desplegado. */
public final class FixtureActorKeys {
    public static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    public static final ECKey KEY = generate(ID);
    public static ECKey generate(UUID id) {
        try { return new ECKeyGenerator(Curve.P_256).keyID(id.toString()).generate(); }
        catch (Exception invalid) { throw new IllegalStateException("fixture EC no disponible"); }
    }
    public static String publicJwks() { return new JWKSet(KEY.toPublicJWK()).toString(); }
    public static String privateJwk() { return KEY.toJSONString(); }
    public static SigningKeyProvider provider() {
        var key = new SigningKey(ID, KEY);
        return new SigningKeyProvider() {
            public Optional<SigningKey> claveParaFirmar() { return Optional.of(key); }
            public Optional<SigningKey> clavePorId(UUID id) {
                return ID.equals(id) ? Optional.of(key.publica()) : Optional.empty();
            }
        };
    }
    public static ActorContextSigner signer() {
        return new ActorContextSigner(provider(), Clock.systemUTC(), Duration.ofSeconds(5));
    }
    public static String signRaw(String header, String payload) {
        try {
            var jws = new JWSObject(JWSHeader.parse(header), new Payload(payload));
            jws.sign(new ECDSASigner(KEY)); return jws.serialize();
        } catch (Exception invalid) { throw new IllegalStateException("fixture JWS inválido"); }
    }
}
