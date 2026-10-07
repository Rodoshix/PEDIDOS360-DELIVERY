package cl.duoc.pedidos360.pedidos;

import java.nio.charset.StandardCharsets;
import cl.duoc.pedidos360.pedidos.messaging.*;
import cl.duoc.pedidos360.pedidos.entity.*;
import cl.duoc.pedidos360.pedidos.repository.PedidoRepository;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;
import com.rabbitmq.client.Channel;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "pedidos360.messaging.coordination-mode=RABBITMQ", "pedidos360.messaging.reliability.platform-ready=true", "spring.datasource.hikari.connection-timeout=1000", "spring.rabbitmq.virtual-host=/"})
@Import({PostgresTestConfiguration.class,RabbitConsumerTests.Broker.class})
class RabbitConsumerTests {
    @TestConfiguration(proxyBeanMethods=false)
    static class Broker {
        @Bean @ServiceConnection RabbitMQContainer rabbit() {
            var broker=new RabbitMQContainer("rabbitmq:4.1-management-alpine")
                .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS","-rabbit dead_letter_worker_publisher_confirm_timeout 1000")
                .withCopyToContainer(org.testcontainers.utility.MountableFile.forClasspathResource("rabbitmq-reliability.conf"),"/etc/rabbitmq/rabbitmq.conf");
            broker.start();
            try {
                var result=broker.execInContainer("rabbitmqctl","set_policy","--apply-to","quorum_queues","reliable","^p360\\.pedidos\\.confirmacion\\.retry\\.(5s|30s|120s)\\.q$", "{\"dead-letter-strategy\":\"at-least-once\",\"overflow\":\"reject-publish\"}");
                if(result.getExitCode()!=0) throw new IllegalStateException(result.getStderr());
                result=broker.execInContainer("rabbitmqctl","set_policy","--apply-to","quorum_queues","dlq-retention","^p360\\.pedidos\\.confirmacion\\.dlq$", "{\"delivery-limit\":-1,\"overflow\":\"reject-publish\"}");
                if(result.getExitCode()!=0) throw new IllegalStateException(result.getStderr());
                result=broker.execInContainer("rabbitmqctl","set_policy","--priority","10","--apply-to","quorum_queues","main-dlx","^p360\\.pedidos\\.confirmacion\\.q$", "{\"dead-letter-exchange\":\"p360.pedidos.dlx\",\"dead-letter-routing-key\":\"pedido.confirmar.failed\",\"dead-letter-strategy\":\"at-least-once\",\"overflow\":\"reject-publish\",\"delivery-limit\":5}");
                if(result.getExitCode()!=0) throw new IllegalStateException(result.getStderr());
            } catch(Exception failure) { broker.stop(); throw new IllegalStateException(failure); }
            return broker;
        }
    }
    @Autowired PedidoRepository repository;
    @Autowired PedidoConfirmacionProcessor processor;
    @Autowired PedidoConfirmacionConsumer consumer;
    @Autowired JsonMapper json;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitProperties properties;
    @Autowired ConfirmacionReliabilityProperties reliability;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired RabbitListenerEndpointRegistry registry;
    @BeforeEach void clean() {
        registry.stop();
        for(String queue:java.util.List.of(properties.queues().confirmacion(),reliability.retry5Queue(),reliability.retry30Queue(),reliability.retry120Queue(),reliability.dlq())) rabbit.execute(c->{c.queuePurge(queue);return null;});
        repository.deleteAll(); registry.start();
    }
    Pedido pedido(EstadoPedido state) {
        var pedido=new Pedido(10L,20L,"Test 123","CLP"); pedido.setEstado(state);
        return repository.saveAndFlush(pedido);
    }
    Message message(long id) {
        return message(json.writeValueAsString(ConfirmarPedidoPorPago.crear(id,100)));
    }
    Message message(String body) {
        var p=new MessageProperties(); p.setContentType("application/json");
        p.setMessageId(json.readTree(body).path("messageId").asText()); p.setDeliveryTag(7);
        return new Message(body.getBytes(StandardCharsets.UTF_8),p);
    }
    @Test void validV1ConfirmacionDuplicadaAckDespuesDelCommit() throws Exception {
        var pedido=pedido(EstadoPedido.CREADO);
        var channel=mock(Channel.class);
        doAnswer(invocation->{
            assertThat(repository.findById(pedido.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO);
            return null;
        }).when(channel).basicAck(7,false);
        var msg=message(pedido.getId());
        consumer.consume(msg,channel);
        Long version=repository.findById(pedido.getId()).orElseThrow().getVersion();
        consumer.consume(msg,channel);
        verify(channel,times(2)).basicAck(7,false);
        assertThat(repository.findById(pedido.getId()).orElseThrow().getVersion()).isEqualTo(version);
    }
    @Test void confirmadoYTodosLosPosterioresNoRetroceden() {
        for (var state:java.util.List.of(EstadoPedido.CONFIRMADO,EstadoPedido.PREPARANDO,
            EstadoPedido.LISTO,EstadoPedido.EN_REPARTO,EstadoPedido.ENTREGADO)) {
            var pedido=pedido(state); processor.process(message(pedido.getId()));
            var actual=repository.findById(pedido.getId()).orElseThrow();
            assertThat(actual.getEstado()).isEqualTo(state);
            assertThat(actual.getVersion()).isEqualTo(pedido.getVersion());
        }
    }
    @Test void canceladoEInexistenteSonDefinitivos() {
        var cancelled=pedido(EstadoPedido.CANCELADO);
        assertThatThrownBy(()->processor.process(message(cancelled.getId())))
            .isInstanceOf(ConfirmacionDefinitivaException.class).satisfies(e->
                assertThat(((ConfirmacionDefinitivaException)e).reason()).isEqualTo(ConfirmacionDefinitivaException.Reason.PEDIDO_CANCELADO));
        assertThatThrownBy(()->processor.process(message(Long.MAX_VALUE)))
            .isInstanceOf(ConfirmacionDefinitivaException.class).satisfies(e->
                assertThat(((ConfirmacionDefinitivaException)e).reason()).isEqualTo(ConfirmacionDefinitivaException.Reason.PEDIDO_INEXISTENTE));
    }
    @Test void invalidoNoInvocaServicioLocal() {
        var local=mock(PedidoService.class); var processor=new PedidoConfirmacionProcessor(json,local);
        var valid=json.writeValueAsString(ConfirmarPedidoPorPago.crear(500,100));
        for (var invalid:java.util.List.of(valid.replace("ConfirmarPedidoPorPago","Otro"),
            valid.replace("\"version\":1","\"version\":2"),
            valid.replace("\"version\":1","\"version\":4294967297"),
            valid.replace("\"version\":1","\"version\":2,\"version\":1"), valid.replace("\"version\":1","\"version\":\"1\""),
            valid.replace("\"pedidoId\":500","\"pedidoId\":0"),valid.replace("\"pagoId\":100","\"pagoId\":-1"),
            valid.replace("\"pagoId\":100","\"pagoId\":1.5"),valid.replace("\"pagoId\":100","\"pagoId\":9223372036854775808"),
            valid.replace("\"occurredAt\":\"", "\"occurredAt\":\"invalid"),
            valid.replace("\"messageId\":\"","\"messageId\":\"invalid"), valid.replace("}",",\"usuario\":10}"))) {
            assertThatThrownBy(()->processor.process(message(invalid))).isInstanceOf(ConfirmacionDefinitivaException.class);
        }
        var malformed=new Message("{bad".getBytes(StandardCharsets.UTF_8),new MessageProperties());
        assertThatThrownBy(()->processor.process(malformed)).isInstanceOf(ConfirmacionDefinitivaException.class);
        var trailing=message(valid);
        trailing=new Message((valid+" {}").getBytes(StandardCharsets.UTF_8),trailing.getMessageProperties());
        final var invalidTrailing=trailing;
        assertThatThrownBy(()->processor.process(invalidTrailing)).isInstanceOf(ConfirmacionDefinitivaException.class);
        verifyNoInteractions(local);
    }
    @Test void consumerReutilizaServicioLocalYEntregaErroresAlPuntoDeExtension() throws Exception {
        var local=mock(PedidoService.class); var failures=mock(PedidoConfirmacionFailureHandler.class);
        var consumer=new PedidoConfirmacionConsumer(new PedidoConfirmacionProcessor(json,local),failures);
        var channel=mock(Channel.class); var msg=message(500);
        consumer.consume(msg,channel); verify(local).confirmarPorPago(500L); verify(channel).basicAck(7,false);
        doThrow(new IllegalStateException("DB temporarily down")).when(local).confirmarPorPago(500L);
        consumer.consume(msg,channel);
        verify(failures).handle(eq(msg),eq(channel),isA(IllegalStateException.class));
        verify(channel,times(1)).basicAck(7,false);
        verifyNoMoreInteractions(channel);
    }
    @Test void ackPerdidoPermiteRedeliverySinSegundoEfecto() throws Exception {
        var pedido=pedido(EstadoPedido.CREADO); var channel=mock(Channel.class);
        doThrow(new java.io.IOException("lost ACK")).when(channel).basicAck(7,false);
        var msg=message(pedido.getId());
        var recovery=mock(ConfirmacionConsumerRecovery.class);
        var handler=new DefaultPedidoConfirmacionFailureHandler(new ConfirmacionErrorClassifier(), mock(ConfirmacionRetryPublisher.class), recovery, new ConfirmacionFailureReporter(json), reliability);
        var safeConsumer=new PedidoConfirmacionConsumer(processor,handler);
        assertThatCode(()->safeConsumer.consume(msg,channel)).doesNotThrowAnyException();
        var version=repository.findById(pedido.getId()).orElseThrow().getVersion();
        verify(recovery).recover();
        safeConsumer.consume(msg,mock(Channel.class));
        assertThat(repository.findById(pedido.getId()).orElseThrow().getVersion()).isEqualTo(version);
    }
    @Test void consumoRealYDuplicadoNoDejanMensajesSinAck() {
        var pedido=pedido(EstadoPedido.CREADO); var msg=message(pedido.getId());
        rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->
            assertThat(repository.findById(pedido.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
        var version=repository.findById(pedido.getId()).orElseThrow().getVersion();
        rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);
        // A following distinct order acts as a barrier: prefetch=1 requires the duplicate's ACK first.
        var next=pedido(EstadoPedido.CREADO);
        rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(next.getId()));
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->
            assertThat(repository.findById(next.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
        assertThat(repository.findById(pedido.getId()).orElseThrow().getVersion()).isEqualTo(version);
    }
    @Test void redeliveryRealAlCerrarCanalDespuesDeCommit() throws Exception {
        registry.stop();
        try {
            var pedido=pedido(EstadoPedido.CREADO); var msg=message(pedido.getId());
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);
            var connection=connectionFactory.createConnection();
            var channel=connection.createChannel(false);
            var first=new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.GetResponse>();
            await().atMost(5,TimeUnit.SECONDS).until(()->{first.set(channel.basicGet(properties.queues().confirmacion(),false));return first.get()!=null;});
            var delivery=first.get();
            var received=new ConfirmacionMessagePropertiesConverter()
                .toMessageProperties(delivery.getProps(),delivery.getEnvelope(),"UTF-8");
            processor.process(new Message(delivery.getBody(),received));
            Long version=repository.findById(pedido.getId()).orElseThrow().getVersion();
            ((org.springframework.amqp.rabbit.connection.ChannelProxy)channel).getTargetChannel().close();
            channel.close(); // physically close: cached proxy close alone does not close AMQP channel
            var newChannel=connection.createChannel(false);
            var redelivery=new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.GetResponse>();
            await().atMost(5,TimeUnit.SECONDS).until(()->{
                redelivery.set(newChannel.basicGet(properties.queues().confirmacion(),false));
                return redelivery.get()!=null;
            });
            var repeated=redelivery.get();
            assertThat(repeated).isNotNull(); assertThat(repeated.getEnvelope().isRedeliver()).isTrue();
            var repeatedProps=new ConfirmacionMessagePropertiesConverter()
                .toMessageProperties(repeated.getProps(),repeated.getEnvelope(),"UTF-8");
            consumer.consume(new Message(repeated.getBody(),repeatedProps),newChannel);
            assertThat(repository.findById(pedido.getId()).orElseThrow().getVersion()).isEqualTo(version);
            assertThat(newChannel.basicGet(properties.queues().confirmacion(),true)).isNull(); newChannel.close();
        } finally { registry.start(); }
    }

    @Autowired ConfirmacionRetryPublisher retryPublisher;
    @Autowired PedidoConfirmacionFailureHandler failureHandler;
    @Autowired RabbitMQContainer broker;
    @Autowired org.testcontainers.postgresql.PostgreSQLContainer postgres;
    @Autowired org.springframework.context.ApplicationContext context;
    @Test void beanReemplazadoYFactoryManualUno() {
        assertThat(context.getBeansOfType(PedidoConfirmacionFailureHandler.class)).hasSize(1);
        assertThat(failureHandler).isInstanceOf(DefaultPedidoConfirmacionFailureHandler.class);
        var container=(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer)registry.getListenerContainer(ConfirmacionConsumerRecovery.LISTENER_ID);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat((Integer)org.springframework.test.util.ReflectionTestUtils.invokeMethod(container,"getPrefetchCount")).isEqualTo(1);
        assertThat(container.getActiveConsumerCount()).isEqualTo(1);
    }
    @Test void definitivosMedianteListenerRealVanADlqSinBloquearSiguiente() {
        var valid=json.writeValueAsString(ConfirmarPedidoPorPago.crear(500,100));
        var invalids=java.util.List.of("{bad",valid.replace("ConfirmarPedidoPorPago","Other"),valid.replace("\"version\":1","\"version\":2"),valid.replace("\"pedidoId\":500","\"pedidoId\":0"),valid.replace("\"messageId\":\"","\"messageId\":\"invalid"),valid.replace("\"pagoId\":100","\"pagoId\":-1"),"{}");
        for(String body:invalids) {
            var props=new MessageProperties();props.setMessageId(java.util.UUID.randomUUID().toString());props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),new Message(body.getBytes(StandardCharsets.UTF_8),props));
            await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->assertThat(rabbit.receive(reliability.dlq())).isNotNull());
        }
        for(long id:java.util.List.of(pedido(EstadoPedido.CANCELADO).getId(),Long.MAX_VALUE)) {
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(id));
            await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->assertThat(rabbit.receive(reliability.dlq())).isNotNull());
        }
        var next=pedido(EstadoPedido.CREADO);rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(next.getId()));
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(next.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
    }
    org.springframework.amqp.core.Message fromDelivery(com.rabbitmq.client.GetResponse delivery) {
        return new Message(delivery.getBody(),new ConfirmacionMessagePropertiesConverter().toMessageProperties(delivery.getProps(),delivery.getEnvelope(),"UTF-8"));
    }
    com.rabbitmq.client.GetResponse get(Channel channel,String queue) {
        var result=new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.GetResponse>();
        await().atMost(10,TimeUnit.SECONDS).until(()->{result.set(channel.basicGet(queue,false));return result.get()!=null;});return result.get();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,1,2})
    void ttlRealRetryConservaIdentidadYVuelveAPrincipal(int count) throws Exception {
        registry.stop();
        var connection=connectionFactory.createConnection();var channel=connection.createChannel(false);
        try {
            var original=message(pedido(EstadoPedido.CREADO).getId()); original.getMessageProperties().setHeader("retry-count",count);
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),original);
            var delivery=get(channel,properties.queues().confirmacion());
            long start=System.nanoTime();failureHandler.handle(fromDelivery(delivery),channel,new org.springframework.dao.DataAccessResourceFailureException("temporary DB"));
            int seconds=new int[]{5,30,120}[count];
            assertThat(channel.basicGet(properties.queues().confirmacion(),false)).isNull();
            var result=new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.GetResponse>();
            await().atMost(seconds+20,TimeUnit.SECONDS).until(()->{result.set(channel.basicGet(properties.queues().confirmacion(),false));return result.get()!=null;});
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()).isGreaterThanOrEqualTo(seconds*1000L-200);
            var repeated=fromDelivery(result.get());assertThat(repeated.getBody()).isEqualTo(original.getBody());
            assertThat(repeated.getMessageProperties().getMessageId()).isEqualTo(original.getMessageProperties().getMessageId());
            assertThat(ConfirmacionRetryPublisher.retryCount(repeated)).isEqualTo(count+1);
            consumer.consume(repeated,channel);
        } finally {channel.close();registry.start();}
    }
    @Test void agotamientoRealDlqConXDeath() throws Exception {
        registry.stop();var connection=connectionFactory.createConnection();var channel=connection.createChannel(false);
        try {
            var msg=message(500);msg.getMessageProperties().setHeader("retry-count",3);
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);
            failureHandler.handle(fromDelivery(get(channel,properties.queues().confirmacion())),channel,new IllegalStateException());
            var failed=get(channel,reliability.dlq());assertThat(failed.getProps().getMessageId()).isEqualTo(msg.getMessageProperties().getMessageId());
            assertThat(failed.getProps().getHeaders()).containsKey("x-death");channel.basicAck(failed.getEnvelope().getDeliveryTag(),false);
        } finally {channel.close();registry.start();}
    }
    @Test void bindingAusenteYExchangeInexistenteNoAceptanRetry() throws Exception {
        registry.stop(); var connection=connectionFactory.createConnection();var channel=connection.createChannel(false);
        try {
            channel.queueUnbind(reliability.retry5Queue(),reliability.retryExchange(),reliability.retry5Key());
            assertThatThrownBy(()->retryPublisher.publish(message(500),0)).isInstanceOf(RetryPublicationException.class)
                .satisfies(e->assertThat(((RetryPublicationException)e).reason()).isEqualTo(RetryPublicationException.Reason.RETURNED));
            channel.queueBind(reliability.retry5Queue(),reliability.retryExchange(),reliability.retry5Key());
            channel.exchangeDelete(reliability.retryExchange());
            assertThatThrownBy(()->retryPublisher.publish(message(500),0)).isInstanceOf(RetryPublicationException.class);
        } finally {
            rabbit.execute(c->{c.exchangeDeclare(reliability.retryExchange(),"direct",true);c.queueBind(reliability.retry5Queue(),reliability.retryExchange(),reliability.retry5Key());c.queueBind(reliability.retry30Queue(),reliability.retryExchange(),reliability.retry30Key());c.queueBind(reliability.retry120Queue(),reliability.retryExchange(),reliability.retry120Key());return null;});
            channel.close();registry.start();
        }
    }
    @Test void handoffFallidoRecuperaListenerConBackoffSinLoop() throws Exception {
        var container=registry.getListenerContainer(ConfirmacionConsumerRecovery.LISTENER_ID);
        var recovery=context.getBean(ConfirmacionConsumerRecovery.class);
        var local=mock(PedidoService.class);doThrow(new IllegalStateException()).when(local).confirmarPorPago(anyLong());
        var publish=mock(ConfirmacionRetryPublisher.class);doThrow(new RetryPublicationException(RetryPublicationException.Reason.RETURNED)).when(publish).publish(any(),anyInt());
        var realHandler=new DefaultPedidoConfirmacionFailureHandler(new ConfirmacionErrorClassifier(),publish,recovery,new ConfirmacionFailureReporter(json),reliability);
        var instrumented=new PedidoConfirmacionConsumer(new PedidoConfirmacionProcessor(json,local),realHandler);
        // Temporarily replace listener adapter with same domain consumer, injected publication failure.
        var simple=(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer)container;
        registry.stop();var previous=simple.getMessageListener();
        simple.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)instrumented::consume);
        try {
            var msg=message(500);rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);simple.start();
            await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->verify(publish,times(1)).publish(any(),eq(0)));
            await().atMost(5,TimeUnit.SECONDS).until(()->!simple.isRunning());
            verify(publish,times(1)).publish(any(),eq(0));
            // Next delivery uses original domain processor, after the scheduled restart.
            simple.setMessageListener(previous);
            await().atMost(15,TimeUnit.SECONDS).untilAsserted(()->assertThat(rabbit.receive(reliability.dlq())).isNotNull());
            verify(publish,times(1)).publish(any(),eq(0));
        } finally {registry.stop();simple.setMessageListener(previous);registry.start();}
    }
    @Test void dlqDestinoRechazaTemporalmenteRetieneHastaLiberarCapacidad() throws Exception {
        var policy=broker.execInContainer("rabbitmqctl","set_policy","--priority","30","--apply-to","quorum_queues","dlq-full","^p360\\.pedidos\\.confirmacion\\.dlq$","{\"max-length\":1,\"overflow\":\"reject-publish\"}");
        assertThat(policy.getExitCode()).isZero();
        int filled=0; boolean rejected=false;
        for(int i=0;i<10;i++) {
            var correlation=new org.springframework.amqp.rabbit.connection.CorrelationData(java.util.UUID.randomUUID().toString());
            rabbit.send(reliability.dlx(),reliability.failedRoutingKey(),message(1),correlation);
            if(!correlation.getFuture().get(3,TimeUnit.SECONDS).ack()) {rejected=true;break;} filled++;
        }
        assertThat(rejected).as("Quorum capacity must actually reject before testing DLQ handoff").isTrue();
        final int fillers=filled;
        await().atMost(5,TimeUnit.SECONDS).untilAsserted(()->rabbit.execute(c->{assertThat(c.queueDeclarePassive(reliability.dlq()).getMessageCount()).isEqualTo(fillers);return null;}));
        var msg=message(Long.MAX_VALUE);
        try {
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);
            var barrier=pedido(EstadoPedido.CREADO);rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(barrier.getId()));
            await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(barrier.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
            rabbit.execute(c->{assertThat(c.queueDeclarePassive(reliability.dlq()).getMessageCount()).isEqualTo(fillers);return null;});
            for(int i=0;i<fillers;i++) assertThat(rabbit.receive(reliability.dlq())).isNotNull(); // release capacity
            await().atMost(45,TimeUnit.SECONDS).untilAsserted(()->{
                var failed=rabbit.receive(reliability.dlq());assertThat(failed).isNotNull();assertThat(failed.getMessageProperties().getMessageId()).isEqualTo(msg.getMessageProperties().getMessageId());
            });
        } finally {assertThat(broker.execInContainer("rabbitmqctl","clear_policy","dlq-full").getExitCode()).isZero();}
    }
    @Test void replayExitosoYFallidoConBrokerReal() throws Exception {
        registry.stop(); var factory=new com.rabbitmq.client.ConnectionFactory();factory.setHost(broker.getHost());factory.setPort(broker.getAmqpPort());factory.setUsername(broker.getAdminUsername());factory.setPassword(broker.getAdminPassword());
        var pedido=pedido(EstadoPedido.CREADO);var msg=message(pedido.getId());
        rabbit.send(reliability.dlx(),reliability.failedRoutingKey(),msg);
        try(var conn=factory.newConnection();var ch=conn.createChannel()) {
            var delivery=get(ch,reliability.dlq());
            assertThatThrownBy(()->ConfirmacionDlqReplay.replay(ch,delivery,properties.exchanges().commands(),"missing-binding",3000)).isInstanceOf(IllegalStateException.class);
            // Channel close without DLQ ACK restores delivery.
        }
        try(var conn=factory.newConnection();var ch=conn.createChannel()) {
            var delivery=get(ch,reliability.dlq());assertThat(delivery.getProps().getMessageId()).isEqualTo(msg.getMessageProperties().getMessageId());
            ConfirmacionDlqReplay.replay(ch,delivery,properties.exchanges().commands(),properties.routingKeys().confirmar(),3000);
            var replay=get(ch,properties.queues().confirmacion());assertThat(replay.getProps().getHeaders()).containsKeys("replay-id","replayed-at");
            assertThat(replay.getProps().getMessageId()).isEqualTo(msg.getMessageProperties().getMessageId());
            consumer.consume(fromDelivery(replay),ch);assertThat(ch.basicGet(reliability.dlq(),true)).isNull();
        } finally {registry.start();}
    }
    @Test void consumerCaidoYReinicioBrokerPersistenComando() throws Exception {
        registry.stop();var pedido=pedido(EstadoPedido.CREADO);var msg=message(pedido.getId());msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);
        await().atMost(5,TimeUnit.SECONDS).untilAsserted(()->rabbit.execute(c->{assertThat(c.queueDeclarePassive(properties.queues().confirmacion()).getMessageCount()).isEqualTo(1);return null;}));
        assertThat(broker.execInContainer("rabbitmqctl","stop_app").getExitCode()).isZero();
        try {assertThatThrownBy(()->retryPublisher.publish(message(500),0)).isInstanceOf(RetryPublicationException.class);}
        finally {assertThat(broker.execInContainer("rabbitmqctl","start_app").getExitCode()).isZero();registry.start();}
        await().atMost(30,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(pedido.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
    }
    @Test void deliveryLimitCincoProtegeRedeliveryRepetida() throws Exception {
        registry.stop();var msg=message(500);rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);
        var connection=connectionFactory.createConnection();
        int lastDeliveryCount=0;
        for(int i=0;i<7;i++) {
            var ch=connection.createChannel(false);
            var delivery=new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.GetResponse>();
            try {
                await().atMost(3,TimeUnit.SECONDS).until(()->{delivery.set(ch.basicGet(properties.queues().confirmacion(),false));return delivery.get()!=null;});
            } catch(org.awaitility.core.ConditionTimeoutException exhausted) {ch.close();break;}
            lastDeliveryCount=((Number)delivery.get().getProps().getHeaders().getOrDefault("x-delivery-count",0L)).intValue();
            ((org.springframework.amqp.rabbit.connection.ChannelProxy)ch).getTargetChannel().abort();ch.close();
        }
        assertThat(lastDeliveryCount).as("Effective quorum delivery-limit in fixture").isEqualTo(5);
        await().atMost(15,TimeUnit.SECONDS).untilAsserted(()->{
            var failed=rabbit.receive(reliability.dlq());assertThat(failed).isNotNull();assertThat(failed.getMessageProperties().getMessageId()).isEqualTo(msg.getMessageProperties().getMessageId());
            var death=failed.getMessageProperties().getXDeathHeader().getFirst();
            assertThat(death.get("reason").toString()).isEqualTo("delivery_limit");
            assertThat(death.get("queue").toString()).isEqualTo(properties.queues().confirmacion());
        });registry.start();
    }

    @Test void dlqConservaOriginalTrasVeinticincoCierresSinAckConPolicyDePlataformaFixture() throws Exception {
        registry.stop(); var msg=message(500);
        rabbit.send(reliability.dlx(),reliability.failedRoutingKey(),msg);
        var factory=new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(broker.getHost());factory.setPort(broker.getAmqpPort());
        factory.setUsername(broker.getAdminUsername());factory.setPassword(broker.getAdminPassword());
        try(var connection=factory.newConnection()) {
            for(int i=0;i<25;i++) {
                try(var channel=connection.createChannel()) {
                    var delivery=get(channel,reliability.dlq());
                    assertThat(delivery.getProps().getMessageId()).isEqualTo(msg.getMessageProperties().getMessageId());
                    assertThat(delivery.getBody()).isEqualTo(msg.getBody());
                    if(i>0) assertThat(delivery.getEnvelope().isRedeliver()).isTrue();
                    // Physical AMQP channel close leaves delivery unsettled for inspection/replay.
                }
            }
            try(var channel=connection.createChannel()) {
                var retained=get(channel,reliability.dlq());
                assertThat(retained.getProps().getMessageId()).isEqualTo(msg.getMessageProperties().getMessageId());
                assertThat(((Number)retained.getProps().getHeaders().get("x-delivery-count")).intValue()).isGreaterThanOrEqualTo(25);
                channel.basicAck(retained.getEnvelope().getDeliveryTag(),false);
            }
        } finally {registry.start();}
    }

    @Test void recoveryDuranteStartRealPreservaFailureYRedeliveryConPrefetchUno() throws Exception {
        registry.stop();
        var simple=(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer)registry.getListenerContainer(ConfirmacionConsumerRecovery.LISTENER_ID);
        var previous=simple.getMessageListener();
        var wrapper=mock(MessageListenerContainer.class);
        var controlledRegistry=mock(RabbitListenerEndpointRegistry.class);
        when(controlledRegistry.getListenerContainer(ConfirmacionConsumerRecovery.LISTENER_ID)).thenReturn(wrapper);
        var recovery=new ConfirmacionConsumerRecovery(controlledRegistry,reliability);
        var failureDuringStart=new java.util.concurrent.CountDownLatch(1);
        var starts=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv->{simple.stop(inv.getArgument(0,Runnable.class));return null;}).when(wrapper).stop(any(Runnable.class));
        doAnswer(inv->{simple.stop();return null;}).when(wrapper).stop();
        doAnswer(inv->{
            simple.start();
            if(starts.incrementAndGet()==1)
                assertThat(failureDuringStart.await(10,TimeUnit.SECONDS)).as("Second failure completed BEFORE start() returns").isTrue();
            return null;
        }).when(wrapper).start();
        var failedPublisher=mock(ConfirmacionRetryPublisher.class);
        doThrow(new RetryPublicationException(RetryPublicationException.Reason.RETURNED)).when(failedPublisher).publish(any(),anyInt());
        var handler=new DefaultPedidoConfirmacionFailureHandler(new ConfirmacionErrorClassifier(),failedPublisher,recovery,new ConfirmacionFailureReporter(json),reliability);
        var local=mock(PedidoService.class);
        var invocations=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv->{
            if(invocations.incrementAndGet()<=2) throw new IllegalStateException("Injected handoff prerequisite failure");
            context.getBean(PedidoService.class).confirmarPorPago(inv.getArgument(0)); return null;
        }).when(local).confirmarPorPago(anyLong());
        var instrumented=new PedidoConfirmacionConsumer(new PedidoConfirmacionProcessor(json,local),handler);
        var original=pedido(EstadoPedido.CREADO);var msg=message(original.getId());
        var deliveries=new java.util.concurrent.CopyOnWriteArrayList<String>();
        simple.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)(delivery,ch)->{
            if(delivery.getMessageProperties().getMessageId().equals(msg.getMessageProperties().getMessageId())) {
                deliveries.add(delivery.getMessageProperties().getMessageId());
                instrumented.consume(delivery,ch);
                if(deliveries.size()==2) failureDuringStart.countDown();
            } else consumer.consume(delivery,ch);
        });
        try {
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),msg);simple.start();
            await().atMost(30,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(original.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
            var barrier=pedido(EstadoPedido.CREADO);rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(barrier.getId()));
            await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(barrier.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
            assertThat(deliveries).containsExactly(msg.getMessageProperties().getMessageId(),msg.getMessageProperties().getMessageId(),msg.getMessageProperties().getMessageId());
            verify(wrapper,times(2)).start();verify(wrapper,times(2)).stop(any(Runnable.class));
            verify(failedPublisher,times(2)).publish(any(),eq(0));
        } finally {recovery.close();simple.stop();simple.setMessageListener(previous);registry.start();}
    }

    @Test void postgresRealInaccesibleGeneraRetryRecuperable() throws Exception {
        registry.stop();var pedido=pedido(EstadoPedido.CREADO);var original=message(pedido.getId());
        assertThat(postgres.getUsername()).isEqualTo("test");
        try(var admin=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var stmt=admin.createStatement()) {
            try {
                stmt.execute("ALTER ROLE test NOLOGIN");
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='test' AND pid<>pg_backend_pid()");
                assertThatThrownBy(()->processor.process(original)).isInstanceOf(RuntimeException.class)
                    .satisfies(e->assertThat(new ConfirmacionErrorClassifier().classify((Exception)e)).isEqualTo(ConfirmacionErrorClassifier.Classification.TRANSITORIO));
                retryPublisher.publish(original,0);
            } finally {stmt.execute("ALTER ROLE test LOGIN");}
        }
        await().ignoreExceptions().atMost(40,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(pedido.getId())).isPresent());
        registry.start();
        await().atMost(30,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(pedido.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
    }

    @Test void todosLosEstadosValidosListenerRealAckSinRetroceso() {
        for(var state:java.util.List.of(EstadoPedido.CONFIRMADO,EstadoPedido.PREPARANDO,EstadoPedido.LISTO,EstadoPedido.EN_REPARTO,EstadoPedido.ENTREGADO)) {
            var original=pedido(state);var version=original.getVersion();
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(original.getId()));
            var barrier=pedido(EstadoPedido.CREADO);rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(barrier.getId()));
            await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(barrier.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
            var persisted=repository.findById(original.getId()).orElseThrow();assertThat(persisted.getEstado()).isEqualTo(state);assertThat(persisted.getVersion()).isEqualTo(version);
        }
    }
    @Test void ackIOExceptionListenerRealRecuperaSinDuplicarEfecto() throws Exception {
        registry.stop();var simple=(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer)registry.getListenerContainer(ConfirmacionConsumerRecovery.LISTENER_ID);
        var previous=simple.getMessageListener();var first=new java.util.concurrent.atomic.AtomicBoolean(true);var version=new java.util.concurrent.atomic.AtomicReference<Long>();
        var original=pedido(EstadoPedido.CREADO);
        simple.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)(msg,ch)->{
            var instrumented=spy(ch);
            doAnswer(inv->{if(first.compareAndSet(true,false)) {
                version.set(repository.findById(original.getId()).orElseThrow().getVersion());
                ((org.springframework.amqp.rabbit.connection.ChannelProxy)ch).getTargetChannel().abort();
                throw new java.io.IOException("injected lost ACK");
            } ch.basicAck(inv.getArgument(0),inv.getArgument(1));return null;}).when(instrumented).basicAck(anyLong(),anyBoolean());
            consumer.consume(msg,instrumented);
        });
        try {
            rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(original.getId()));simple.start();
            await().atMost(10,TimeUnit.SECONDS).until(()->version.get()!=null);
            var barrier=pedido(EstadoPedido.CREADO);rabbit.send(properties.exchanges().commands(),properties.routingKeys().confirmar(),message(barrier.getId()));
            await().atMost(25,TimeUnit.SECONDS).untilAsserted(()->assertThat(repository.findById(barrier.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPedido.CONFIRMADO));
            assertThat(repository.findById(original.getId()).orElseThrow().getVersion()).isEqualTo(version.get());
        } finally {registry.stop();simple.setMessageListener(previous);registry.start();}
    }
}
