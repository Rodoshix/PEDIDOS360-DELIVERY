package cl.duoc.pedidos360.restaurantes;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
    named = "P360_KAFKA_IT",
    matches = "true")
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    properties = {
      "p360.kafka.enabled=true",
      "p360.kafka.snapshot-run=true",
      "spring.config.import="
    })
class KafkaProducerIntegrationTests {
  @Container static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", db::getJdbcUrl);
    p.add("spring.datasource.username", db::getUsername);
    p.add("spring.datasource.password", db::getPassword);
  }

  @Autowired cl.duoc.pedidos360.restaurantes.service.RestauranteService service;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void everyRealWritePathRecordsOnlyTransitions() {
    var request =
        new cl.duoc.pedidos360.restaurantes.dto.RestauranteDto(
            null,
            "Kafka fixture",
            "",
            "",
            cl.duoc.pedidos360.restaurantes.entity.EstadoRestaurante.ABIERTO);
    long id = service.crear(request).getId();
    service.actualizar(id, request);
    service.desactivar(id);
    service.desactivar(id);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM restaurantes.kafka_state WHERE entity_id=?", Long.class, id))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM restaurantes.kafka_outbox WHERE entity_id=?", Long.class, id))
        .isEqualTo(2);
  }

  @Test
  void explicitInitialSnapshotCoversExistingSourceRows() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM restaurantes.kafka_outbox WHERE payload LIKE '%SNAPSHOT%'",
                Long.class))
        .isPositive();
  }
}
