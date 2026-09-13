package cl.duoc.pedidos360.pedidos;
import cl.duoc.pedidos360.pedidos.service.CatalogoPedidosHttp;
import cl.duoc.pedidos360.pedidos.security.UpstreamSeguro;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class CatalogoPedidosHttpTests {
 com.sun.net.httpserver.HttpServer server;
 UpstreamSeguro http;
 CatalogoPedidosHttp catalog;
 String body;
 int status;
 String auth;
 @BeforeEach void start() throws Exception {
  status=200; body="{\"id\":101,\"restauranteId\":20,\"precio\":8490,\"disponible\":true}";
  server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/productos/101", exchange -> {
   auth=exchange.getRequestHeaders().getFirst("Authorization");
   byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.getResponseHeaders().add("Content-Type","application/json");
   exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
  }); server.start(); http=new UpstreamSeguro();
  catalog=new CatalogoPedidosHttp(http,new MockEnvironment().withProperty("pedidos.productos-url","http://127.0.0.1:"+server.getAddress().getPort()));
 }
 @AfterEach void stop() { http.close(); server.stop(0); }
 @Test void usaPrecioRemotoSinBearer() { assertThat(catalog.precio(101L,20L)).isEqualTo(8490); assertThat(auth).isNull(); }
 @Test void rechazaOtroRestaurante() { assertThatThrownBy(()->catalog.precio(101L,21L)).hasMessageContaining("restaurante"); }
 @Test void rechazaNoDisponible() { body=body.replace("true","false"); assertThatThrownBy(()->catalog.precio(101L,20L)).hasMessageContaining("disponible"); }
 @Test void rechazaPreciosNoEnterosNegativosOExcesivos() {
  String original=body;
  for(String price:java.util.List.of("1.5","-1","100000001","999999999999999999999999")) {
   body=original.replace("8490",price); assertThatThrownBy(()->catalog.precio(101L,20L)).hasMessageContaining("CLP");
  }
 }
 @Test void noUsaPrecioDeRespaldoAnteFallo() { status=503; assertThatThrownBy(()->catalog.precio(101L,20L)).hasMessageContaining("no disponible"); }
 @Test void rechazaIdRemotoDistinto() { body=body.replace("101","102"); assertThatThrownBy(()->catalog.precio(101L,20L)).hasMessageContaining("inválida"); }
 @Test void noSigueRedireccion() { status=302; assertThatThrownBy(()->catalog.precio(101L,20L)).hasMessageContaining("no disponible"); }
}

