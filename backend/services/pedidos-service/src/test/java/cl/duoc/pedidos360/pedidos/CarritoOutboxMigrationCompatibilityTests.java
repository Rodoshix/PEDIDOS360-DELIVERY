package cl.duoc.pedidos360.pedidos;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CarritoOutboxMigrationCompatibilityTests {
  @Test
  void oldOrdersReceiveNoIntentAndUnknownCannotBeLinked() throws Exception {
    try (var pg = new PostgreSQLContainer("postgres:17-alpine")) {
      pg.start();
      Flyway.configure()
          .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
          .schemas("pedidos")
          .defaultSchema("pedidos")
          .target("1")
          .load()
          .migrate();
      try (var connection =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var sql = connection.createStatement()) {
        sql.execute(
            "INSERT INTO"
                + " pedidos.pedidos(usuario_id,restaurante_id,direccion_entrega,estado,total,moneda)"
                + " VALUES(10,20,'Histórico','CREADO',200,'CLP')");
        Flyway.configure()
            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .schemas("pedidos")
            .defaultSchema("pedidos")
            .load()
            .migrate();
        try (var row = sql.executeQuery("SELECT count(*) FROM pedidos.carrito_vaciado_outbox")) {
          row.next();
          assertThat(row.getLong(1)).isZero();
        }
        assertThatThrownBy(
                () ->
                    sql.execute(
                        "INSERT INTO"
                            + " pedidos.carrito_vaciado_outbox(message_id,pedido_id,tenant_id,entra_object_id,carrito_id,expected_version,payload)"
                            + " VALUES('33333333-3333-3333-3333-333333333333',1,'11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222',1,0,'{}')"))
            .isInstanceOf(SQLException.class);
        sql.execute(
            "INSERT INTO"
                + " pedidos.pedidos(usuario_id,restaurante_id,direccion_entrega,estado,total,moneda,tenant_id,tenant_origin)"
                + " VALUES(10,20,'Forma"
                + " anterior','CREADO',200,'CLP','11111111-1111-1111-1111-111111111111','AUTHENTICATED_NEW')");
        try (var row = sql.executeQuery("SELECT count(*) FROM pedidos.carrito_vaciado_outbox")) {
          row.next();
          assertThat(row.getLong(1)).isZero();
        }
      }
    }
  }
}
