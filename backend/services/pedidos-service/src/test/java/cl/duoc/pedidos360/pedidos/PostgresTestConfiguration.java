package cl.duoc.pedidos360.pedidos;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {
    // Fixture exclusiva de tests: no existe precio fijo en el código productivo.
    @Bean
    @org.springframework.context.annotation.Primary
    cl.duoc.pedidos360.pedidos.service.CatalogoPedidos catalogoFixture() {
        return (producto, restaurante) -> 6990L;
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }
}
