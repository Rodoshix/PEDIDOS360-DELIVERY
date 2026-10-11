package cl.duoc.pedidos360.restaurantes;

import cl.duoc.pedidos360.messaging.fixture.FixtureActorKeys;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import cl.duoc.pedidos360.restaurantes.entity.EstadoRestaurante;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import cl.duoc.pedidos360.messaging.QueryTopology;
import cl.duoc.pedidos360.messaging.actor.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.messaging.relay.QueryConsumerRecovery;
import cl.duoc.pedidos360.restaurantes.entity.Restaurante;
import cl.duoc.pedidos360.restaurantes.repository.RestauranteRepository;
import cl.duoc.pedidos360.restaurantes.service.RestauranteService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real application listener + PostgreSQL + quorum/TTL policies + configure-denied account. */
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "pedidos360.messaging.relay-mode=ACTIVE", "pedidos360.messaging.actor.emisor=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "pedidos360.messaging.actor.clave-id=11111111-2222-3333-4444-555555555555",
    "pedidos360.messaging.actor.tolerancia-reloj=0s", "pedidos360.messaging.recovery-backoff=500ms"})
class RestaurantesRabbitTests {
    static final String MAIN = "p360.restaurantes.consultas.q";
    static final String RETRY = "p360.restaurantes.consultas.retry.1s.q";
    static final String DLQ = "p360.restaurantes.consultas.dlq";
    static final String REPLIES = "p360.bff.consultas.respuestas.q";
    static final String OPERATION = "restaurante.listar.v1";
    static final UUID TENANT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    static final UUID KEY = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final String CONSUMER_PASSWORD = UUID.randomUUID().toString();
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Container static RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:4.1.8-management-alpine");

    @DynamicPropertySource static void configure(DynamicPropertyRegistry p) throws Exception {
        p.add("pedidos360.messaging.actor.public-jwks", FixtureActorKeys::publicJwks);
        provision(); // Before the real listener starts. TEST fixture only, no application declarations.
        p.add("spring.datasource.url", postgres::getJdbcUrl);
        p.add("spring.datasource.username", postgres::getUsername);
        p.add("spring.datasource.password", postgres::getPassword);
        p.add("spring.rabbitmq.host", broker::getHost);
        p.add("spring.rabbitmq.port", broker::getAmqpPort);
        p.add("spring.rabbitmq.virtual-host", () -> "/");
        p.add("spring.rabbitmq.username", () -> "p360-restaurantes-consumer");
        p.add("spring.rabbitmq.password", () -> CONSUMER_PASSWORD);
    }

    static JsonNode api(String method, String path, Object body) throws Exception {
        String basic = Base64.getEncoder().encodeToString((broker.getAdminUsername() + ":" + broker.getAdminPassword())
                .getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://" + broker.getHost() + ":" + broker.getHttpPort() + "/api/" + path))
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                        HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        var response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    static void provision() throws Exception {
        for (String exchange : List.of("p360.queries", "p360.retry", "p360.dlx"))
            api("PUT", "exchanges/%2F/" + exchange, Map.of("type","direct","durable",true,"auto_delete",false,"arguments",Map.of()));
        for (String queue : List.of(MAIN, RETRY, DLQ, REPLIES))
            api("PUT", "queues/%2F/" + queue, Map.of("durable",true,"auto_delete",false,"arguments",Map.of("x-queue-type","quorum")));
        binding("p360.queries", MAIN, OPERATION);
        binding("p360.retry", RETRY, "restaurante.listar.retry.1s");
        binding("p360.dlx", DLQ, "restaurante.listar.failed");
        policy(MAIN, Map.of("dead-letter-exchange","p360.dlx", "dead-letter-routing-key","restaurante.listar.failed",
                "dead-letter-strategy","at-least-once","overflow","reject-publish","delivery-limit",5));
        policy(RETRY, Map.of("message-ttl",1000,"dead-letter-exchange","p360.queries","dead-letter-routing-key",OPERATION,
                "dead-letter-strategy","at-least-once","overflow","reject-publish","delivery-limit",-1));
        policy(DLQ, Map.of("delivery-limit",-1,"overflow","reject-publish"));
        policy(REPLIES, Map.of("message-ttl",60000,"max-length",1000,"overflow","reject-publish","delivery-limit",5));
        api("PUT", "users/p360-restaurantes-consumer", Map.of("password",CONSUMER_PASSWORD,"tags",""));
        api("PUT", "permissions/%2F/p360-restaurantes-consumer", Map.of("configure","^$",
                "write","^(p360\\.retry|amq\\.default|p360\\.dlx)$", "read","^(p360\\.restaurantes\\.consultas\\.q)$"));
    }
    static void binding(String exchange, String queue, String key) throws Exception {
        api("POST", "bindings/%2F/e/" + exchange + "/q/" + queue, Map.of("routing_key",key,"arguments",Map.of()));
    }
    static void policy(String queue, Map<String,Object> definition) throws Exception {
        api("PUT", "policies/%2F/ep2-" + queue, Map.of("pattern","^(" + queue.replace(".","\\.") + ")$",
                "priority",10,"apply-to","quorum_queues","definition",definition));
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired ActorContextSigner signer;
    @Autowired RequestEnvelopeContext envelopes;
    @Autowired QueryTopology topology;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired RestauranteRepository repository;
    @MockitoSpyBean RestauranteService service;
    @LocalServerPort int port;
    com.rabbitmq.client.Connection admin;
    com.rabbitmq.client.Channel channel;

    @BeforeEach void initialize() throws Exception {
        var connection = new com.rabbitmq.client.ConnectionFactory();
        connection.setHost(broker.getHost()); connection.setPort(broker.getAmqpPort());
        connection.setUsername(broker.getAdminUsername()); connection.setPassword(broker.getAdminPassword());
        admin = connection.newConnection(); channel = admin.createChannel(); channel.confirmSelect();
        repository.deleteAll();
        for (String queue : List.of(MAIN,RETRY,DLQ,REPLIES))
            assertThat(channel.queueDeclarePassive(queue).getMessageCount()).as("no test leftovers in %s",queue).isZero();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(registry.getListenerContainer(
                QueryConsumerRecovery.QUERY_LISTENER_ID).isRunning()).isTrue());
    }
    @AfterEach void close() throws Exception { if (admin != null) admin.close(); }

    RequestEnvelope request(JsonNode payload, String operation, UUID tenant, Set<String> roles, Set<String> scopes,
            String audience, boolean expired) {
        Instant now = Instant.now();
        Instant created = expired ? now.minusSeconds(10) : now;
        Instant deadline = expired ? now.minusSeconds(1) : now.plusSeconds(5);
        var actor = new ActorContext(tenant, UUID.randomUUID(), roles, scopes, created,
                deadline.minusMillis(100), audience, KEY);
        return RequestEnvelope.crear(UUID.randomUUID(), operation, payload, FixtureActorKeys.signer().emitir(actor, deadline), created, deadline);
    }
    RequestEnvelope valid() {
        return request(JSON.createObjectNode(), OPERATION, TENANT, Set.of("CLIENTE"),
                Set.of("access_as_user"), MAIN, false);
    }
    String send(RequestEnvelope request) throws Exception { return send(request, envelopes.escribir(request), REPLIES); }
    String send(RequestEnvelope request, byte[] body, String reply) throws Exception {
        String correlation = UUID.randomUUID().toString();
        channel.basicPublish("p360.queries", OPERATION, true,
                new com.rabbitmq.client.AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2)
                    .messageId(request.messageId().toString()).correlationId(correlation).replyTo(reply).build(), body);
        channel.waitForConfirmsOrDie(3000);
        return correlation;
    }
    com.rabbitmq.client.GetResponse receive(String queue) throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.GetResponse>();
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(25)).until(() -> {
            reference.set(channel.basicGet(queue,false)); return reference.get()!=null;
        });
        var response = reference.get(); channel.basicAck(response.getEnvelope().getDeliveryTag(),false);
        channel.queueDeclarePassive(queue); // fence ACK
        return response;
    }
    void dlqWithoutDomain(RequestEnvelope request, byte[] body) throws Exception {
        send(request,body,REPLIES);
        var failed = receive(DLQ);
        assertThat(failed.getProps().getMessageId()).isEqualTo(request.messageId().toString());
        assertThat(((Number)failed.getProps().getHeaders().getOrDefault("retry-count",0)).intValue()).isZero();
        verify(service,never()).listar();
    }

    @Test void realListenerMatchesHttpIncludingInactiveCorrelationAndManualAck() throws Exception {
        repository.saveAll(List.of(new Restaurante("Abierto","Test","A",EstadoRestaurante.ABIERTO),
                new Restaurante("Cerrado","Test","B",EstadoRestaurante.CERRADO),
                new Restaurante("Inactivo","Test","C",EstadoRestaurante.INACTIVO)));
        var request = valid(); String correlation = send(request);
        var response = receive(REPLIES); JsonNode result = JSON.readTree(response.getBody());
        var http = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/restaurantes")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(http.statusCode()).isEqualTo(200);
        assertThat(result.path("payload")).isEqualTo(JSON.readTree(http.body()));
        assertThat(result.path("payload").size()).isEqualTo(3);
        assertThat(result.path("payload")).anySatisfy(item ->
                assertThat(item.path("estado").asString()).isEqualTo("INACTIVO"));
        assertThat(result.path("status").asInt()).isEqualTo(200);
        assertThat(response.getProps().getCorrelationId()).isEqualTo(correlation);
        assertThat(result.path("correlationId").asString()).isEqualTo(correlation);
        assertThat(result.path("messageId").asString()).isEqualTo(request.messageId().toString());
        var container = (AbstractMessageListenerContainer)registry.getListenerContainer(QueryConsumerRecovery.QUERY_LISTENER_ID);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        // A second request through prefetch=1 proves the first delivery was settled.
        send(valid()); receive(REPLIES);
        assertThat(channel.queueDeclarePassive(MAIN).getMessageCount()).isZero();
    }

    @Test void emptyCatalogAndAdminActorReturnEmptySuccess() throws Exception {
        send(request(JSON.createObjectNode(),OPERATION,TENANT,Set.of("ADMIN"),Set.of("access_as_user"),MAIN,false));
        var result=JSON.readTree(receive(REPLIES).getBody());
        assertThat(result.path("success").asBoolean()).isTrue(); assertThat(result.path("payload").isEmpty()).isTrue();
    }
    @Test void malformedJsonGoesToDlqWithoutDomain() throws Exception { dlqWithoutDomain(valid(),"{broken".getBytes(StandardCharsets.UTF_8)); }
    @ParameterizedTest @ValueSource(strings={"{\"restauranteId\":1}","{\"estado\":\"ABIERTO\"}","{\"tenantId\":\"otro\"}","{\"extra\":null}"})
    void extraFieldsGoToDlqWithoutDomain(String payload) throws Exception {
        var r=request(JSON.readTree(payload),OPERATION,TENANT,Set.of("CLIENTE"),Set.of("access_as_user"),MAIN,false);
        dlqWithoutDomain(r,envelopes.escribir(r));
    }
    @Test void arrayPayloadIsRejectedAtListener() throws Exception {
        var r=valid(); var tree=(tools.jackson.databind.node.ObjectNode)JSON.readTree(envelopes.escribir(r));
        tree.set("payload",JSON.createArrayNode()); dlqWithoutDomain(r,JSON.writeValueAsBytes(tree));
    }
    @Test void malformedOidIsRejectedBeforeDomain() throws Exception {
        var r=valid(); String[] parts=r.actor().split("\\.");
        var claims=(tools.jackson.databind.node.ObjectNode)JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        claims.put("sujetoId","not-an-oid");
        parts[1]=Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(claims));
        String modified=FixtureActorKeys.signRaw(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.readTree(envelopes.escribir(r));
        tree.put("actor",modified); dlqWithoutDomain(r,JSON.writeValueAsBytes(tree));
    }
    @Test void disallowedReplyToNeverRoutesOutsideTechnicalQueue() throws Exception {
        String forbidden=channel.queueDeclare("",false,true,true,null).getQueue();
        var r=valid(); send(r,envelopes.escribir(r),forbidden);
        var failed=receive(DLQ);
        assertThat(((Number)failed.getProps().getHeaders().get("retry-count")).intValue()).isEqualTo(1);
        assertThat(channel.queueDeclarePassive(REPLIES).getMessageCount()).isZero();
        assertThat(channel.queueDeclarePassive(forbidden).getMessageCount()).isZero();
    }
    @Test void wrongOperationIsDefinitive() throws Exception {
        var r=request(JSON.createObjectNode(),"pago.consultar.v1",TENANT,Set.of("CLIENTE"),Set.of("access_as_user"),MAIN,false);
        dlqWithoutDomain(r,envelopes.escribir(r));
    }
    @Test void malformedActorIsDefinitive() throws Exception {
        var r=valid(); var tree=JSON.readTree(envelopes.escribir(r)).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)tree).put("actor","invalid"); dlqWithoutDomain(r,JSON.writeValueAsBytes(tree));
    }
    @Test void incorrectSignatureIsDefinitive() throws Exception {
        var r=valid(); var tree=(tools.jackson.databind.node.ObjectNode)JSON.readTree(envelopes.escribir(r));
        String[] parts=r.actor().split("\\."); parts[2]="A".repeat(43);
        tree.put("actor",String.join(".",parts)); dlqWithoutDomain(r,JSON.writeValueAsBytes(tree));
    }
    @Test void wrongTenantIsDefinitive() throws Exception {
        var r=request(JSON.createObjectNode(),OPERATION,UUID.randomUUID(),Set.of("CLIENTE"),Set.of("access_as_user"),MAIN,false);
        dlqWithoutDomain(r,envelopes.escribir(r));
    }
    @Test void wrongDestinationIsDefinitive() throws Exception {
        var r=request(JSON.createObjectNode(),OPERATION,TENANT,Set.of("CLIENTE"),Set.of("access_as_user"),"p360.usuarios.consultas.q",false);
        dlqWithoutDomain(r,envelopes.escribir(r));
    }
    @Test void disallowedRoleIsDefinitive() throws Exception {
        var r=request(JSON.createObjectNode(),OPERATION,TENANT,Set.of("REPARTIDOR"),Set.of("access_as_user"),MAIN,false);
        dlqWithoutDomain(r,envelopes.escribir(r));
    }
    @Test void missingScopeIsDefinitive() throws Exception {
        var r=request(JSON.createObjectNode(),OPERATION,TENANT,Set.of("CLIENTE"),Set.of(),MAIN,false);
        dlqWithoutDomain(r,envelopes.escribir(r));
    }
    @Test void deadlineExpiredDoesNotExecuteOrRetry() throws Exception {
        var r=request(JSON.createObjectNode(),OPERATION,TENANT,Set.of("CLIENTE"),Set.of("access_as_user"),MAIN,true);
        dlqWithoutDomain(r,envelopes.escribir(r));
        assertThat(channel.queueDeclarePassive(REPLIES).getMessageCount()).isZero(); // No respuesta funcional fuera de plazo.
    }
    @Test void businessErrorReturns409AndIsAcked() throws Exception {
        doThrow(QueryBusinessException.conflicto("fixture-conflict")).when(service).listar();
        send(valid()); assertThat(JSON.readTree(receive(REPLIES).getBody()).path("status").asInt()).isEqualTo(409);
        assertThat(channel.queueDeclarePassive(DLQ).getMessageCount()).isZero();
        assertThat(channel.queueDeclarePassive(RETRY).getMessageCount()).isZero();
        reset(service); send(valid()); receive(REPLIES); // settlement barrier
    }
    @Test void expiredActorOnLiveRequestIsDefinitive() throws Exception {
        var r=valid(); Instant now=Instant.now();
        var actor=new ActorContext(TENANT,UUID.randomUUID(),Set.of("CLIENTE"),Set.of("access_as_user"),
                now.minusSeconds(30),now.minusSeconds(1),MAIN,KEY);
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.readTree(envelopes.escribir(r));
        tree.put("actor",FixtureActorKeys.signer().emitir(actor,r.expiresAt())); dlqWithoutDomain(r,JSON.writeValueAsBytes(tree));
    }
    @Test void requestWaitsWhileConsumerStoppedThenRecovers() throws Exception {
        var listener=registry.getListenerContainer(QueryConsumerRecovery.QUERY_LISTENER_ID);
        listener.stop();
        try {
            var r=valid(); String correlation=send(r);
            assertThat(channel.queueDeclarePassive(MAIN).getMessageCount()).isEqualTo(1);
            verify(service,never()).listar();
            listener.start();
            assertThat(receive(REPLIES).getProps().getCorrelationId()).isEqualTo(correlation);
        } finally { if(!listener.isRunning()) listener.start(); }
    }
    @Test void transientDbFailureRetriesOnceWithRealPolicyTtl() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture-db-offline"))
                .doCallRealMethod().when(service).listar();
        var r=valid(); Instant start=Instant.now(); String correlation=send(r);
        var response=receive(REPLIES);
        assertThat(Duration.between(start,Instant.now()).toMillis()).isGreaterThanOrEqualTo(900);
        assertThat(response.getProps().getCorrelationId()).isEqualTo(correlation);
        assertThat(response.getProps().getMessageId()).isEqualTo(r.messageId().toString());
        verify(service,times(2)).listar();
    }
    @Test void unexpectedFailureExhaustsOneRetryThenDlq() throws Exception {
        doThrow(new IllegalStateException("fixture-unexpected")).when(service).listar();
        var r=valid(); send(r); var failed=receive(DLQ);
        assertThat(failed.getProps().getMessageId()).isEqualTo(r.messageId().toString());
        assertThat(((Number)failed.getProps().getHeaders().get("retry-count")).intValue()).isEqualTo(1);
        verify(service,times(2)).listar();
    }
    @Test void failedRetryHandoffKeepsOriginalAndRecoversWithoutTtlOrHotLoop() throws Exception {
        channel.queueUnbind(RETRY,"p360.retry","restaurante.listar.retry.1s");
        try {
            var calls=new AtomicInteger(); var times=new ArrayList<Instant>();
            doAnswer(invocation -> { times.add(Instant.now()); if(calls.incrementAndGet()==1)
                throw new org.springframework.dao.DataAccessResourceFailureException("fixture-handoff");
                return invocation.callRealMethod(); }).when(service).listar();
            var r=valid(); String correlation=send(r); var recovered=receive(REPLIES);
            assertThat(recovered.getProps().getCorrelationId()).isEqualTo(correlation);
            assertThat(recovered.getProps().getMessageId()).isEqualTo(r.messageId().toString());
            assertThat(calls.get()).isEqualTo(2);
            assertThat(Duration.between(times.get(0),times.get(1)).toMillis()).isGreaterThanOrEqualTo(450);
            assertThat(channel.queueDeclarePassive(RETRY).getMessageCount()).isZero();
            send(valid()); receive(REPLIES); // listener remains live with prefetch=1
        } finally { binding("p360.retry",RETRY,"restaurante.listar.retry.1s"); }
    }
    @Test void quorumQueuesHavePolicyTtlAndConsumerCannotConfigure() throws Exception {
        var info=api("GET","queues/%2F/"+RETRY,null);
        assertThat(info.path("type").asString()).isEqualTo("quorum");
        assertThat(info.path("arguments").has("x-message-ttl")).isFalse();
        assertThat(info.path("effective_policy_definition").path("message-ttl").asInt()).isEqualTo(1000);
        var permission=api("GET","permissions/%2F/p360-restaurantes-consumer",null);
        assertThat(permission.path("configure").asString()).isEqualTo("^$");
        assertThat(permission.path("write").asString()).contains("p360\\.dlx");
        assertThat(topology.queue()).isEqualTo(MAIN);
        assertThat(topology.routingKey()).isEqualTo(OPERATION);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var consumers=api("GET","consumers/%2F",null);
            assertThat(consumers.size()).isEqualTo(1);
            assertThat(consumers.get(0).path("prefetch_count").asInt()).isEqualTo(1);
            assertThat(consumers.get(0).path("ack_required").asBoolean()).isTrue();
        });
        assertThat(topology.declarations().getDeclarables()).filteredOn(d -> d instanceof Queue)
                .allSatisfy(d -> assertThat(((Queue)d).getArguments()).isEmpty());
    }
}
