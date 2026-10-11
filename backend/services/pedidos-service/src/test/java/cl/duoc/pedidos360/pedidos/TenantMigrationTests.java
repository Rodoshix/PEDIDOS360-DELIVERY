package cl.duoc.pedidos360.pedidos;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL, disposable container; seeded using the prior Flyway schema. */
class TenantMigrationTests {
 @Test void upgradeRetainsUnknownGuardsWritersAndAllowsOnlyAuditedReconciliation() throws Exception {
  try(var pg=new PostgreSQLContainer("postgres:17-alpine")) {
   pg.start();
   var old=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())
      .schemas("pedidos").defaultSchema("pedidos").locations("classpath:db/migration").target("1").load();
   old.migrate();
   try(var conn=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()); var sql=conn.createStatement()) {
    sql.execute("INSERT INTO pedidos.pedidos(usuario_id,restaurante_id,direccion_entrega,estado,total,moneda) VALUES(10,20,'Prueba','CREADO',6990,'CLP')");
    Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).schemas("pedidos")
        .defaultSchema("pedidos").locations("classpath:db/migration").load().migrate();
    try(var row=sql.executeQuery("SELECT tenant_id,tenant_origin FROM pedidos.pedidos WHERE id=1")) {
     assertThat(row.next()).isTrue(); assertThat(row.getObject(1)).isNull(); assertThat(row.getString(2)).isEqualTo("UNKNOWN");
    }
    assertThatThrownBy(()->sql.execute("INSERT INTO pedidos.pedidos(usuario_id,restaurante_id,direccion_entrega,estado,total,moneda) VALUES(10,20,'Prueba','CREADO',6990,'CLP')")).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("UPDATE pedidos.pedidos SET tenant_id='11111111-1111-1111-1111-111111111111',tenant_origin='AUTHENTICATED_NEW' WHERE id=1")).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("SELECT pedidos.reconcile_tenant(1,NULL,'11111111-1111-1111-1111-111111111111','"+"a".repeat(64)+"')")).isInstanceOf(SQLException.class);
    String reconcile="SELECT pedidos.reconcile_tenant(1,0,'11111111-1111-1111-1111-111111111111','"+"a".repeat(64)+"')";
    sql.execute(reconcile); sql.execute(reconcile);
    try(var row=sql.executeQuery("SELECT count(*) FROM pedidos.tenant_reconciliation_audit")) { row.next(); assertThat(row.getInt(1)).isEqualTo(1); }
    assertThatThrownBy(()->sql.execute("UPDATE pedidos.pedidos SET estado='CONFIRMADO' WHERE id=1")).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("UPDATE pedidos.pedidos SET tenant_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' WHERE id=1")).isInstanceOf(SQLException.class);
    sql.execute("INSERT INTO pedidos.pedidos(usuario_id,restaurante_id,direccion_entrega,estado,total,moneda,tenant_id,tenant_origin) VALUES(10,20,'Prueba','CREADO',6990,'CLP','11111111-1111-1111-1111-111111111111','AUTHENTICATED_NEW')");
    assertThatThrownBy(()->sql.execute("UPDATE pedidos.pedidos SET tenant_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' WHERE tenant_origin='AUTHENTICATED_NEW'")).isInstanceOf(SQLException.class);
    sql.execute("CREATE ROLE tenant_runtime NOLOGIN");
    sql.execute("GRANT USAGE ON SCHEMA pedidos TO tenant_runtime");
    sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON pedidos.pedidos TO tenant_runtime");
    sql.execute("SET ROLE tenant_runtime");
    assertThatThrownBy(()->sql.execute(reconcile)).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("INSERT INTO pedidos.tenant_reconciliation_audit(resource_id,previous_version,tenant_id,evidence_sha256,operator_name) VALUES(999,0,'11111111-1111-1111-1111-111111111111','"+"a".repeat(64)+"','forged')")).isInstanceOf(SQLException.class);
    sql.execute("RESET ROLE");
   }
  }
 }
}
