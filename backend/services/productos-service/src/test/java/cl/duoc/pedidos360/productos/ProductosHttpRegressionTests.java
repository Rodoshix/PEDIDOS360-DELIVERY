package cl.duoc.pedidos360.productos;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import cl.duoc.pedidos360.messaging.relay.QueryConsumer;
import cl.duoc.pedidos360.productos.entity.Producto;
import cl.duoc.pedidos360.productos.repository.ProductoRepository;
import cl.duoc.pedidos360.productos.service.ProductoService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductosHttpRegressionTests {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", postgres::getJdbcUrl);
        p.add("spring.datasource.username", postgres::getUsername);
        p.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired ProductoRepository repository;
    @Autowired ProductoService service;
    @Autowired ApplicationContext context;
    @Autowired JsonMapper json;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void clearDatabase() { repository.deleteAll(); }

    HttpResponse<String> call(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    String product(String name, boolean available) {
        return "{\"restauranteId\":7,\"nombre\":\"" + name + "\",\"descripcion\":\"Test\","
                + "\"precio\":1200.00,\"categoria\":\"Comida\",\"disponible\":" + available + "}";
    }

    @Test void httpAndHealthWorkWithoutRabbitOrActorSecretsByDefault() throws Exception {
        assertThat(context.getEnvironment().getProperty("pedidos360.messaging.relay-mode")).isEqualTo("DISABLED");
        assertThat(context.getEnvironment().getProperty("spring.rabbitmq.dynamic")).isEqualTo("false");
        assertThat(context.getBeansOfType(QueryConsumer.class)).isEmpty();
        assertThat(context.containsBean("queryTopology")).isFalse();
        assertThat(context.containsBean("amqpAdmin")).isFalse();
        assertThat(context.containsBean("springSecurityFilterChain")).isFalse();
        assertThat(call("GET", "/productos", null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test void realRepositoryFiltersUnavailableAndOtherRestaurants() throws Exception {
        repository.saveAll(List.of(new Producto(7L,"Disponible","Test",new BigDecimal("1200"),"Comida",true),
                new Producto(7L,"Oculto","Test",new BigDecimal("1200"),"Comida",false),
                new Producto(8L,"Otro","Test",new BigDecimal("1200"),"Comida",true)));
        var response = call("GET", "/productos/restaurante/7/disponibles", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode payload = json.readTree(response.body());
        assertThat(payload).isEqualTo(json.readTree(json.writeValueAsBytes(service.listarDisponiblesPorRestaurante(7L))));
        assertThat(payload.size()).isEqualTo(1);
        assertThat(payload.get(0).path("nombre").asString()).isEqualTo("Disponible");
        assertThat(payload.get(0).propertyNames()).containsExactlyInAnyOrder(
                "id","restauranteId","nombre","descripcion","precio","categoria","disponible");
    }

    @Test void emptyRestaurantKeepsHttp200EmptyArray() throws Exception {
        var response = call("GET", "/productos/restaurante/999/disponibles", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).isEmpty()).isTrue();
    }

    @Test void crudAndAvailabilityEndpointsKeepTheirBehavior() throws Exception {
        var created = call("POST", "/productos", product("Original", true));
        assertThat(created.statusCode()).isEqualTo(201);
        long id = json.readTree(created.body()).path("id").asLong();
        assertThat(call("GET", "/productos/" + id, null).statusCode()).isEqualTo(200);
        var updated = call("PUT", "/productos/" + id, product("Actualizado", true));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).path("nombre").asString()).isEqualTo("Actualizado");
        var hidden = call("PATCH", "/productos/" + id + "/disponibilidad?disponible=false", null);
        assertThat(hidden.statusCode()).isEqualTo(200);
        assertThat(json.readTree(hidden.body()).path("disponible").asBoolean()).isFalse();
        assertThat(json.readTree(call("GET", "/productos/restaurante/7/disponibles", null).body()).isEmpty()).isTrue();
        assertThat(json.readTree(call("GET", "/productos/restaurante/7", null).body()).size()).isEqualTo(1);
    }

    @Test void invalidCrudRequestStillReturns400() throws Exception {
        assertThat(call("POST", "/productos", "{}").statusCode()).isEqualTo(400);
        assertThat(repository.count()).isZero();
    }

    @Test void nonexistentProductStillReturns404() throws Exception {
        assertThat(call("GET", "/productos/999999", null).statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/productos/999999", product("Test", true)).statusCode()).isEqualTo(404);
    }
}
