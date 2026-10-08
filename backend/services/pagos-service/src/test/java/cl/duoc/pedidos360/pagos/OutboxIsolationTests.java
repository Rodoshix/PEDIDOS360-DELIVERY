package cl.duoc.pedidos360.pagos;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import cl.duoc.pedidos360.pagos.entity.*;
import cl.duoc.pedidos360.pagos.messaging.*;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real PostgreSQL: CAS settlement and SQL writers without owner/DDL privileges. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Import({PostgresTestConfiguration.class,PedidosStubConfiguration.class})
class OutboxIsolationTests {
    static final UUID T=UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER=UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    @Autowired OutboxStore store;
    @Autowired PagoRepository pagos;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.testcontainers.postgresql.PostgreSQLContainer postgres;
    @Autowired JsonMapper json;

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE pagos.tenant_reconciliation_audit,pagos.confirmacion_outbox,pagos.pagos RESTART IDENTITY CASCADE");
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='outbox_runtime') THEN CREATE ROLE outbox_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA pagos TO outbox_runtime");
        jdbc.execute("GRANT SELECT ON pagos.pagos TO outbox_runtime");
        jdbc.execute("GRANT SELECT,UPDATE ON pagos.confirmacion_outbox TO outbox_runtime");
    }
    @AfterEach void clearDisposableFixtures() {
        jdbc.execute("TRUNCATE pagos.tenant_reconciliation_audit,pagos.confirmacion_outbox,pagos.pagos RESTART IDENTITY CASCADE");
    }
    Pago payment(UUID tenant,long pedido) {
        return pagos.saveAndFlush(new Pago(tenant,pedido,10L,1L,"CLP",MetodoPago.EFECTIVO,EstadoPago.PENDIENTE,"key-"+pedido));
    }
    OutboxStore.Claim pending(UUID tenant,long pedido) {
        var pago=payment(tenant,pedido);
        var command=ConfirmarPedidoPorPago.crear(pedido,pago.getId());
        String payload=json.writeValueAsString(command);
        UUID token=UUID.randomUUID();
        jdbc.update("INSERT INTO pagos.confirmacion_outbox(message_id,pago_id,payload,estado,next_attempt_at,lease_token,lease_until) VALUES(?,?,?,'PENDING',now(),?,now()+interval '1 hour')",command.messageId(),pago.getId(),payload,token);
        return new OutboxStore.Claim(command.messageId(),pago.getId(),tenant,token,payload);
    }
    OutboxStore.Claim leased(UUID tenant,long pedido) {
        var c=pending(tenant,pedido);
        jdbc.update("UPDATE pagos.confirmacion_outbox SET estado='IN_FLIGHT' WHERE message_id=?",c.messageId());
        return c;
    }
    String state(OutboxStore.Claim c) {
        return jdbc.queryForObject("SELECT estado FROM pagos.confirmacion_outbox WHERE message_id=?",String.class,c.messageId());
    }
    Connection runtime() throws SQLException {
        // Dedicated connection: session SET ROLE must never leak into Hikari's pool.
        var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        try { c.createStatement().execute("SET ROLE outbox_runtime");return c; }
        catch(SQLException e) { c.close();throw e; }
    }
    void immutable(String sql) throws Exception {
        try(var c=runtime();var s=c.createStatement()) {
            assertThatThrownBy(()->s.execute(sql)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
            s.execute("RESET ROLE");
        }
    }
    @Test void validClaimAndFinalizationPreserveAssociation() {
        var original=pending(T,500);
        var c=store.claim().getFirst();
        assertThat(c.pagoId()).isEqualTo(original.pagoId());
        assertThat(c.tenantId()).isEqualTo(T);
        assertThat(c.payload()).isEqualTo(original.payload());
        assertThat(store.finish(c,null)).isTrue();
        assertThat(state(c)).isEqualTo("PUBLISHED");
        assertThat(store.finish(c,null)).isFalse();
    }
    @Test void foreignClaimExcludedAndFinishBlockDenied() {
        var c=leased(OTHER,500);
        assertThat(store.claim()).isEmpty();
        assertThat(store.finish(c,null)).isFalse();
        assertThat(store.block(c)).isFalse();
        var forged=new OutboxStore.Claim(c.messageId(),c.pagoId(),T,c.token(),c.payload());
        assertThat(store.finish(forged,null)).isFalse();
        assertThat(store.block(forged)).isFalse();
        assertThat(state(c)).isEqualTo("IN_FLIGHT");
    }
    void retained(String origin) {
        var c=leased(T,500);
        // Simulates a pre-migration aggregate, solely in this disposable fixture.
        jdbc.execute("ALTER TABLE pagos.pagos DISABLE TRIGGER guard_tenant_origin");
        try { jdbc.update("UPDATE pagos.pagos SET tenant_id=?::uuid,tenant_origin=? WHERE id=?",origin.equals("UNKNOWN")?null:T.toString(),origin,c.pagoId()); }
        finally { jdbc.execute("ALTER TABLE pagos.pagos ENABLE TRIGGER guard_tenant_origin"); }
        assertThat(store.claim()).isEmpty();
        assertThat(store.finish(c,null)).isFalse();
        assertThat(store.finish(c,"TRANSPORT")).isFalse();
        assertThat(store.block(c)).isFalse();
        assertThat(state(c)).isEqualTo("IN_FLIGHT");
    }
    @Test void unknownCannotFinishOrBlock() { retained("UNKNOWN"); }
    @Test void reconciledCannotFinishOrBlock() { retained("RECONCILED_LEGACY"); }
    @Test void staleLeaseCannotFinishOrBlockNewLease() {
        var c=leased(T,500); UUID next=UUID.randomUUID();
        jdbc.update("UPDATE pagos.confirmacion_outbox SET lease_token=? WHERE message_id=?",next,c.messageId());
        assertThat(store.finish(c,null)).isFalse();assertThat(store.block(c)).isFalse();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM pagos.confirmacion_outbox",UUID.class)).isEqualTo(next);
        assertThat(state(c)).isEqualTo("IN_FLIGHT");
    }
    @Test void expiredLeaseRetainsWorkUntilRecoveryWithStableIdentity() {
        var c=leased(T,500);
        jdbc.update("UPDATE pagos.confirmacion_outbox SET lease_until=now()-interval '1 second'");
        assertThat(store.finish(c,null)).isFalse();assertThat(store.block(c)).isFalse();
        var recovered=store.claim().getFirst();
        assertThat(recovered.messageId()).isEqualTo(c.messageId());
        assertThat(recovered.payload()).isEqualTo(c.payload());
        assertThat(recovered.token()).isNotEqualTo(c.token());
        assertThat(store.finish(recovered,null)).isTrue();
    }
    @Test void incorrectAssociationPayloadOrClaimTenantAffectsZeroRows() {
        var c=leased(T,500);var other=payment(T,600);
        for(var invalid:List.of(
            new OutboxStore.Claim(c.messageId(),other.getId(),T,c.token(),c.payload()),
            new OutboxStore.Claim(c.messageId(),c.pagoId(),OTHER,c.token(),c.payload()),
            new OutboxStore.Claim(c.messageId(),c.pagoId(),T,c.token(),"{}"))) {
            assertThat(store.finish(invalid,null)).isFalse();assertThat(store.block(invalid)).isFalse();
        }
        assertThat(state(c)).isEqualTo("IN_FLIGHT");
    }
    @Test void runtimeCannotChangePaymentAssociationBeforeFinish() throws Exception {
        var c=leased(T,500);var other=payment(OTHER,600);
        immutable("UPDATE pagos.confirmacion_outbox SET pago_id="+other.getId()+" WHERE message_id='"+c.messageId()+"'");
        assertThat(store.finish(c,null)).isTrue();
        assertThat(jdbc.queryForObject("SELECT pago_id FROM pagos.confirmacion_outbox",Long.class)).isEqualTo(c.pagoId());
    }
    @Test void runtimeCannotChangeMessageUuid() throws Exception {
        var c=leased(T,500);
        immutable("UPDATE pagos.confirmacion_outbox SET message_id=gen_random_uuid()");
        assertThat(store.block(c)).isTrue();assertThat(state(c)).isEqualTo("BLOCKED");
    }
    @Test void runtimeCannotChangeOriginalPayloadButCanUpdateMetadata() throws Exception {
        var c=leased(T,500);
        immutable("UPDATE pagos.confirmacion_outbox SET payload='{}'");
        try(var conn=runtime();var sql=conn.createStatement()) {
            sql.execute("UPDATE pagos.confirmacion_outbox SET attempts=attempts+1,next_attempt_at=now()");sql.execute("RESET ROLE");
        }
        assertThat(store.finish(c,"RETURNED")).isTrue();assertThat(state(c)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT payload FROM pagos.confirmacion_outbox",String.class)).isEqualTo(c.payload());
    }
    @Test void associationCannotChangeWhileFinishWaitsForConcurrentWriter() throws Exception {
        var claim=leased(T,500);var other=payment(OTHER,600);
        try(var conn=runtime();var sql=conn.createStatement();var executor=Executors.newSingleThreadExecutor()) {
            conn.setAutoCommit(false);
            sql.execute("SELECT message_id FROM pagos.confirmacion_outbox FOR UPDATE");
            Savepoint point=conn.setSavepoint();
            assertThatThrownBy(()->sql.execute("UPDATE pagos.confirmacion_outbox SET pago_id="+other.getId()))
                .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
            conn.rollback(point); // Keep the row lock acquired before this savepoint.
            var finish=executor.submit(()->store.finish(claim,null));
            try {
                await().atMost(java.time.Duration.ofSeconds(5)).until(()->jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'UPDATE pagos.confirmacion_outbox%'",Integer.class)>0);
                assertThat(finish.isDone()).isFalse();
            } finally { conn.commit(); }
            assertThat(finish.get(5,TimeUnit.SECONDS)).isTrue();
            sql.execute("RESET ROLE");
        }
        assertThat(jdbc.queryForObject("SELECT pago_id FROM pagos.confirmacion_outbox",Long.class)).isEqualTo(claim.pagoId());
        assertThat(state(claim)).isEqualTo("PUBLISHED");
    }
    @Test void competingPublishersOnlyOneCanSettleSameLease() throws Exception {
        var c=leased(T,500);var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()->{barrier.await(5,TimeUnit.SECONDS);return store.finish(c,null);};
            var a=executor.submit(task);var b=executor.submit(task);
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void leaseExpiringWhileFinishWaitsCannotBeSettled() throws Exception {
        var c=leased(T,500);
        try(var conn=runtime();var sql=conn.createStatement();var executor=Executors.newSingleThreadExecutor()) {
            conn.setAutoCommit(false);
            sql.execute("UPDATE pagos.confirmacion_outbox SET lease_until=clock_timestamp()+interval '2 seconds'");
            var future=executor.submit(()->store.finish(c,null));
            try {
                await().atMost(java.time.Duration.ofSeconds(5)).until(()->jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'UPDATE pagos.confirmacion_outbox%'",Integer.class)>0);
                sql.execute("SELECT pg_sleep(2.1)");
            } finally {conn.commit();}
            assertThat(future.get(5,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(state(c)).isEqualTo("IN_FLIGHT");
        assertThat(store.claim()).hasSize(1);
    }
    @Test void simultaneousClaimsUseSkipLockedAndDifferentLeases() throws Exception {
        pending(T,500);pending(T,600);var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<OutboxStore.Claim> task=()->{barrier.await(5,TimeUnit.SECONDS);return store.claim().getFirst();};
            var a=executor.submit(task);var b=executor.submit(task);
            var first=a.get(5,TimeUnit.SECONDS);var second=b.get(5,TimeUnit.SECONDS);
            assertThat(first.messageId()).isNotEqualTo(second.messageId());
            assertThat(first.token()).isNotEqualTo(second.token());
            assertThat(store.finish(first,null)).isTrue();assertThat(store.block(second)).isTrue();
        }
    }
}
