package cl.duoc.pedidos360.pagos;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL, disposable container; seeded using the prior Flyway schema. */
class TenantMigrationTests {
 @Test void outboxUpgradeRetainsOriginalCommandAndGuardsRuntimeWithSeparatedMigrationOwner() throws Exception {
  try(var pg=new PostgreSQLContainer("postgres:17-alpine")) {
   pg.start();
   try(var admin=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var sql=admin.createStatement()) {
    sql.execute("CREATE ROLE migration_owner LOGIN PASSWORD 'test-migration-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
    sql.execute("CREATE ROLE runtime_worker LOGIN PASSWORD 'test-runtime-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
    sql.execute("GRANT CREATE ON DATABASE "+pg.getDatabaseName()+" TO migration_owner");
   }
   var old=Flyway.configure().dataSource(pg.getJdbcUrl(),"migration_owner","test-migration-only")
      .schemas("pagos").defaultSchema("pagos").locations("classpath:db/migration").target("3").load();
   old.migrate();
   var command=cl.duoc.pedidos360.pagos.messaging.ConfirmarPedidoPorPago.crear(500,1);
   String payload=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(command);
   try(var migration=DriverManager.getConnection(pg.getJdbcUrl(),"migration_owner","test-migration-only");var sql=migration.createStatement()) {
    sql.execute("INSERT INTO pagos.pagos(pedido_id,usuario_id,monto,moneda,metodo,estado,clave_idempotencia) VALUES(500,10,1,'CLP','EFECTIVO','PENDIENTE','old')");
    try(var insert=migration.prepareStatement("INSERT INTO pagos.confirmacion_outbox(message_id,pago_id,payload,estado,next_attempt_at,lease_token,lease_until) VALUES(?,1,?,'IN_FLIGHT',now(),gen_random_uuid(),now()+interval '1 hour')")) {
     insert.setObject(1,command.messageId());insert.setString(2,payload);insert.executeUpdate();
    }
    Flyway.configure().dataSource(pg.getJdbcUrl(),"migration_owner","test-migration-only").schemas("pagos")
        .defaultSchema("pagos").locations("classpath:db/migration").load().migrate();
    try(var row=sql.executeQuery("SELECT proowner::regrole::text,prosecdef FROM pg_proc WHERE oid='pagos.reconcile_tenant(bigint,bigint,uuid,text)'::regprocedure")) {
     row.next();assertThat(row.getString(1)).isEqualTo("migration_owner");assertThat(row.getBoolean(2)).isTrue();
    }
    sql.execute("GRANT USAGE ON SCHEMA pagos TO runtime_worker");
    sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON pagos.pagos,pagos.confirmacion_outbox TO runtime_worker");
    sql.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA pagos TO runtime_worker");
   }
   try(var runtime=DriverManager.getConnection(pg.getJdbcUrl(),"runtime_worker","test-runtime-only");var sql=runtime.createStatement()) {
    assertThatThrownBy(()->sql.execute("SELECT pagos.reconcile_tenant(1,0,'11111111-1111-1111-1111-111111111111','"+"a".repeat(64)+"')")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
    assertThatThrownBy(()->sql.execute("INSERT INTO pagos.tenant_reconciliation_audit VALUES(1,0,'11111111-1111-1111-1111-111111111111','"+"a".repeat(64)+"','forged',now())")).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("ALTER TABLE pagos.confirmacion_outbox DISABLE TRIGGER guard_outbox_identity")).isInstanceOf(SQLException.class);
    assertThatThrownBy(()->sql.execute("UPDATE pagos.confirmacion_outbox SET estado='PUBLISHED'")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
    sql.execute("INSERT INTO pagos.pagos(pedido_id,usuario_id,monto,moneda,metodo,estado,clave_idempotencia,tenant_id,tenant_origin) VALUES(600,10,1,'CLP','EFECTIVO','PENDIENTE','new','11111111-1111-1111-1111-111111111111','AUTHENTICATED_NEW')");
    sql.execute("INSERT INTO pagos.confirmacion_outbox(message_id,pago_id,payload,estado,next_attempt_at) VALUES(gen_random_uuid(),2,'new-payload','PENDING',now())");
    for(String update:java.util.List.of("pago_id=2","message_id=gen_random_uuid()","payload='altered'")) {
     assertThatThrownBy(()->sql.execute("UPDATE pagos.confirmacion_outbox SET "+update+" WHERE pago_id=1")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
    }
    sql.execute("UPDATE pagos.confirmacion_outbox SET estado='IN_FLIGHT',attempts=attempts+1,lease_token=gen_random_uuid(),lease_until=now()+interval '1 hour' WHERE pago_id=2");
    sql.execute("UPDATE pagos.confirmacion_outbox SET estado='PUBLISHED',published_at=now(),lease_token=NULL,lease_until=NULL WHERE pago_id=2");
    try(var row=sql.executeQuery("SELECT o.payload,o.message_id,p.tenant_id,p.tenant_origin,o.estado FROM pagos.confirmacion_outbox o JOIN pagos.pagos p ON p.id=o.pago_id WHERE pago_id=1")) {
     row.next();assertThat(row.getString(1)).isEqualTo(payload);assertThat(row.getObject(2)).isEqualTo(command.messageId());
     assertThat(row.getObject(3)).isNull();assertThat(row.getString(4)).isEqualTo("UNKNOWN");assertThat(row.getString(5)).isEqualTo("IN_FLIGHT");
    }
   }
  }
 }
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
