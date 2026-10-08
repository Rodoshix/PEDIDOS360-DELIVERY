package cl.duoc.pedidos360.messaging.identity;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import cl.duoc.pedidos360.messaging.actor.SigningKey;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Públicas de Usuarios. No ofrece emisión y nunca descubre claves desde un mensaje. */
public final class IdentityProofKeys {
    private final Map<UUID, SigningKey> keys;
    private IdentityProofKeys(Map<UUID, SigningKey> keys) { this.keys = Map.copyOf(keys); }
    public SigningKey get(UUID kid) {
        SigningKey key = keys.get(kid);
        if (key == null) throw new IdentityProofException(IdentityProofException.Reason.CLAVE); return key;
    }
    public static IdentityProofKeys load(String publicJwks, String actorPublicJwks) {
        try {
            var json = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            if (publicJwks == null || publicJwks.isBlank() || publicJwks.length() > 65536
                    || actorPublicJwks == null || actorPublicJwks.isBlank() || actorPublicJwks.length() > 65536)
                throw new IllegalArgumentException();
            json.readTree(publicJwks); json.readTree(actorPublicJwks);
            var actorKeys = JWKSet.parse(actorPublicJwks).getKeys();
            if (actorKeys.isEmpty()) throw new IllegalArgumentException();
            var rawKeys = JWKSet.parse(publicJwks).getKeys();
            if (rawKeys.isEmpty() || rawKeys.size() > 16) throw new IllegalArgumentException();
            var result = new java.util.LinkedHashMap<UUID, SigningKey>();
            var materialIds = new java.util.HashSet<String>();
            for (var raw : rawKeys) {
                if (!(raw instanceof ECKey ec) || ec.isPrivate()) throw new IllegalArgumentException();
                var key = new SigningKey(UUID.fromString(ec.getKeyID()), ec);
                if (result.putIfAbsent(key.keyId(), key) != null) throw new IllegalArgumentException();
                if (!materialIds.add(ec.computeThumbprint().toString())) throw new IllegalArgumentException();
                for (var actor : actorKeys) {
                    if (key.keyId().toString().equals(actor.getKeyID()) || ec.computeThumbprint().equals(actor.computeThumbprint()))
                        throw new IllegalArgumentException();
                }
            }
            return new IdentityProofKeys(result);
        } catch (Exception invalid) { throw new IllegalStateException("prueba de identidad exige públicas P-256 propias de Usuarios, distintas de ActorContext"); }
    }
    @Override public String toString() { return "IdentityProofKeys[public-only]"; }
}
