package cl.duoc.pedidos360.restaurantes;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import cl.duoc.pedidos360.messaging.relay.QueryConsumer;
import cl.duoc.pedidos360.restaurantes.entity.*;
import cl.duoc.pedidos360.restaurantes.repository.RestauranteRepository;
import cl.duoc.pedidos360.restaurantes.service.RestauranteService;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestaurantesHttpRegressionTests {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);
        p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired RestauranteRepository repository;
    @Autowired RestauranteService service;
    @Autowired ApplicationContext context;
    @Autowired JsonMapper json;
    @LocalServerPort int port;
    @BeforeEach void clearDatabase() { repository.deleteAll(); }
    HttpResponse<String> call(String method,String path,String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body))
                .build(),HttpResponse.BodyHandlers.ofString());
    }
    String data(String name,String state) {
        return "{\"nombre\":\""+name+"\",\"descripcion\":\"Test\",\"direccion\":\"A\",\"estado\":\""+state+"\"}";
    }
    @Test void defaultHttpAndHealthNeedNoBrokerOrSecurityFilterChain() throws Exception {
        assertThat(context.getEnvironment().getProperty("pedidos360.messaging.relay-mode")).isEqualTo("DISABLED");
        assertThat(context.getEnvironment().getProperty("spring.rabbitmq.dynamic")).isEqualTo("false");
        assertThat(context.getBeansOfType(QueryConsumer.class)).isEmpty();
        assertThat(context.containsBean("queryTopology")).isFalse();
        assertThat(context.containsBean("amqpAdmin")).isFalse();
        assertThat(context.containsBean("springSecurityFilterChain")).isFalse();
        assertThat(call("GET","/restaurantes",null).statusCode()).isEqualTo(200);
        assertThat(call("GET","/actuator/health",null).statusCode()).isEqualTo(200);
    }
    @Test void listIncludesInactiveWithUnchangedDto() throws Exception {
        repository.saveAndFlush(new Restaurante("Inactivo","Test","A",EstadoRestaurante.INACTIVO));
        var http=call("GET","/restaurantes",null);
        assertThat(http.statusCode()).isEqualTo(200);
        var payload=json.readTree(http.body());
        assertThat(payload).isEqualTo(json.readTree(json.writeValueAsBytes(service.listar())));
        assertThat(payload.get(0).path("estado").asString()).isEqualTo("INACTIVO");
        assertThat(payload.get(0).propertyNames()).containsExactlyInAnyOrder("id","nombre","descripcion","direccion","estado");
    }
    @Test void emptyListIsHttp200Array() throws Exception {
        var http=call("GET","/restaurantes",null);
        assertThat(http.statusCode()).isEqualTo(200);
        assertThat(json.readTree(http.body()).isArray()).isTrue();
        assertThat(json.readTree(http.body()).isEmpty()).isTrue();
    }
    @Test void crudAndSoftDeactivationKeepBehavior() throws Exception {
        var created=call("POST","/restaurantes",data("Original","ABIERTO"));
        assertThat(created.statusCode()).isEqualTo(201);
        long id=json.readTree(created.body()).path("id").asLong();
        assertThat(call("GET","/restaurantes/"+id,null).statusCode()).isEqualTo(200);
        var updated=call("PUT","/restaurantes/"+id,data("Actualizado","CERRADO"));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).path("nombre").asString()).isEqualTo("Actualizado");
        assertThat(call("DELETE","/restaurantes/"+id,null).statusCode()).isEqualTo(204);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(json.readTree(call("GET","/restaurantes",null).body()).get(0).path("estado").asString()).isEqualTo("INACTIVO");
    }
    @Test void invalidCrudStillReturns400WithoutPersistence() throws Exception {
        assertThat(call("POST","/restaurantes","{}").statusCode()).isEqualTo(400);
        assertThat(repository.count()).isZero();
    }
    @Test void missingRestaurantStillReturns404() throws Exception {
        assertThat(call("GET","/restaurantes/999999",null).statusCode()).isEqualTo(404);
        assertThat(call("PUT","/restaurantes/999999",data("Otro","ABIERTO")).statusCode()).isEqualTo(404);
        assertThat(call("DELETE","/restaurantes/999999",null).statusCode()).isEqualTo(404);
    }

    @Test void openApiStillDescribesCatalogHttpContract() throws Exception {
        var response = call("GET", "/v3/api-docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var document = json.readTree(response.body());
        assertThat(document.path("openapi").asString()).startsWith("3.");
        var operation = document.path("paths").path("/restaurantes").path("get");
        assertThat(operation.isObject()).isTrue();
        assertThat(operation.path("responses").has("200")).isTrue();
        assertThat(document.path("components").path("schemas").isObject()).isTrue();
    }
}
