package cl.duoc.pedidos360.integration;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerfilJourneyTests {
    static final String TENANT = "11111111-1111-1111-1111-111111111111";
    static final String API = "22222222-2222-2222-2222-222222222222";
    static final String FRONTEND = "33333333-3333-3333-3333-333333333333";
    static final String USER = "44444444-4444-4444-4444-444444444444";
    static final RSAKey KEY = key();
    final JsonMapper mapper = JsonMapper.builder().build();
    PostgreSQLContainer postgres;
    ConfigurableApplicationContext usuarios, bff;
    int usuariosPort, bffPort;
    long profileId;

    static RSAKey key() { try { return new RSAKeyGenerator(2048).generate(); } catch (Exception e) { throw new IllegalStateException(e); } }
    static NimbusJwtDecoder decoder(boolean usuario) {
        try {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(usuario
                    ? cl.duoc.pedidos360.usuarios.security.EntraConfiguration.validators(TENANT, API, FRONTEND)
                    : cl.duoc.pedidos360.bff.security.EntraConfiguration.validators(TENANT, API, FRONTEND));
            return decoder;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @TestConfiguration(proxyBeanMethods = false) static class UsuariosKeys {
        @Bean @Primary JwtDecoder temporaryDecoder() { return decoder(true); }
    }
    @TestConfiguration(proxyBeanMethods = false) static class BffKeys {
        @Bean @Primary JwtDecoder temporaryDecoder() { return decoder(false); }
    }
    String token(Map<String,Object> overrides) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://login.microsoftonline.com/" + TENANT + "/v2.0")
                .audience(API).subject(USER).claim("tid", TENANT).claim("oid", USER).claim("azp", FRONTEND)
                .claim("ver", "2.0").claim("scp", "access_as_user").claim("roles", List.of("CLIENTE"))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(30))).expirationTime(Date.from(Instant.now().plusSeconds(600)));
        overrides.forEach(claims::claim);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
    String[] args(int port) {
        return new String[] {"--spring.config.location=optional:classpath:/isolated-test.properties",
            "--server.address=127.0.0.1", "--server.port=" + port,
            "--entra.enabled=true", "--entra.tenant-id=" + TENANT, "--entra.api-client-id=" + API,
            "--entra.frontend-client-id=" + FRONTEND, "--usuarios.identidad-local.enabled=false",
            "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(), "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.flyway.default-schema=usuarios", "--spring.flyway.schemas=usuarios",
            "--spring.jpa.properties.hibernate.default_schema=usuarios", "--spring.jpa.open-in-view=false",
            "--spring.jackson.deserialization.fail-on-unknown-properties=true",
            "--management.endpoints.web.exposure.include=health", "--management.endpoint.health.show-details=never"};
    }
    void startUsuarios(int port) {
        usuarios = new SpringApplicationBuilder(cl.duoc.pedidos360.usuarios.UsuariosApplication.class, UsuariosKeys.class).run(args(port));
        usuariosPort = Integer.parseInt(usuarios.getEnvironment().getProperty("local.server.port"));
    }
    @BeforeAll void start() {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        startUsuarios(0);
        var arguments = new ArrayList<>(List.of(args(0)));
        arguments.add("--bff.usuarios-url=http://127.0.0.1:" + usuariosPort);
        // BFF no necesita base: excluir autoconfiguraciones aportadas por el classpath de la prueba.
        arguments.add("--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
        bff = new SpringApplicationBuilder(cl.duoc.pedidos360.bff.BffApplication.class, BffKeys.class).run(arguments.toArray(String[]::new));
        bffPort = Integer.parseInt(bff.getEnvironment().getProperty("local.server.port"));
    }
    @AfterAll void stop() {
        if (bff != null) bff.close();
        if (usuarios != null) usuarios.close();
        if (postgres != null) postgres.stop();
    }
    HttpResponse<String> call(int port, String method, String path, String token, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(java.time.Duration.ofSeconds(10)).header("Origin", "http://localhost:5173")
                    .header("X-User-Id", "untrusted").header("X-Roles", "ADMIN");
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test @Order(1) void creaConsultaActualizaYConservaPerfilTrasReinicio() throws Exception {
        String token = token(Map.of());
        assertThat(call(bffPort, "GET", "/usuarios/me", token, null).statusCode()).isEqualTo(404);
        String profile = "{\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"email\":\"ana@example.test\"}";
        var created = call(bffPort, "POST", "/usuarios", token, profile);
        assertThat(created.statusCode()).isEqualTo(201);
        profileId = mapper.readTree(created.body()).get("id").asLong();
        assertThat(created.headers().firstValue("location")).hasValue("/usuarios/" + profileId);
        assertThat(created.headers().firstValue("access-control-allow-origin")).hasValue("http://localhost:5173");
        assertThat(call(bffPort, "POST", "/usuarios", token, profile).statusCode()).isEqualTo(409);
        assertThat(call(bffPort, "PUT", "/usuarios/" + profileId, token, profile.replace("Ana", "Actualizada")).statusCode()).isEqualTo(200);
        usuarios.close();
        startUsuarios(usuariosPort);
        var reloaded = call(bffPort, "GET", "/usuarios/me", token, null);
        assertThat(reloaded.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(reloaded.body()).get("nombre").asText()).isEqualTo("Actualizada");
    }
    @Test @Order(2) void rechazaTokensYPerfilesAjenosEnAmbasCapas() throws Exception {
        assertThat(call(bffPort, "GET", "/usuarios/me", null, null).statusCode()).isEqualTo(401);
        assertThat(call(usuariosPort, "GET", "/usuarios/me", null, null).statusCode()).isEqualTo(401);
        String invalid = token(Map.of("aud", FRONTEND));
        assertThat(call(bffPort, "GET", "/usuarios/me", invalid, null).statusCode()).isEqualTo(401);
        assertThat(call(usuariosPort, "GET", "/usuarios/me", invalid, null).statusCode()).isEqualTo(401);
        assertThat(call(bffPort, "GET", "/usuarios/me", token(Map.of("scp", "otro")), null).statusCode()).isEqualTo(403);
        String other = token(Map.of("oid", "55555555-5555-5555-5555-555555555555"));
        assertThat(call(bffPort, "GET", "/usuarios/" + profileId, other, null).statusCode()).isEqualTo(403);
        assertThat(call(bffPort, "GET", "/usuarios", token(Map.of()), null).statusCode()).isEqualTo(403);
        assertThat(call(bffPort, "GET", "/usuarios", token(Map.of("roles", List.of("ADMIN"))), null).statusCode()).isEqualTo(200);
    }
    @Test @Order(3) void bajaPerfilYReportaServicioCaido() throws Exception {
        String token = token(Map.of());
        assertThat(call(bffPort, "DELETE", "/usuarios/" + profileId, token, null).statusCode()).isEqualTo(204);
        assertThat(call(bffPort, "GET", "/usuarios/me", token, null).statusCode()).isEqualTo(403);
        usuarios.close();
        assertThat(call(bffPort, "GET", "/usuarios/me", token, null).statusCode()).isEqualTo(502);
    }
}
