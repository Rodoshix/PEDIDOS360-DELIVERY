package cl.duoc.pedidos360.rabbitadmin;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, JWT signature/validators, sandbox account and RabbitMQ. Entra authority is a fixture. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "entra.tenant-id=11111111-1111-1111-1111-111111111111",
    "entra.api-client-id=22222222-2222-2222-2222-222222222222",
    "entra.frontend-client-id=33333333-3333-3333-3333-333333333333",
    "entra.enabled=false", // No such bypass: JWT remains mandatory.
    "rabbit-admin.allow-queue-delete=true", // Unknown properties cannot override fixed policy.
    "rabbit-admin.delete-queues.enabled=true",
    "spring.rabbitmq.virtual-host=pedidos360"})
@Import(RabbitAdminIntegrationTests.Keys.class)
@TestMethodOrder(MethodOrderer.MethodName.class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class RabbitAdminIntegrationTests {
    static final String TENANT="11111111-1111-1111-1111-111111111111",API="22222222-2222-2222-2222-222222222222",
        FRONT="33333333-3333-3333-3333-333333333333",OID="44444444-4444-4444-4444-444444444444";
    static final RSAKey KEY=key();
    static final RabbitMQContainer BROKER=new RabbitMQContainer("rabbitmq:4.1.8-management-alpine");
    @LocalServerPort int port;
    @Autowired CachingConnectionFactory cf;
    @Autowired RabbitAdminService service;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static RSAKey key() { try { return new RSAKeyGenerator(2048).generate(); } catch(Exception e) { throw new IllegalStateException(e); } }
    @TestConfiguration(proxyBeanMethods=false) static class Keys {
        @Bean @Primary JwtDecoder fixtureDecoder() throws Exception {
            var d=NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            d.setJwtValidator(EntraConfiguration.validators(TENANT,API,FRONT)); return d;
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        BROKER.start();
        check("rabbitmqctl","add_vhost",SandboxRules.VHOST);
        check("rabbitmqctl","add_user","p360-admin-demo","local-test-only");
        check("rabbitmqctl","set_permissions","-p",SandboxRules.VHOST,"p360-admin-demo",
            "^p360\\.demo\\.","^p360\\.demo\\.","^p360\\.demo\\.");
        r.add("rabbit-admin.host",BROKER::getHost); r.add("rabbit-admin.port",BROKER::getAmqpPort);
        r.add("rabbit-admin.username",() -> "p360-admin-demo"); r.add("rabbit-admin.password",() -> "local-test-only");
    }
    static void check(String... cmd) throws Exception { assertThat(BROKER.execInContainer(cmd).getExitCode()).isZero(); }
    @AfterAll static void stop() { BROKER.stop(); }
    String name() { return "p360.demo."+UUID.randomUUID(); }
    String token(Map<String,Object> changes) { return token(changes,KEY); }
    String token(Map<String,Object> changes,RSAKey signing) {
        try {
            var b=new JWTClaimsSet.Builder().issuer("https://login.microsoftonline.com/"+TENANT+"/v2.0")
                .audience(API).subject(OID).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(30))).claim("ver","2.0").claim("tid",TENANT)
                .claim("oid",OID).claim("azp",FRONT).claim("scp","access_as_user").claim("roles",List.of("ADMIN"));
            changes.forEach(b::claim); var j=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),b.build());
            j.sign(new RSASSASigner(signing)); return j.serialize();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    HttpResponse<String> request(String method,String path,String body,String jwt) throws Exception {
        return request(method,path,body,jwt,Map.of());
    }
    HttpResponse<String> request(String method,String path,String body,String jwt,Map<String,String> headers) throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/rabbit"+path)).timeout(Duration.ofSeconds(10));
            if(jwt!=null) r.header("Authorization","Bearer "+jwt);
            r.header("Content-Type","application/json");
            headers.forEach(r::header);
            return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    HttpResponse<String> call(String method,String path,String body) throws Exception { return request(method,path,body,token(Map.of())); }
    String queue() throws Exception { String q=name(); assertThat(call("PUT","/queues/"+q,"{\"durable\":true}").statusCode()).isEqualTo(200); return q; }
    String exchange(String type) throws Exception { String e=name(); assertThat(call("PUT","/exchanges/"+e,"{\"type\":\""+type+"\",\"durable\":true}").statusCode()).isEqualTo(200); return e; }
    String bind(String q,String e) throws Exception {
        var result=call("POST","/bindings",JSON.writeValueAsString(Map.of("queue",q,"exchange",e,"routingKey","key")));
        assertThat(result.statusCode()).isEqualTo(201); return JSON.readTree(result.body()).path("bindingId").stringValue();
    }
    @Test void crudRoundTripAndBrokerCounts() throws Exception {
        String q=queue(),e=exchange("direct"),id=bind(q,e);
        var rabbit=new RabbitTemplate(cf); rabbit.convertAndSend(e,"key","fixture");
        var info=call("GET","/queues/"+q,null); assertThat(info.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(info.body()).path("messages").intValue()).isEqualTo(1);
        assertThat(rabbit.receive(q,2000)).isNotNull();
        assertThat(call("DELETE","/bindings/"+id,null).statusCode()).isEqualTo(204);
        rabbit.convertAndSend(e,"key","unrouted");
        assertThat(rabbit.receive(q,100)).isNull();
        assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
        assertThat(call("DELETE","/exchanges/"+e,null).statusCode()).isEqualTo(204);
        assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(200);
    }
    @Test void emptyQueueDeletionIsForbiddenWithStableSanitizedResponse() throws Exception {
        String q=queue();
        var result=call("DELETE","/queues/"+q,null);
        assertThat(result.statusCode()).isEqualTo(403);
        var problem=JSON.readTree(result.body());
        assertThat(problem.path("status").intValue()).isEqualTo(403);
        assertThat(problem.path("detail").stringValue())
            .isEqualTo("Eliminación de queues deshabilitada por política de seguridad.");
        var retained=call("GET","/queues/"+q,null);
        assertThat(retained.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(retained.body()).path("messages").intValue()).isZero();
    }
    @Test void queueDeleteStillRequiresValidJwtScopeAndAdmin() throws Exception {
        String q=queue(),path="/queues/"+q;
        for(String jwt:Arrays.asList(null,"not.jwt",token(Map.of(),key()),token(Map.of("exp",Date.from(Instant.now().minusSeconds(180))))))
            assertThat(request("DELETE",path,null,jwt).statusCode()).isEqualTo(401);
        for(var claims:List.of(Map.<String,Object>of("roles",List.of("CLIENTE")),Map.<String,Object>of("scp","wrong"))) {
            var denied=request("DELETE",path,null,token(claims));
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).doesNotContain("Eliminación de queues deshabilitada");
        }
        assertThat(call("DELETE",path,null).statusCode()).isEqualTo(403);
        assertThat(call("GET",path,null).statusCode()).isEqualTo(200);
    }
    @Test void callerCannotEnableQueueDeletionThroughQueryHeadersOrBody() throws Exception {
        String q=queue();
        var result=request("DELETE","/queues/"+q+"?enabled=true&force=true&allowQueueDelete=true",
            "{\"enabled\":true,\"force\":true}",token(Map.of()),Map.of("X-Allow-Queue-Delete","true","X-Force","true"));
        assertThat(result.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(result.body()).path("detail").stringValue())
            .isEqualTo("Eliminación de queues deshabilitada por política de seguridad.");
        assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings={"direct","fanout","topic"})
    void exchangeTypes(String type) throws Exception { String e=exchange(type); assertThat(call("DELETE","/exchanges/"+e,null).statusCode()).isEqualTo(204); }
    @Test void incompatibleExchangeAndMissingBindingAreConflicts() throws Exception {
        String e=exchange("direct"),q=queue();
        assertThat(call("PUT","/exchanges/"+e,"{\"type\":\"topic\",\"durable\":true}").statusCode()).isEqualTo(409);
        assertThat(call("POST","/bindings",JSON.writeValueAsString(Map.of("queue",name(),"exchange",e,"routingKey","key"))).statusCode()).isEqualTo(409);
        String id=bind(q,e);
        assertThat(call("DELETE","/exchanges/"+e,null).statusCode()).isEqualTo(409);
        assertThat(call("DELETE","/bindings/"+id,null).statusCode()).isEqualTo(204);
    }
    @Test void queueMessagesInsertedAfterReadPreventDeletion() throws Exception {
        String q=queue(),e=exchange("direct"); bind(q,e);
        assertThat(JSON.readTree(call("GET","/queues/"+q,null).body()).path("messages").intValue()).isZero();
        var rabbit=new RabbitTemplate(cf);
        for(String body:List.of("preserve-1","preserve-2","preserve-3")) rabbit.convertAndSend(e,"key",body);
        assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
        assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(200);
        var retained=new ArrayList<String>();
        for(int i=0;i<3;i++) retained.add(new String(rabbit.receive(q,2000).getBody(),java.nio.charset.StandardCharsets.UTF_8));
        // Publications can use different cached channels: verify all contents, not cross-channel order.
        assertThat(retained).containsExactlyInAnyOrder("preserve-1","preserve-2","preserve-3");
        assertThat(rabbit.receive(q,100)).isNull();
        assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
    }
    @Test void unacknowledgedMessageMustPreventDeletion() throws Exception {
        String q=queue(),e=exchange("direct"); bind(q,e);
        new RabbitTemplate(cf).convertAndSend(e,"key","must-not-be-deleted-unacked");
        var raw=new com.rabbitmq.client.ConnectionFactory();
        raw.setHost(BROKER.getHost()); raw.setPort(BROKER.getAmqpPort());
        raw.setUsername("p360-admin-demo"); raw.setPassword("local-test-only"); raw.setVirtualHost(SandboxRules.VHOST);
        // A real client channel, not a Spring cached proxy: close really requeues pending ACKs.
        try(var connection=raw.newConnection();var channel=connection.createChannel()) {
            var delivery=channel.basicGet(q,false);
            assertThat(delivery).isNotNull();
            var read=JSON.readTree(call("GET","/queues/"+q,null).body());
            assertThat(read.path("messages").intValue()).isZero();
            assertThat(read.path("consumers").intValue()).isZero();
            // A ready-count snapshot is not proof that no message remains awaiting ACK.
            assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
            assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(200);
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(JSON.readTree(call("GET","/queues/"+q,null).body()).path("messages").intValue()).isEqualTo(1));
        assertThat(new RabbitTemplate(cf).receive(q,2000).getBody())
            .isEqualTo("must-not-be-deleted-unacked".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new RabbitTemplate(cf).receive(q,100)).isNull();
    }
    @Test void queueConsumerAddedAfterReadPreventsDeletion() throws Exception {
        String q=queue();
        assertThat(JSON.readTree(call("GET","/queues/"+q,null).body()).path("consumers").intValue()).isZero();
        try(var connection=cf.createConnection();var channel=connection.createChannel(false)) {
            String consumer=channel.basicConsume(q,true,(tag,msg) -> {},tag -> {});
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(JSON.readTree(call("GET","/queues/"+q,null).body()).path("consumers").intValue()).isEqualTo(1));
            assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
            channel.basicCancel(consumer);
        }
        assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
        assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings={"amq.topic","p360.queries","p360.pedidos.confirmacion.q","demo.old","p360.demo","p360.demo.-bad","p360.demo.bad@name"})
    void protectedNamesRejectedOnAllResourceRoutes(String n) throws Exception {
        int expected=n.contains("@")?400:409;
        assertThat(call("PUT","/queues/"+n,"{\"durable\":true}").statusCode()).isEqualTo(expected);
        assertThat(call("GET","/queues/"+n,null).statusCode()).isEqualTo(expected);
        assertThat(call("DELETE","/queues/"+n,null).statusCode()).isEqualTo(expected);
        assertThat(call("PUT","/exchanges/"+n,"{\"type\":\"direct\",\"durable\":true}").statusCode()).isEqualTo(expected);
        assertThat(call("DELETE","/exchanges/"+n,null).statusCode()).isEqualTo(expected);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"durable\":null}","{\"durable\":false}","{\"durable\":\"true\"}",
        "{\"durable\":true,\"vhost\":\"pedidos360\"}","{\"durable\":true,\"broker\":\"attacker\"}",
        "{\"durable\":true,\"credentials\":\"injected\"}","{\"durable\":true,\"arguments\":{}}",
        "{\"durable\":true,\"durable\":true}","{\"durable\":true} {}","[]"})
    void invalidQueueDto(String body) throws Exception { assertThat(call("PUT","/queues/"+name(),body).statusCode()).isEqualTo(400); }
    @ParameterizedTest @ValueSource(strings={"headers","DIRECT","custom",""})
    void invalidExchangeType(String type) throws Exception { assertThat(call("PUT","/exchanges/"+name(),"{\"type\":\""+type+"\",\"durable\":true}").statusCode()).isEqualTo(400); }
    @ParameterizedTest @ValueSource(strings={"{}","{\"queue\":null,\"exchange\":\"p360.demo.e\",\"routingKey\":\"key\"}",
        "{\"queue\":\"\",\"exchange\":\"p360.demo.e\",\"routingKey\":\"key\"}",
        "{\"queue\":\"p360.demo.q\",\"exchange\":\"p360.demo.e\",\"routingKey\":3}",
        "{\"queue\":\"p360.demo.q\",\"exchange\":\"p360.demo.e\",\"routingKey\":\"key\",\"arguments\":{}}"})
    void invalidBindingDto(String body) throws Exception { assertThat(call("POST","/bindings",body).statusCode()).isEqualTo(400); }
    @Test void incompatibleQueueTypeIsConflict() throws Exception {
        String q=name();
        try(var connection=cf.createConnection();var channel=connection.createChannel(false)) {
            channel.queueDeclare(q,true,false,false,Map.of("x-queue-type","quorum"));
        }
        assertThat(call("PUT","/queues/"+q,"{\"durable\":true}").statusCode()).isEqualTo(409);
        assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(200);
        assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
        assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(200); // No unconditional fallback.
    }
    @Test void callerQueryCannotSelectConnection() throws Exception {
        String q=queue();
        assertThat(call("GET","/queues/"+q+"?vhost=pedidos360&broker=invalid",null).statusCode()).isEqualTo(200);
        assertThat(cf.getVirtualHost()).isEqualTo(SandboxRules.VHOST);
        assertThat(cf.getHost()).isEqualTo(BROKER.getHost());
        assertThat(cf.getPort()).isEqualTo(BROKER.getAmqpPort());
    }
    @Test void invalidBindingCannotReachProtectedResources() throws Exception {
        for(var body:List.of(Map.of("queue",name(),"exchange","","routingKey","key"),
            Map.of("queue","p360.pagos.consultas.q","exchange",name(),"routingKey","key"),
            Map.of("queue",name(),"exchange",name(),"routingKey","bad\nkey")))
            assertThat(call("POST","/bindings",JSON.writeValueAsString(body)).statusCode()).isEqualTo(400);
        String forged=Base64.getUrlEncoder().withoutPadding().encodeToString(("p360.pagos.consultas.q\n"+name()+"\nkey").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(call("DELETE","/bindings/"+forged,null).statusCode()).isEqualTo(409);
        assertThat(call("DELETE","/bindings/not-a-binding",null).statusCode()).isEqualTo(400);
        assertThatThrownBy(() -> service.deleteQueue("p360.queries")).isInstanceOf(AdminFailure.class);
    }
    @Test void missingAndInvalidJwtAreUnauthorized() throws Exception {
        String path="/queues/"+name();
        for(String jwt:Arrays.asList(null,"not.jwt",token(Map.of(),key()),token(Map.of("exp",Date.from(Instant.now().minusSeconds(180))))))
            assertThat(request("PUT",path,"{\"durable\":true}",jwt).statusCode()).isEqualTo(401);
    }
    @ParameterizedTest @ValueSource(strings={"tid","aud","iss","azp","oid","ver"})
    void wrongEntraClaimsAreUnauthorized(String claim) throws Exception {
        assertThat(request("PUT","/queues/"+name(),"{\"durable\":true}",token(Map.of(claim,"wrong"))).statusCode()).isEqualTo(401);
    }
    @Test void clientAndIncorrectScopeAreForbidden() throws Exception {
        for(var claims:List.of(Map.<String,Object>of("roles",List.of("CLIENTE")),Map.<String,Object>of("scp","wrong"),
            Map.<String,Object>of("roles","ADMIN"),Map.<String,Object>of("roles",List.of("admin"))))
            assertThat(request("PUT","/queues/"+name(),"{\"durable\":true}",token(claims)).statusCode()).isEqualTo(403);
    }
    @Test void sandboxConnectionAndBrokerPermissionsAreIndependentOfDefaultConfig() throws Exception {
        assertThat(cf.getVirtualHost()).isEqualTo(SandboxRules.VHOST);
        assertThat(cf.getUsername()).isEqualTo("p360-admin-demo");
        try(var c=cf.createConnection();var channel=c.createChannel(false)) {
            assertThatThrownBy(() -> channel.queueDeclare("p360.pedidos.confirmacion.q",true,false,false,null)).isInstanceOf(java.io.IOException.class);
        }
        var foreign=new CachingConnectionFactory(BROKER.getHost(),BROKER.getAmqpPort());
        foreign.setUsername("p360-admin-demo"); foreign.setPassword("local-test-only"); foreign.setVirtualHost("/");
        try { assertThatThrownBy(foreign::createConnection).isInstanceOf(org.springframework.amqp.AmqpException.class); }
        finally { foreign.destroy(); }
    }
    @Test void auditDoesNotExposeRequestOrToken(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String secret="AUDIT_SECRET_SENTINEL",jwt=token(Map.of());
        queue();
        String q=queue(); assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
        assertThat(request("PUT","/queues/"+name(),"{\"durable\":true,\"password\":\""+secret+"\"}",jwt).statusCode()).isEqualTo(400);
        assertThat(output.getAll()).contains("RabbitAdmin requestId=","actorHash=","operation=PUT_QUEUE","operation=DELETE_QUEUE","outcome=FORBIDDEN").doesNotContain(secret,jwt,"local-test-only",OID);
    }
    @ParameterizedTest @ValueSource(strings={"exp","nbf"})
    void missingLifetimeIsUnauthorized(String claim) throws Exception {
        assertThat(request("GET","/queues/"+name(),null,token(Collections.singletonMap(claim,null))).statusCode()).isEqualTo(401);
    }
    @Test void unsignedAndHmacJwtAreUnauthorized() throws Exception {
        String unsigned=new PlainJWT(new JWTClaimsSet.Builder().subject(OID).build()).serialize();
        var hmac=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),new JWTClaimsSet.Builder().subject(OID).build());
        byte[] bytes=new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
        hmac.sign(new com.nimbusds.jose.crypto.MACSigner(bytes));
        for(String jwt:List.of(unsigned,hmac.serialize())) assertThat(request("GET","/queues/"+name(),null,jwt).statusCode()).isEqualTo(401);
    }
    @Test void zzBrokerStoppedReturns503AndRecovers() throws Exception {
        String q=queue(); check("rabbitmqctl","stop_app");
        try {
            assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(503);
            assertThat(call("PUT","/queues/"+name(),"{\"durable\":true}").statusCode()).isEqualTo(503);
            assertThat(call("DELETE","/queues/"+q,null).statusCode()).isEqualTo(403);
        } finally { check("rabbitmqctl","start_app"); }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(call("GET","/queues/"+q,null).statusCode()).isEqualTo(200));
    }
}
