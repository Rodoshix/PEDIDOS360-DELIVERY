package cl.duoc.pedidos360.bff.security;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class EntraTestTokens {
    public static final String TENANT = "11111111-1111-1111-1111-111111111111";
    public static final String API = "22222222-2222-2222-2222-222222222222";
    public static final String FRONTEND = "33333333-3333-3333-3333-333333333333";
    public static final String USER = "44444444-4444-4444-4444-444444444444";
    static final RSAKey KEY = key();
    static RSAKey key() {
        try { return new RSAKeyGenerator(2048).generate(); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static NimbusJwtDecoder decoder() {
        try {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(EntraConfiguration.validators(TENANT, API, FRONTEND));
            return decoder;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String token(Map<String, Object> overrides) { return token(overrides, KEY); }
    static String token(Map<String, Object> overrides, RSAKey key) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .issuer("https://login.microsoftonline.com/" + TENANT + "/v2.0")
                    .audience(API).subject(USER)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .notBeforeTime(Date.from(Instant.now().minusSeconds(30)))
                    .claim("ver", "2.0").claim("tid", TENANT).claim("oid", USER)
                    .claim("azp", FRONTEND).claim("scp", "access_as_user")
                    .claim("roles", List.of("CLIENTE"));
            overrides.forEach(claims::claim);
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}

