package cl.duoc.pedidos360.carrito;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Disposable prior schema upgraded to V2; no project database involved. */
class CarritoMigrationCompatibilityTests {
  @Test
  void additiveMigrationPreservesPriorCartAndHasNoReceipts() throws Exception {
    try (var pg = new PostgreSQLContainer("postgres:17-alpine")) {
      pg.start();
      Flyway.configure()
          .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
          .schemas("carrito")
          .defaultSchema("carrito")
          .target("1")
          .load()
          .migrate();
      try (var connection =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var sql = connection.createStatement()) {
        sql.execute(
            "INSERT INTO"
                + " carrito.carritos(tenant_id,entra_object_id,restaurante_id,version,revision_contenido,creado_en,actualizado_en)"
                + " VALUES('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222',20,7,7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        sql.execute(
            "INSERT INTO"
                + " carrito.lineas_carrito(carrito_id,producto_id,nombre_producto,precio_unitario,cantidad)"
                + " VALUES(1,101,'Anterior',100,2)");
        Flyway.configure()
            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .schemas("carrito")
            .defaultSchema("carrito")
            .load()
            .migrate();
        try (var row =
            sql.executeQuery(
                "SELECT c.version,l.cantidad,(SELECT count(*) FROM carrito.vaciado_por_pedido) FROM"
                    + " carrito.carritos c JOIN carrito.lineas_carrito l ON l.carrito_id=c.id WHERE"
                    + " c.id=1")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getLong(1)).isEqualTo(7);
          assertThat(row.getInt(2)).isEqualTo(2);
          assertThat(row.getInt(3)).isZero();
        }
        sql.execute(
            "UPDATE carrito.carritos SET version=version+1,revision_contenido=revision_contenido+1"
                + " WHERE id=1");
      }
    }
  }
}
