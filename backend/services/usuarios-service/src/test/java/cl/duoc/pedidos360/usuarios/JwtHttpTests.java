package cl.duoc.pedidos360.usuarios;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import cl.duoc.pedidos360.usuarios.security.EntraTestTokens;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "entra.enabled=true",
    "entra.tenant-id=11111111-1111-1111-1111-111111111111",
    "entra.api-client-id=22222222-2222-2222-2222-222222222222",
    "entra.frontend-client-id=33333333-3333-3333-3333-333333333333",
    "usuarios.identidad-local.enabled=false",
    "spring.config.import="
})
@Import({JwtHttpTests.Keys.class, PostgresTestConfiguration.class})
class JwtHttpTests {
    @LocalServerPort int port;
    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean @Primary JwtDecoder testDecoder() { return EntraTestTokens.decoder(); }
    }
    
    HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("X-User-Id", "attacker").header("X-Roles", "ADMIN");
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void sinTokenNiCabecerasFalsificadasAutentican() throws Exception {
        assertThat(call("GET", "/usuarios/me", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/usuarios/me", "falso", null).statusCode()).isEqualTo(401);
    }
    @Test void scopeYRolSonObligatorios() throws Exception {
        for (var claims : List.of(Map.<String,Object>of("scp", "otro"),
                Map.<String,Object>of("roles", List.of("SUPERADMIN")))) {
            assertThat(call("GET", "/usuarios/me", EntraTestTokens.token(claims), null).statusCode()).isEqualTo(403);
        }
    }
    @Test void audienciaDelFrontendNoSirve() throws Exception {
        assertThat(call("GET", "/usuarios/me", EntraTestTokens.token(Map.of("aud", EntraTestTokens.FRONTEND)), null)
                .statusCode()).isEqualTo(401);
    }
    
    @org.springframework.beans.factory.annotation.Autowired
    cl.duoc.pedidos360.usuarios.repository.UsuarioRepository repository;
    @org.springframework.beans.factory.annotation.Autowired tools.jackson.databind.json.JsonMapper mapper;
    @org.junit.jupiter.api.BeforeEach void clean() { repository.deleteAll(); }

    @Test void identidadJwtPersisteYAislaPerfiles() throws Exception {
        String token = EntraTestTokens.token(Map.of());
        String profile = "{\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"email\":\"ana@example.test\"}";
        var created = call("POST", "/usuarios", token, profile);
        assertThat(created.statusCode()).isEqualTo(201);
        long id = mapper.readTree(created.body()).get("id").asLong();
        assertThat(call("GET", "/usuarios/me", token, null).statusCode()).isEqualTo(200);
        var persisted = repository.findByTenantIdAndEntraObjectId(UUID.fromString(EntraTestTokens.TENANT),
                UUID.fromString(EntraTestTokens.USER));
        assertThat(persisted).isPresent();

        String other = EntraTestTokens.token(Map.of("oid", "55555555-5555-5555-5555-555555555555"));
        assertThat(call("GET", "/usuarios/" + id, other, null).statusCode()).isEqualTo(403);
        assertThat(call("PUT", "/usuarios/" + id, other, profile).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/usuarios", token, null).statusCode()).isEqualTo(403);
        String admin = EntraTestTokens.token(Map.of("roles", List.of("ADMIN")));
        assertThat(call("GET", "/usuarios", admin, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/usuarios/" + id,
                EntraTestTokens.token(Map.of("tid", EntraTestTokens.API)), null).statusCode()).isEqualTo(401);
    }
}

