package cl.duoc.pedidos360.pagos;

import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.*;
import cl.duoc.pedidos360.pagos.messaging.*;
import cl.duoc.pedidos360.pagos.entity.*;
import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario;
import cl.duoc.pedidos360.pagos.security.IdentidadUsuario.Rol;
import cl.duoc.pedidos360.pagos.service.PagoService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "pedidos360.messaging.coordination-mode=RABBITMQ",
    "pedidos360.messaging.dispatch-interval-ms=3600000",
    "pedidos360.messaging.confirm-timeout=1s", "spring.rabbitmq.virtual-host=/"})
@Import({PostgresTestConfiguration.class,PedidosStubConfiguration.class,RabbitCoreTests.Broker.class})
class RabbitCoreTests {
    @TestConfiguration(proxyBeanMethods=false)
    static class Broker {
        @Bean @ServiceConnection RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4.1-management-alpine");
        }
    }
    static final IdentidadUsuario CLIENTE=new IdentidadUsuario(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), 10L,Set.of(Rol.CLIENTE));
    @Autowired PagoService service;
    @Autowired PagoRepository pagos;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxStore store;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired PagoConfirmacionPublisher publisher;
    @Autowired PedidosClientStub pedidos;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitProperties properties;
    @Autowired RabbitMQContainer broker;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JsonMapper json;
    RabbitAdmin admin;

    @BeforeEach void reset() {
        outbox.deleteAll(); pagos.deleteAll(); pedidos.reiniciar();
        admin=new RabbitAdmin(rabbit);
        admin.declareExchange(new DirectExchange(properties.exchanges().commands()));
        admin.deleteQueue(properties.queues().confirmacion());
        admin.declareQueue(new Queue(properties.queues().confirmacion(),true));
        admin.declareBinding(new Binding(properties.queues().confirmacion(),Binding.DestinationType.QUEUE,
            properties.exchanges().commands(),properties.routingKeys().confirmar(),null));
    }
    void registrar(MetodoPago method) {
        service.registrar(CLIENTE,"stable-key",new CrearPagoRequest(500L,method));
    }
    void readyAgain() {
        jdbc.update("UPDATE pagos.confirmacion_outbox SET next_attempt_at=now()-interval '1 second'");
    }
    @Test void tarjetaCommitPublicacionRealYPayloadExacto() {
        registrar(MetodoPago.TARJETA);
        assertThat(pagos.findAll().getFirst().getEstado()).isEqualTo(EstadoPago.APROBADO);
        assertThat(outbox.count()).isEqualTo(1);
        dispatcher.dispatch();
        var row=outbox.findAll().getFirst();
        assertThat(row.getEstado()).isEqualTo(OutboxMessage.State.PUBLISHED);
        var msg=rabbit.receive(properties.queues().confirmacion(),3000);
        assertThat(msg).isNotNull();
        var payload=json.readTree(msg.getBody());
        assertThat(payload.size()).isEqualTo(6);
        assertThat(payload.path("messageId").stringValue()).isEqualTo(row.getMessageId().toString());
        assertThat(payload.path("type").stringValue()).isEqualTo("ConfirmarPedidoPorPago");
        assertThat(payload.path("version").intValue()).isEqualTo(1);
        assertThat(payload.path("pedidoId").longValue()).isEqualTo(500);
        assertThat(payload.path("pagoId").longValue()).isEqualTo(pagos.findAll().getFirst().getId());
        assertThat(Instant.parse(payload.path("occurredAt").stringValue())).isBeforeOrEqualTo(Instant.now());
        assertThat(msg.getMessageProperties().getMessageId()).isEqualTo(row.getMessageId().toString());
        assertThat(msg.getMessageProperties().getAppId()).isEqualTo("pagos-service");
        assertThat(msg.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(msg.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(msg.getMessageProperties().getRetryCount()).isZero();
        assertThat(pagos.findAll().getFirst().isPedidoConfirmado()).isFalse();
        assertThat(service.reconciliarConfirmacionesPendientes()).isZero();
        assertThat(pedidos.confirmaciones()).isZero();
    }
    @Test void efectivoPendienteAprobarYReintentarNoDuplicaIntencion() {
        registrar(MetodoPago.EFECTIVO);
        assertThat(pagos.findAll().getFirst().getEstado()).isEqualTo(EstadoPago.PENDIENTE);
        UUID id=outbox.findAll().getFirst().getMessageId();
        registrar(MetodoPago.EFECTIVO);
        service.aprobar(new IdentidadUsuario(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), 1L,Set.of(Rol.ADMIN)),pagos.findAll().getFirst().getId());
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(outbox.findAll().getFirst().getMessageId()).isEqualTo(id);
    }
    @Test void dosReintentosConcurrentesCompartenPagoYComando() throws Exception {
        var barrier=new CyclicBarrier(2);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<Long> task=()->{ barrier.await(); return service.registrar(CLIENTE,"same-key",
                new CrearPagoRequest(500L,MetodoPago.TARJETA)).pagoId(); };
            var first=executor.submit(task); var second=executor.submit(task);
            assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo(second.get(10,TimeUnit.SECONDS));
        }
        assertThat(pagos.count()).isEqualTo(1); assertThat(outbox.count()).isEqualTo(1);
    }
    @Test void errorAlInsertarOutboxReviertePago() {
        jdbc.execute("ALTER TABLE pagos.confirmacion_outbox ADD CONSTRAINT test_reject CHECK (pago_id < 0)");
        try {
            assertThatThrownBy(()->registrar(MetodoPago.TARJETA))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(pagos.count()).isZero(); assertThat(outbox.count()).isZero();
        } finally { jdbc.execute("ALTER TABLE pagos.confirmacion_outbox DROP CONSTRAINT test_reject"); }
    }
    @Test void rollbackDespuesDeGuardarAmbosNoDejaHuerfanos() {
        var tx=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            var pago=pagos.saveAndFlush(new Pago(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), 500L,10L,13980L,"CLP",MetodoPago.TARJETA,EstadoPago.APROBADO,"rollback"));
            store.crear(pago); throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(pagos.count()).isZero(); assertThat(outbox.count()).isZero();
    }
    @Test void returnSinBindingNoMarcaPublishedYRecupera() {
        registrar(MetodoPago.TARJETA);
        admin.removeBinding(new Binding(properties.queues().confirmacion(),Binding.DestinationType.QUEUE,
            properties.exchanges().commands(),properties.routingKeys().confirmar(),null));
        dispatcher.dispatch();
        assertThat(outbox.findAll().getFirst().getEstado()).isEqualTo(OutboxMessage.State.PENDING);
        admin.declareBinding(new Binding(properties.queues().confirmacion(),Binding.DestinationType.QUEUE,
            properties.exchanges().commands(),properties.routingKeys().confirmar(),null));
        readyAgain(); dispatcher.dispatch();
        assertThat(outbox.findAll().getFirst().getEstado()).isEqualTo(OutboxMessage.State.PUBLISHED);
    }
    @Test void brokerCaidoNoBloqueaRegistroYRecuperaMismoMessageId() throws Exception {
        broker.execInContainer("rabbitmqctl","stop_app");
        try {
            registrar(MetodoPago.TARJETA);
            dispatcher.dispatch();
            assertThat(outbox.findAll().getFirst().getEstado()).isEqualTo(OutboxMessage.State.PENDING);
            assertThat(pagos.count()).isEqualTo(1);
        } finally { broker.execInContainer("rabbitmqctl","start_app"); }
        UUID id=outbox.findAll().getFirst().getMessageId();
        readyAgain(); dispatcher.dispatch();
        assertThat(outbox.findById(id).orElseThrow().getEstado()).isEqualTo(OutboxMessage.State.PUBLISHED);
    }
    @Test void aceptacionSinGuardarResultadoRecuperaLeaseYPublicaIdentidadEstable() throws Exception {
        registrar(MetodoPago.TARJETA);
        var abandoned=store.claim().getFirst();
        publisher.publish(abandoned); // crash/lost confirm before the DB completion
        assertThat(store.claim()).isEmpty();
        jdbc.update("UPDATE pagos.confirmacion_outbox SET lease_until=now()-interval '1 second'");
        var recovered=store.claim().getFirst();
        assertThat(recovered.messageId()).isEqualTo(abandoned.messageId());
        assertThat(recovered.payload()).isEqualTo(abandoned.payload());
        store.finish(abandoned,null); // stale completion cannot steal the new lease
        assertThat(outbox.findAll().getFirst().getEstado()).isEqualTo(OutboxMessage.State.IN_FLIGHT);
        publisher.publish(recovered); store.finish(recovered,null);
        var first=rabbit.receive(properties.queues().confirmacion(),3000);
        var second=rabbit.receive(properties.queues().confirmacion(),3000);
        assertThat(first.getMessageProperties().getMessageId()).isEqualTo(second.getMessageProperties().getMessageId());
    }
    @Test void confirmTimeoutPermiteRepublicarMismoMessageId() {
        registrar(MetodoPago.TARJETA);
        var silent=new RabbitTemplate() {
            @Override public void send(String e,String r,Message m,org.springframework.amqp.rabbit.connection.CorrelationData c) {}
        };
        new OutboxDispatcher(store,new PagoConfirmacionPublisher(silent,properties),json,properties).dispatch();
        UUID id=outbox.findAll().getFirst().getMessageId();
        assertThat(outbox.findAll().getFirst().getEstado()).isEqualTo(OutboxMessage.State.PENDING);
        readyAgain(); dispatcher.dispatch();
        assertThat(rabbit.receive(properties.queues().confirmacion(),3000).getMessageProperties().getMessageId()).isEqualTo(id.toString());
    }
    @Test void confirmNegativoNoMarcaPublished() {
        registrar(MetodoPago.TARJETA);
        var negative=new RabbitTemplate() {
            @Override public void send(String e,String r,Message m,org.springframework.amqp.rabbit.connection.CorrelationData c) {
                c.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(false,"test"));
            }
        };
        new OutboxDispatcher(store,new PagoConfirmacionPublisher(negative,properties),json,properties).dispatch();
        assertThat(outbox.findAll().getFirst().getEstado()).isEqualTo(OutboxMessage.State.PENDING);
    }
    @Test void schedulerSoloReconciliacionHttpHistorica() {
        registrar(MetodoPago.TARJETA);
        pedidos.registrarPedido(600,10,"CREADO",13980,"CLP");
        pagos.saveAndFlush(new Pago(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), 600L,10L,13980L,"CLP",MetodoPago.TARJETA,EstadoPago.APROBADO,"legacy"));
        assertThat(service.reconciliarConfirmacionesPendientes()).isEqualTo(1);
        assertThat(pedidos.confirmaciones()).isEqualTo(1);
        assertThat(pedidos.estaConfirmado(600)).isTrue();
        assertThat(pedidos.estaConfirmado(500)).isFalse();
        assertThat(outbox.count()).isEqualTo(1);
    }
    @Test void dispatcherNuevoRecuperaPendienteTrasReinicio() {
        registrar(MetodoPago.TARJETA);
        UUID id=outbox.findAll().getFirst().getMessageId();
        new OutboxDispatcher(store,publisher,json,properties).dispatch();
        assertThat(outbox.findById(id).orElseThrow().getEstado()).isEqualTo(OutboxMessage.State.PUBLISHED);
        assertThat(rabbit.receive(properties.queues().confirmacion(),3000).getMessageProperties().getMessageId()).isEqualTo(id.toString());
    }

    @Test void legacyPedidoRejectedBeforeAtomicPaymentOutboxOrBrokerEffect() {
        pedidos.registrarResumen(new cl.duoc.pedidos360.pagos.client.PedidoResumen(500L,CLIENTE.tenantId(),10L,"CREADO",13980L,"CLP","RECONCILED_LEGACY"));
        assertThatThrownBy(()->registrar(MetodoPago.TARJETA)).isInstanceOf(cl.duoc.pedidos360.pagos.exception.PagoException.class);
        assertThat(pagos.count()).isZero(); assertThat(outbox.count()).isZero();
        dispatcher.dispatch();
        assertThat(rabbit.receive(properties.queues().confirmacion(),100)).isNull();
        assertThat(pedidos.confirmaciones()).isZero();
    }
    @Test void unknownAndReconciledOutboxNeverClaimedEvenAfterLeaseExpiration() {
        registrar(MetodoPago.TARJETA);
        var before=outbox.findAll().getFirst();
        UUID id=before.getMessageId(); String payload=before.getPayload();
        try {
            for(String origin:java.util.List.of("UNKNOWN","RECONCILED_LEGACY")) {
                // Historical-state fixture in this disposable container, not operational reconciliation.
                jdbc.execute("ALTER TABLE pagos.pagos DISABLE TRIGGER guard_tenant_origin");
                try { jdbc.update("UPDATE pagos.pagos SET tenant_id=?::uuid,tenant_origin=?",origin.equals("UNKNOWN")?null:CLIENTE.tenantId().toString(),origin); }
                finally { jdbc.execute("ALTER TABLE pagos.pagos ENABLE TRIGGER guard_tenant_origin"); }
                jdbc.execute("UPDATE pagos.confirmacion_outbox SET estado='IN_FLIGHT',lease_until=now()-interval '1 second',lease_token=gen_random_uuid()");
                assertThat(store.claim()).isEmpty();
                dispatcher.dispatch();
                assertThat(rabbit.receive(properties.queues().confirmacion(),100)).isNull();
                assertThat(outbox.findById(id).orElseThrow().getPayload()).isEqualTo(payload);
            }
        } finally {
            jdbc.execute("ALTER TABLE pagos.pagos DISABLE TRIGGER guard_tenant_origin");
            try { jdbc.update("UPDATE pagos.pagos SET tenant_id=?::uuid,tenant_origin='AUTHENTICATED_NEW'",CLIENTE.tenantId().toString()); }
            finally { jdbc.execute("ALTER TABLE pagos.pagos ENABLE TRIGGER guard_tenant_origin"); }
        }
    }
    @Test void claimJoinDoesNotLockPaymentParent() throws Exception {
        registrar(MetodoPago.TARJETA);
        long id=pagos.findAll().getFirst().getId();
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForList("SELECT id FROM pagos.pagos WHERE id=? FOR UPDATE",id);
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(()->store.claim()).get(3,TimeUnit.SECONDS)).hasSize(1);
            } finally { release.countDown(); }
            holder.get(5,TimeUnit.SECONDS);
        }
    }
}
