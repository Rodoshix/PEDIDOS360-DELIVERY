package cl.duoc.pedidos360.bff;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import cl.duoc.pedidos360.bff.security.EntraTestTokens;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "entra.enabled=true",
    "entra.tenant-id=11111111-1111-1111-1111-111111111111",
    "entra.api-client-id=22222222-2222-2222-2222-222222222222",
    "entra.frontend-client-id=33333333-3333-3333-3333-333333333333",
    "usuarios.identidad-local.enabled=false",
    "spring.config.import="
})
@Import({JwtHttpTests.Keys.class, JwtHttpTests.Probe.class})
class JwtHttpTests {
    @LocalServerPort int port;
    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean @Primary JwtDecoder testDecoder() { return EntraTestTokens.decoder(); }
    }
    @org.springframework.web.bind.annotation.RestController
    static class Probe {
        @org.springframework.web.bind.annotation.GetMapping("/usuarios/me")
        Map<String, String> perfil() { return Map.of("status", "authenticated"); }
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
    
    @Test void tokenValidoAccedeSinCrearSesion() throws Exception {
        var response = call("GET", "/usuarios/me", EntraTestTokens.token(Map.of()), null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    }
}

