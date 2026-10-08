package cl.duoc.pedidos360.pagos;
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
      .schemas("pagos").defaultSchema("pagos").locations("classpath:db/migration").target("3").load();
   old.migrate();
   try(var conn=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()); var sql=conn.createStatement()) {
    sql.execute("INSERT INTO pagos.pagos(pedido_id,usuario_id,monto,moneda,metodo,estado,clave_idempotencia) VALUES(500,10,13980,'CLP','EFECTIVO','PENDIENTE','historic')");
    Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).schemas("pagos")
        .defaultSchema("pagos").locations("classpath:db/migration").load().migrate();
    try(var row=sql.executeQuery("SELECT tenant_id,tenant_origin FROM pagos.pagos WHERE id=1")) {
     assertThat(row.next()).isTrue(); assertThat(row.getObject(1)).isNull(); assertThat(row.getString(2)).isEqualTo("UNKNOWN");
    }
    assertThatThrownBy(()->sql.execute("INSERT INTO pagos.pagos(pedido_id,usuario_id,monto,moneda,metodo,estado,clave_idempotencia) VALUES(600,10,13980,'CLP','EFECTIVO','PENDIENTE','new')")).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("UPDATE pagos.pagos SET tenant_id='11111111-1111-1111-1111-111111111111',tenant_origin='AUTHENTICATED_NEW' WHERE id=1")).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("SELECT pagos.reconcile_tenant(1,NULL,'11111111-1111-1111-1111-111111111111','"+"a".repeat(64)+"')")).isInstanceOf(SQLException.class);
    String reconcile="SELECT pagos.reconcile_tenant(1,0,'11111111-1111-1111-1111-111111111111','"+"a".repeat(64)+"')";
    sql.execute(reconcile); sql.execute(reconcile);
    try(var row=sql.executeQuery("SELECT count(*) FROM pagos.tenant_reconciliation_audit")) { row.next(); assertThat(row.getInt(1)).isEqualTo(1); }
    assertThatThrownBy(()->sql.execute("UPDATE pagos.pagos SET estado='APROBADO' WHERE id=1")).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("UPDATE pagos.pagos SET tenant_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' WHERE id=1")).isInstanceOf(SQLException.class);
    sql.execute("INSERT INTO pagos.pagos(pedido_id,usuario_id,monto,moneda,metodo,estado,clave_idempotencia,tenant_id,tenant_origin) VALUES(600,10,13980,'CLP','EFECTIVO','PENDIENTE','new','11111111-1111-1111-1111-111111111111','AUTHENTICATED_NEW')");
    assertThatThrownBy(()->sql.execute("UPDATE pagos.pagos SET tenant_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' WHERE tenant_origin='AUTHENTICATED_NEW'")).isInstanceOf(SQLException.class);
    sql.execute("CREATE ROLE tenant_runtime NOLOGIN");
    sql.execute("GRANT USAGE ON SCHEMA pagos TO tenant_runtime");
    sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON pagos.pagos TO tenant_runtime");
    sql.execute("SET ROLE tenant_runtime");
    assertThatThrownBy(()->sql.execute(reconcile)).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("INSERT INTO pagos.tenant_reconciliation_audit(resource_id,previous_version,tenant_id,evidence_sha256,operator_name) VALUES(999,0,'11111111-1111-1111-1111-111111111111','"+"a".repeat(64)+"','forged')")).isInstanceOf(SQLException.class);
    sql.execute("RESET ROLE");
   }
  }
 }
}
