package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual executable service jars and HTTP entry points, isolated databases, real Kafka/ZooKeeper.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "P360_KAFKA_PROCESSES_IT", matches = "true")
class ServiceProcessesIntegrationTests {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
  static List<Process> children = new ArrayList<>();
  static JsonMapper json = JsonMapper.builder().build();
  static int restaurantsPort, productsPort, cartPort;
  static JdbcTemplate db;

  @BeforeAll
  static void start() throws Exception {
    db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    restaurantsPort = start("restaurantes-service");
    productsPort = start("productos-service");
    cartPort = start("carrito-service");
  }

  static int start(String service) throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    var jar =
        Path.of("../../services/" + service + "/target/" + service + "-0.0.1-SNAPSHOT.jar")
            .toAbsolutePath();
    if (!Files.isRegularFile(jar))
      throw new IllegalStateException("Build service jars first: " + service);
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                jar.toString(),
                "--server.port=" + port,
                "--server.address=127.0.0.1",
                "--spring.config.import=",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--p360.kafka.enabled=true",
                "--logging.level.org.apache.kafka=ERROR")
            .redirectErrorStream(true)
            .redirectOutput(Path.of("target", "process-" + service + ".log").toFile())
            .start();
    children.add(process);
    await()
        .ignoreExceptionsInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class)
        .atMost(Duration.ofSeconds(60))
        .until(
            () -> {
              if (!process.isAlive()) throw new IllegalStateException("Service exited: " + service);
              try {
                return call(port, "GET", "/actuator/health", null).statusCode() == 200;
              } catch (Exception e) {
                return false;
              }
            });
    return port;
  }

  @AfterAll
  static void stop() throws Exception {
    for (var process : children) {
      process.destroy();
      if (!process.waitFor(15, TimeUnit.SECONDS))
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
    }
  }

  static HttpResponse<String> call(int port, String method, String path, String body)
      throws Exception {
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(5));
      if (body != null) request.header("Content-Type", "application/json");
      return client.send(
          request
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  void realHttpWritesReachBothIndependentServiceProjections() throws Exception {
    long base = System.currentTimeMillis();
    db.execute(
        "SELECT setval(pg_get_serial_sequence('restaurantes.restaurantes','id'),"
            + base
            + ",false)");
    db.execute(
        "SELECT setval(pg_get_serial_sequence('productos.productos','id'),"
            + (base + 1)
            + ",false)");
    var response =
        call(
            restaurantsPort,
            "POST",
            "/restaurantes",
            "{\"nombre\":\"Kafka process fixture\",\"estado\":\"ABIERTO\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    long restaurant = json.readTree(response.body()).path("id").longValue();
    await()
        .ignoreExceptionsInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class)
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        json.readTree(
                                call(
                                        productsPort,
                                        "GET",
                                        "/productos/restaurante/"
                                            + restaurant
                                            + "/estado-restaurante",
                                        null)
                                    .body())
                            .path("valor").toString())
                    .isEqualTo("\"ABIERTO\""));
    assertThat(
            call(
                    restaurantsPort,
                    "PUT",
                    "/restaurantes/" + restaurant,
                    "{\"nombre\":\"Kafka process fixture\",\"estado\":\"CERRADO\"}")
                .statusCode())
        .isEqualTo(200);
    await()
        .ignoreExceptionsInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class)
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        json.readTree(
                                call(
                                        productsPort,
                                        "GET",
                                        "/productos/restaurante/"
                                            + restaurant
                                            + "/estado-restaurante",
                                        null)
                                    .body())
                            .path("valor").toString())
                    .isEqualTo("\"CERRADO\""));
    var product =
        call(
            productsPort,
            "POST",
            "/productos",
            "{\"restauranteId\":"
                + restaurant
                + ",\"nombre\":\"Kafka product"
                + " fixture\",\"categoria\":\"test\",\"precio\":1000,\"disponible\":true}");
    assertThat(product.statusCode()).isEqualTo(201);
    long id = json.readTree(product.body()).path("id").longValue();
    assertThat(
            json.readTree(call(productsPort, "GET", "/productos/" + id, null).body())
                .path("disponible")
                .booleanValue())
        .isTrue();
    await()
        .ignoreExceptionsInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class)
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        db.queryForObject(
                            "SELECT state FROM carrito.kafka_projection WHERE entity_id=?",
                            String.class,
                            id))
                    .isEqualTo("true"));
    assertThat(
            call(
                    productsPort,
                    "PATCH",
                    "/productos/" + id + "/disponibilidad?disponible=false",
                    null)
                .statusCode())
        .isEqualTo(200);
    await()
        .ignoreExceptionsInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class)
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        db.queryForMap(
                            "SELECT state,version FROM carrito.kafka_projection WHERE entity_id=?",
                            id))
                    .containsEntry("state", "false")
                    .containsEntry("version", 2L));
    assertThat(call(cartPort, "GET", "/carrito/disponibilidad", null).statusCode()).isEqualTo(401);
  }
}
