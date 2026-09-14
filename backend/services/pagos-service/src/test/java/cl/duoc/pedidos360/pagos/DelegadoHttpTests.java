package cl.duoc.pedidos360.pagos;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import cl.duoc.pedidos360.pagos.security.EntraTestTokens;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
 "entra.enabled=true", "entra.tenant-id=11111111-1111-1111-1111-111111111111",
 "entra.api-client-id=22222222-2222-2222-2222-222222222222",
 "entra.frontend-client-id=33333333-3333-3333-3333-333333333333",
 "pagos.identidad-local.enabled=false", "spring.config.import="
})
@Import({DelegadoHttpTests.Keys.class, PostgresTestConfiguration.class})
class DelegadoHttpTests {
 @LocalServerPort int port;
 static final com.sun.net.httpserver.HttpServer users;
 static volatile boolean active = true;
 static {
  try {
   users = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   users.createContext("/usuarios/me", exchange -> {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    String oid = EntraTestTokens.decoder().decode(header.substring(7)).getClaimAsString("oid");
    long id = EntraTestTokens.USER.equals(oid) ? 42 : 43;
    byte[] bytes = ("{\"id\":" + id + ",\"activo\":" + active + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type","application/json");
    exchange.sendResponseHeaders(200,bytes.length);
    exchange.getResponseBody().write(bytes); exchange.close();
   });
   users.start();
  } catch (Exception e) { throw new ExceptionInInitializerError(e); }
 }
 @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
  registry.add("entra.usuarios-url", () -> "http://127.0.0.1:" + users.getAddress().getPort());
 }
 @AfterAll static void stop() { users.stop(0); }
 @BeforeEach void reset() { active = true; }
 @TestConfiguration(proxyBeanMethods=false) static class Keys {
  @Bean @Primary @org.springframework.beans.factory.annotation.Qualifier("entraDelegadoDecoder")
  JwtDecoder testDecoder() { return EntraTestTokens.decoder(); }
 }
 HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
  try(var http=HttpClient.newHttpClient()) {
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
    .header("X-User-Id","999").header("X-Roles","ADMIN");
   if(token!=null) request.header("Authorization","Bearer "+token);
   if(body!=null) request.header("Content-Type","application/json");
   return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
 }
 @Test void rechazaSinJwtYClaimsNoDelegados() throws Exception {
  String path="/pagos/987654";
  assertThat(call("GET",path,null,null).statusCode()).isEqualTo(401);
  assertThat(call("GET",path,"falso",null).statusCode()).isEqualTo(401);
  for(var claims:List.of(Map.<String,Object>of("scp","otro"),Map.<String,Object>of("roles",List.of("Pedidos.Confirmar"))))
   assertThat(call("GET",path,EntraTestTokens.token(claims),null).statusCode()).isEqualTo(403);
  assertThat(call("GET",path,EntraTestTokens.token(Map.of("aud",EntraTestTokens.FRONTEND)),null).statusCode()).isEqualTo(401);
 }
 @Test void perfilInactivoNoAccede() throws Exception {
  active=false;
  assertThat(call("GET","/pagos/987654",EntraTestTokens.token(Map.of()),null).statusCode()).isEqualTo(403);
 }
 
 @Test void tokenValidoResuelvePerfilAntesDeConsultarPago() throws Exception {
  assertThat(call("GET","/pagos/987654",EntraTestTokens.token(Map.of()),null).statusCode()).isEqualTo(404);
 }
}

