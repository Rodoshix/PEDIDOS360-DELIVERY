package cl.duoc.pedidos360.pedidos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor JWKS local y emisión de tokens firmados, para probar el decoder real
 * (firma, issuer, audiencia) sin depender de Entra.
 */
final class JwksTestServer implements AutoCloseable {

    private final HttpServer server;
    private final RSAKey clave;
    private final RSAKey otraClave;
    private final String issuer;

    JwksTestServer(String tenant) throws Exception {
        this.clave = new RSAKeyGenerator(2048).keyID("test-key").generate();
        this.otraClave = new RSAKeyGenerator(2048).keyID("otra-key").generate();
        this.issuer = "https://login.microsoftonline.com/" + tenant + "/v2.0";
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(clave.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, body.length);
                out.write(body);
            }
        });
        server.start();
    }

    String jwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    /** Token válido firmado con la clave publicada en el JWKS. */
    String tokenValido(String tenant, String audiencia, String worker, String rol) throws Exception {
        return firmar(clave, claims(tenant, audiencia, worker, rol, "2.0", null));
    }

    /** Token manipulado: firmado con otra clave (firma inválida para el JWKS publicado). */
    String tokenFirmaInvalida(String tenant, String audiencia, String worker, String rol) throws Exception {
        return firmar(otraClave, claims(tenant, audiencia, worker, rol, "2.0", null));
    }

    /** Token delegado de usuario: incluye scp. */
    String tokenDelegado(String tenant, String audiencia, String worker, String rol) throws Exception {
        return firmar(clave, claims(tenant, audiencia, worker, rol, "2.0", "access_as_user"));
    }

    private JWTClaimsSet claims(String tenant, String audiencia, String worker, String rol,
            String ver, String scp) {
        var builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("worker")
                .audience(audiencia)
                .issueTime(Date.from(Instant.now().minusSeconds(30)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(30)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .claim("ver", ver)
                .claim("tid", tenant)
                .claim("roles", List.of(rol))
                .claim("azp", worker);
        if (scp != null) {
            builder.claim("scp", scp);
        }
        return builder.build();
    }

    private String firmar(RSAKey key, JWTClaimsSet claims) throws Exception {
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build();
        var jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
