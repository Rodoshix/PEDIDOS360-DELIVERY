package cl.duoc.pedidos360.pagos;

import cl.duoc.pedidos360.messaging.actor.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.relay.QueryConsumerRecovery;
import cl.duoc.pedidos360.pagos.entity.*;
import cl.duoc.pedidos360.pagos.messaging.*;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import cl.duoc.pedidos360.pagos.service.PagoService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** Real listener, real PostgreSQL and quorum RabbitMQ; selected service failures are injected explicitly. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "pedidos360.messaging.relay-mode=ACTIVE", "pedidos360.messaging.identity-proof.enabled=true",
    "pedidos360.messaging.coordination-mode=RABBITMQ", "spring.rabbitmq.dynamic=false",
    "pedidos360.messaging.actor.tolerancia-reloj=0s", "pedidos360.messaging.confirm-timeout=500ms",
    "pedidos360.messaging.actor.emisor=11111111-1111-1111-1111-111111111111"})
@Import({PostgresTestConfiguration.class, PedidosStubConfiguration.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PagosQueryRabbitTests {
    static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OID = UUID.randomUUID();
    static final String MAIN="p360.pagos.consultas.q", RETRY="p360.pagos.consultas.retry.1s.q",
            DLQ="p360.pagos.consultas.dlq", REPLIES="p360.bff.consultas.respuestas.q", COMMANDS="p360.pedidos.confirmacion.q";
    static final String PASSWORD=UUID.randomUUID().toString();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final RabbitMQContainer broker=new RabbitMQContainer("rabbitmq:4.1.8-management-alpine")
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "-rabbit collect_statistics_interval 100");
    static final AtomicLong orders=new AtomicLong(2000);
    @Autowired PagoRepository pagos;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean PagoService service;
    @Autowired OutboxStore store;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired @Qualifier("queryRabbitTemplate") RabbitTemplate queries;
    @Autowired @Qualifier("rabbitTemplate") RabbitTemplate publisher;
    RabbitTemplate admin;
    CachingConnectionFactory adminCf;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        broker.start(); provision();
        p.add("pedidos360.messaging.actor.public-jwks",PagosQueryKeys::actors);
        p.add("pedidos360.messaging.identity-proof.public-jwks",PagosQueryKeys::proofs);
        p.add("spring.rabbitmq.host",broker::getHost); p.add("spring.rabbitmq.port",broker::getAmqpPort);
        p.add("spring.rabbitmq.virtual-host",()->"/"); p.add("spring.rabbitmq.username",()->"publisher");
        p.add("spring.rabbitmq.password",()->PASSWORD);
        p.add("pagos.consultas.rabbitmq.host",broker::getHost); p.add("pagos.consultas.rabbitmq.port",broker::getAmqpPort);
        p.add("pagos.consultas.rabbitmq.virtual-host",()->"/"); p.add("pagos.consultas.rabbitmq.username",()->"queries");
        p.add("pagos.consultas.rabbitmq.password",()->PASSWORD);
    }
    static JsonNode api(String method,String path,Object body) throws Exception {
        String basic=Base64.getEncoder().encodeToString((broker.getAdminUsername()+":"+broker.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://"+broker.getHost()+":"+broker.getHttpPort()+"/api/"+path))
                .header("Authorization","Basic "+basic).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isBetween(200,299);
            return response.body().isBlank()?JSON.createObjectNode():JSON.readTree(response.body());
        }
    }
    static void provision() throws Exception {
        for(String e:List.of("p360.queries","p360.retry","p360.dlx","p360.pedidos.commands"))
            api("PUT","exchanges/%2F/"+e,Map.of("type","direct","durable",true,"arguments",Map.of()));
        for(String q:List.of(MAIN,RETRY,DLQ,REPLIES,COMMANDS))
            api("PUT","queues/%2F/"+q,Map.of("durable",true,"arguments",Map.of("x-queue-type","quorum")));
        bind("p360.queries",MAIN,"pago.consultar.v1"); bind("p360.retry",RETRY,"pago.consultar.retry.1s");
        bind("p360.dlx",DLQ,"pago.consultar.failed"); bind("p360.pedidos.commands",COMMANDS,"pedido.confirmar.v1");
        policy(MAIN,Map.of("dead-letter-exchange","p360.dlx","dead-letter-routing-key","pago.consultar.failed","dead-letter-strategy","at-least-once","overflow","reject-publish","delivery-limit",5));
        policy(RETRY,Map.of("message-ttl",1000,"dead-letter-exchange","p360.queries","dead-letter-routing-key","pago.consultar.v1","dead-letter-strategy","at-least-once","overflow","reject-publish","delivery-limit",-1));
        policy(DLQ,Map.of("delivery-limit",-1,"overflow","reject-publish"));
        policy(REPLIES,Map.of("message-ttl",60000,"overflow","reject-publish","max-length",1000,"delivery-limit",5));
        for(String user:List.of("publisher","queries")) api("PUT","users/"+user,Map.of("password",PASSWORD,"tags",""));
        permissions("queries","^(amq\\.default|p360\\.retry|p360\\.dlx)$","^p360\\.pagos\\.consultas\\.q$");
        permissions("publisher","^p360\\.pedidos\\.commands$","^$");
    }
    static void permissions(String user,String write,String read) throws Exception { api("PUT","permissions/%2F/"+user,Map.of("configure","^$","write",write,"read",read)); }
    static void bind(String e,String q,String key) throws Exception { api("POST","bindings/%2F/e/"+e+"/q/"+q,Map.of("routing_key",key,"arguments",Map.of())); }
    static void policy(String q,Map<String,Object> definition) throws Exception { api("PUT","policies/%2F/test-"+q,Map.of("pattern","^"+q.replace(".","\\.")+"$","apply-to","quorum_queues","priority",10,"definition",definition)); }
    @BeforeEach void before() throws Exception {
        adminCf=new CachingConnectionFactory(broker.getHost(),broker.getAmqpPort());
        adminCf.setUsername(broker.getAdminUsername()); adminCf.setPassword(broker.getAdminPassword());
        admin=new RabbitTemplate(adminCf);
        for(String q:List.of(MAIN,RETRY,DLQ,REPLIES,COMMANDS)) api("DELETE","queues/%2F/"+q+"/contents",null);
        jdbc.execute("TRUNCATE pagos.tenant_reconciliation_audit,pagos.confirmacion_outbox,pagos.pagos RESTART IDENTITY CASCADE");
        clearInvocations(service);
    }
    @AfterEach void after() { adminCf.destroy(); }
    @AfterAll void stopBroker() {
        registry.stop();
        ((CachingConnectionFactory)queries.getConnectionFactory()).destroy();
        ((CachingConnectionFactory)publisher.getConnectionFactory()).destroy();
        broker.stop();
    }
    Pago save(UUID tenant,long owner) { return pagos.saveAndFlush(new Pago(tenant,orders.incrementAndGet(),owner,1000L,"CLP",MetodoPago.EFECTIVO,EstadoPago.PENDIENTE,UUID.randomUUID().toString())); }
    RequestEnvelope request(long id,long user,Set<String> roles,long rMillis) {
        Instant now=IdentityProofCodec.millis(Instant.now()); Instant r=now.plusMillis(rMillis);
        var proof=new IdentityProof(T,OID,user,now,now,now.plusSeconds(4),now.plusSeconds(5),UUID.randomUUID(),UUID.randomUUID());
        var actor=new ActorContext(T,OID,roles,Set.of("access_as_user"),now,r,IdentityProof.AUDIENCE,PagosQueryKeys.ACTOR_ID);
        var payload=JSON.createObjectNode().put("pagoId",id).put("pruebaIdentidad",PagosQueryKeys.proof(proof));
        return RequestEnvelope.crear(UUID.randomUUID(),IdentityProof.OPERATION,payload,PagosQueryKeys.signer().emitir(actor,r),now,r);
    }
    MessageProperties send(RequestEnvelope request) {
        var p=new MessageProperties(); p.setMessageId(request.messageId().toString()); p.setCorrelationId(UUID.randomUUID().toString());
        p.setReplyTo(REPLIES); p.setDeliveryMode(MessageDeliveryMode.PERSISTENT); p.setContentType("application/json");
        admin.send("p360.queries",IdentityProof.OPERATION,new Message(new RequestEnvelopeContext().escribir(request),p));
        return p;
    }
    JsonNode reply() { var msg=admin.receive(REPLIES,6000); assertThat(msg).isNotNull(); return JSON.readTree(msg.getBody()); }
    Message dlq() { var msg=admin.receive(DLQ,6000); assertThat(msg).isNotNull(); return msg; }
    void settled() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var unacknowledged = api("GET", "queues/%2F/" + MAIN, null).path("messages_unacknowledged");
            // Management can omit statistics until its first sample; absence is not zero.
            assertThat(unacknowledged.isIntegralNumber()).isTrue();
            assertThat(unacknowledged.intValue()).isZero();
        });
    }

    @Test void ownPaymentUsesRealListenerAndExactPublicProjectionWithoutWrites() {
        var pago=save(T,10); send(request(pago.getId(),10,Set.of("CLIENTE"),3750));
        var response=reply(); assertThat(response.path("status").intValue()).isEqualTo(200);
        assertThat(response.path("payload").propertyNames()).containsExactlyInAnyOrder("pagoId","pedidoId","usuarioId","monto","moneda","metodo","estado","fecha");
        assertThat(response.toString()).doesNotContain("pruebaIdentidad","tenantOrigin","tenantId");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagos.confirmacion_outbox",Long.class)).isZero(); settled();
    }
    @ParameterizedTest @ValueSource(strings={"foreignUser","admin","external","missing","unknown","reconciled"})
    void tenantAndHistoricalMatrix(String kind) {
        long id;
        if(kind.equals("unknown")||kind.equals("reconciled")) {
            jdbc.execute("ALTER TABLE pagos.pagos DISABLE TRIGGER guard_tenant_origin");
            try { id=jdbc.queryForObject("INSERT INTO pagos.pagos(pedido_id,usuario_id,monto,moneda,metodo,estado,clave_idempotencia) VALUES(?,10,1000,'CLP','EFECTIVO','PENDIENTE',?) RETURNING id",Long.class,orders.incrementAndGet(),UUID.randomUUID().toString()); }
            finally { jdbc.execute("ALTER TABLE pagos.pagos ENABLE TRIGGER guard_tenant_origin"); }
            if(kind.equals("reconciled")) jdbc.execute("SELECT pagos.reconcile_tenant("+id+",0,'"+T+"','"+"a".repeat(64)+"')");
        } else id=kind.equals("missing")?999999:save(kind.equals("external")?UUID.randomUUID():T,10).getId();
        boolean admin=kind.equals("admin");
        send(request(id,kind.equals("foreignUser")||admin?20:10,Set.of(admin?"ADMIN":"CLIENTE"),3750));
        int expected=kind.equals("foreignUser")?403:List.of("external","missing","unknown").contains(kind)?404:200;
        assertThat(reply().path("status").intValue()).isEqualTo(expected); settled();
    }
    @ParameterizedTest @ValueSource(strings={"missingProof","extra","idString","idZero","badProof","wrongTenant","wrongOid","expiredActor","RTooLate","wrongAudience","badActor"})
    void invalidIdentityOrProtocolNeverExecutesBusiness(String kind) {
        var pago=save(T,10); var r=request(pago.getId(),10,Set.of("CLIENTE"),kind.equals("RTooLate")?3900:3750);
        var payload=(tools.jackson.databind.node.ObjectNode)r.payload();
        switch(kind) {
            case "missingProof" -> payload.remove("pruebaIdentidad");
            case "extra" -> payload.put("usuarioId",10);
            case "idString" -> payload.put("pagoId","1");
            case "idZero" -> payload.put("pagoId",0);
            case "badProof" -> payload.put("pruebaIdentidad","bad");
            case "badActor" -> r=RequestEnvelope.crear(r.messageId(),r.operacion(),payload,"invalid",r.occurredAt(),r.expiresAt());
            case "wrongTenant","wrongOid","wrongAudience" -> {
                Instant n=r.occurredAt();
                if(kind.equals("wrongAudience")) {
                    var a=new ActorContext(T,OID,Set.of("CLIENTE"),Set.of("access_as_user"),n,r.expiresAt(),"p360.usuarios.consultas.q",PagosQueryKeys.ACTOR_ID);
                    r=RequestEnvelope.crear(r.messageId(),r.operacion(),payload,PagosQueryKeys.signer().emitir(a,r.expiresAt()),n,r.expiresAt());
                } else payload.put("pruebaIdentidad",PagosQueryKeys.proof(new IdentityProof(kind.equals("wrongTenant")?UUID.randomUUID():T,
                        kind.equals("wrongOid")?UUID.randomUUID():OID,10,n,n,n.plusSeconds(4),n.plusSeconds(5),UUID.randomUUID(),UUID.randomUUID())));
            }
            case "expiredActor" -> {
                Instant n=r.occurredAt(); var a=new ActorContext(T,OID,Set.of("CLIENTE"),Set.of("access_as_user"),n.minusSeconds(2),n.minusSeconds(1),IdentityProof.AUDIENCE,PagosQueryKeys.ACTOR_ID);
                r=RequestEnvelope.crear(r.messageId(),r.operacion(),payload,PagosQueryKeys.signer().emitir(a,r.expiresAt()),n,r.expiresAt());
            }
        }
        send(r); dlq(); verify(service,never()).obtener(any(),any()); assertThat(admin.receive(REPLIES)).isNull(); settled();
    }
    @Test void injectedDatabaseFailureRetriesOnceWithoutChangingOriginalEnvelope() {
        var pago=save(T,10); var r=request(pago.getId(),10,Set.of("CLIENTE"),3750);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("injected")).doCallRealMethod().when(service).obtener(any(),eq(pago.getId()));
        send(r); var response=reply(); assertThat(response.path("messageId").stringValue()).isEqualTo(r.messageId().toString());
        verify(service,times(2)).obtener(any(),eq(pago.getId())); settled();
    }
    @Test void repeatedInjectedDatabaseFailureGoesToDlqAfterOneRetry() {
        var pago=save(T,10); var r=request(pago.getId(),10,Set.of("CLIENTE"),3750);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("injected")).when(service).obtener(any(),eq(pago.getId()));
        var metadata=send(r); var failed=dlq(); assertThat(failed.getBody()).isEqualTo(new RequestEnvelopeContext().escribir(r));
        assertThat(failed.getMessageProperties().getCorrelationId()).isEqualTo(metadata.getCorrelationId());
        assertThat(failed.getMessageProperties().getReplyTo()).isEqualTo(metadata.getReplyTo());
        assertThat(failed.getMessageProperties().getRetryCount()).isEqualTo(1); verify(service,times(2)).obtener(any(),eq(pago.getId())); settled();
    }
    @Test void elapsedDuringInjectedSlowSqlReturnsNoFunctionalResponseOrRetry() {
        var pago=save(T,10);
        doAnswer(call->{ jdbc.execute("SELECT pg_sleep(1)"); return call.callRealMethod(); }).when(service).obtener(any(),eq(pago.getId()));
        send(request(pago.getId(),10,Set.of("CLIENTE"),300)); dlq(); assertThat(admin.receive(REPLIES)).isNull();
        verify(service,times(1)).obtener(any(),eq(pago.getId())); settled();
    }
    @Test void expiredEnvelopeDoesNotExecuteEvenWithValidProof() {
        var pago=save(T,10); var r=request(pago.getId(),10,Set.of("CLIENTE"),1);
        await().until(()->!r.expiresAt().isAfter(Instant.now())); send(r); dlq(); verify(service,never()).obtener(any(),any()); settled();
    }
    @ParameterizedTest @ValueSource(strings={"messageId","correlation","replyTo","duplicate","type","version","routing","contentType"})
    void incoherentMetadataOrDuplicateJsonIsDefinitive(String kind) {
        var pago=save(T,10); var r=request(pago.getId(),10,Set.of("CLIENTE"),3750);
        var p=new MessageProperties(); p.setMessageId(kind.equals("messageId")?UUID.randomUUID().toString():r.messageId().toString());
        p.setCorrelationId(kind.equals("correlation")?"bad":UUID.randomUUID().toString());
        p.setReplyTo(kind.equals("replyTo")?COMMANDS:REPLIES);
        p.setContentType(kind.equals("contentType")?"text/xml":"application/json");
        byte[] body=new RequestEnvelopeContext().escribir(r);
        if(kind.equals("duplicate")) body=new String(body,StandardCharsets.UTF_8)
                .replace("\"pagoId\":1","\"pagoId\":1,\"pagoId\":1").getBytes(StandardCharsets.UTF_8);
        if(kind.equals("type")||kind.equals("version")) {
            var invalid=(tools.jackson.databind.node.ObjectNode)JSON.readTree(body);
            if(kind.equals("type")) invalid.put("type","Other"); else invalid.put("version",2);
            body=JSON.writeValueAsBytes(invalid);
        }
        // Default exchange reaches the same queue with a different received routing key, without topology changes.
        if(kind.equals("routing")) { p.setHeader("x-death",List.of(Map.of("queue",RETRY))); admin.send("",MAIN,new Message(body,p)); }
        else admin.send("p360.queries",IdentityProof.OPERATION,new Message(body,p));
        var failed=dlq(); assertThat(failed.getMessageProperties().getRetryCount()).isZero();
        verify(service,never()).obtener(any(),any()); assertThat(admin.receive(REPLIES)).isNull(); settled();
    }
    @Test void authenticatedActorWithoutAllowedRoleGets403WithoutReadingPayment() {
        var pago=save(T,10); send(request(pago.getId(),10,Set.of("REPARTIDOR"),3750));
        assertThat(reply().path("status").intValue()).isEqualTo(403); verify(service,never()).obtener(any(),any()); settled();
    }
    @Test void twoConnectionsPreserveExistingOutboxCommandAndDenyPrivilegeUnion() throws Exception {
        var pago=save(T,10);
        new TransactionTemplate(manager).executeWithoutResult(s->store.crear(pago));
        send(request(pago.getId(),10,Set.of("CLIENTE"),3750)); dispatcher.dispatch();
        assertThat(reply().path("status").intValue()).isEqualTo(200);
        var command=admin.receive(COMMANDS,3000); assertThat(command).isNotNull();
        assertThat(JSON.readTree(command.getBody()).path("pagoId").longValue()).isEqualTo(pago.getId());
        assertThat(queries.getConnectionFactory()).isNotSameAs(publisher.getConnectionFactory());
        assertThat(((CachingConnectionFactory)queries.getConnectionFactory()).getUsername()).isEqualTo("queries");
        assertThat(((CachingConnectionFactory)publisher.getConnectionFactory()).getUsername()).isEqualTo("publisher");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->{
            var connections=api("GET","connections",null);
            var users=new ArrayList<String>();
            for(int i=0;i<connections.size();i++) users.add(connections.get(i).path("user").stringValue());
            assertThat(users).contains("queries","publisher");
        });
        assertThatThrownBy(()->new RabbitAdmin(queries).declareQueue(new org.springframework.amqp.core.Queue("forbidden"))).isInstanceOf(Exception.class);
        assertThatThrownBy(()->queries.receive(COMMANDS)).isInstanceOf(Exception.class);
        // basicPublish is asynchronous; a following RPC observes the broker's ACCESS_REFUSED.
        assertThatThrownBy(()->queries.execute(channel->{
            channel.basicPublish("p360.pedidos.commands","pedido.confirmar.v1",true,
                    new com.rabbitmq.client.AMQP.BasicProperties(),new byte[0]);
            channel.queueDeclarePassive(MAIN);
            return null;
        })).isInstanceOf(Exception.class);
        assertThatThrownBy(()->queries.execute(channel->{ channel.exchangeDeclare("p360.pedidos.commands","direct",true); return null; })).isInstanceOf(Exception.class);
        assertThatThrownBy(()->publisher.receive(MAIN)).isInstanceOf(Exception.class);
        assertThat(admin.receive(COMMANDS)).isNull();
        assertThat(api("GET","permissions/%2F/queries",null).path("write").stringValue()).isEqualTo("^(amq\\.default|p360\\.retry|p360\\.dlx)$");
        settled();
    }
}
