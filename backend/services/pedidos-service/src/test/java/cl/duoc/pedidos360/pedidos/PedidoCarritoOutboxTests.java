package cl.duoc.pedidos360.pedidos;

import static org.assertj.core.api.Assertions.*;

import cl.duoc.pedidos360.messaging.command.*;
import cl.duoc.pedidos360.pedidos.dto.*;
import cl.duoc.pedidos360.pedidos.messaging.carrito.*;
import cl.duoc.pedidos360.pedidos.security.*;
import cl.duoc.pedidos360.pedidos.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"entra.tenant-id=11111111-1111-1111-1111-111111111111"})
@Import({PostgresTestConfiguration.class, PedidoCarritoOutboxTests.Fixture.class})
class PedidoCarritoOutboxTests {
  static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @TestConfiguration
  static class Fixture {
    @Bean
    CarritoOutboxStore cartStore(JdbcTemplate db, CarritoCommandProperties c, TenantSistema t) {
      return new CarritoOutboxStore(db, c, t);
    }
  }

  @Autowired PedidoService service;
  @Autowired CarritoOutboxStore store;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager manager;

  IdentidadUsuario identity() {
    return new IdentidadUsuario(T, 10L, Set.of(IdentidadUsuario.Rol.CLIENTE));
  }

  CrearPedidoRequest request() {
    return new CrearPedidoRequest(
        20L, "Dirección de prueba", List.of(new LineaPedidoRequest(101L, 2)));
  }

  CarritoSnapshotClient.Snapshot snapshot() {
    return new CarritoSnapshotClient.Snapshot(T, UUID.randomUUID(), 42, 3);
  }

  @Test
  void atomicOrderIntentAndStableLeaseRecovery() {
    var order = service.crearConCarrito(identity(), request(), snapshot());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM pedidos.carrito_vaciado_outbox WHERE pedido_id=?",
                Long.class,
                order.pedidoId()))
        .isEqualTo(1);
    var first = store.claim().getFirst();
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE pedidos.carrito_vaciado_outbox SET payload='{}' WHERE message_id=?",
                    first.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    db.update(
        "UPDATE pedidos.carrito_vaciado_outbox SET lease_until=clock_timestamp()-interval '1"
            + " second' WHERE message_id=?",
        first.id());
    var next = store.claim().getFirst();
    assertThat(next.id()).isEqualTo(first.id());
    assertThat(next.payload()).isEqualTo(first.payload());
    assertThat(store.finish(first, null)).isFalse();
    assertThat(store.finish(next, null)).isTrue();
    assertThat(store.claim()).isEmpty();
  }

  @Test
  void rollbackDoesNotLeaveOrderOrIntent() {
    long before = db.queryForObject("SELECT count(*) FROM pedidos.pedidos", Long.class);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              service.crearConCarrito(identity(), request(), snapshot());
              tx.setRollbackOnly();
            });
    assertThat(db.queryForObject("SELECT count(*) FROM pedidos.pedidos", Long.class))
        .isEqualTo(before);
  }

  @Test
  void httpCreationAndOldBinaryShapeHaveNoIntent() {
    var order = service.crear(identity(), request());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM pedidos.carrito_vaciado_outbox WHERE pedido_id=?",
                Long.class,
                order.pedidoId()))
        .isZero();
  }

  @Test
  void foreignSnapshotFailsBeforeWriting() {
    long before = db.queryForObject("SELECT count(*) FROM pedidos.pedidos", Long.class);
    assertThatThrownBy(
            () ->
                service.crearConCarrito(
                    identity(),
                    request(),
                    new CarritoSnapshotClient.Snapshot(
                        UUID.randomUUID(), UUID.randomUUID(), 42, 3)))
        .isInstanceOf(RuntimeException.class);
    assertThat(db.queryForObject("SELECT count(*) FROM pedidos.pedidos", Long.class))
        .isEqualTo(before);
  }

  @Test
  void postgresRejectsInconsistentInitialIntentAndDuplicateOrder() {
    var order = service.crearConCarrito(identity(), request(), snapshot());
    var rows =
        db.queryForList(
            "SELECT * FROM pedidos.carrito_vaciado_outbox WHERE pedido_id=?", order.pedidoId());
    var row = rows.getFirst();
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO"
                        + " pedidos.carrito_vaciado_outbox(message_id,pedido_id,tenant_id,entra_object_id,carrito_id,expected_version,payload)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    order.pedidoId(),
                    T,
                    row.get("entra_object_id"),
                    42,
                    3,
                    row.get("payload")))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void runtimeRoleCannotChangePayloadOrDeleteIntent() {
    var order = service.crearConCarrito(identity(), request(), snapshot());
    db.execute(
        "DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='cart_runtime_test') THEN"
            + " CREATE ROLE cart_runtime_test; END IF; END $$");
    db.execute("GRANT USAGE ON SCHEMA pedidos TO cart_runtime_test");
    db.execute(
        "GRANT SELECT,INSERT,UPDATE,DELETE ON pedidos.carrito_vaciado_outbox TO cart_runtime_test");
    db.execute("GRANT SELECT ON pedidos.pedidos TO cart_runtime_test");
    var tx = new TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      db.execute("SET LOCAL ROLE cart_runtime_test");
                      db.update(
                          "UPDATE pedidos.carrito_vaciado_outbox SET carrito_id=carrito_id+1 WHERE"
                              + " pedido_id=?",
                          order.pedidoId());
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      db.execute("SET LOCAL ROLE cart_runtime_test");
                      db.update(
                          "DELETE FROM pedidos.carrito_vaciado_outbox WHERE pedido_id=?",
                          order.pedidoId());
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    db.execute("REVOKE ALL ON pedidos.carrito_vaciado_outbox FROM cart_runtime_test");
    db.execute("REVOKE ALL ON pedidos.pedidos FROM cart_runtime_test");
    db.execute("REVOKE USAGE ON SCHEMA pedidos FROM cart_runtime_test");
    db.execute("DROP ROLE cart_runtime_test");
  }
}
