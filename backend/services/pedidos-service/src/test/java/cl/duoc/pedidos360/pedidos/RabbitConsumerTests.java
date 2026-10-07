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
    "pedidos360.messaging.coordination-mode=RABBITMQ", "spring.rabbitmq.virtual-host=/"})
@Import({PostgresTestConfiguration.class,RabbitConsumerTests.Broker.class})
class RabbitConsumerTests {
    @TestConfiguration(proxyBeanMethods=false)
    static class Broker {
        @Bean @ServiceConnection RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4.1-management-alpine");
        }
    }
    @Autowired PedidoRepository repository;
    @Autowired PedidoConfirmacionProcessor processor;
    @Autowired PedidoConfirmacionConsumer consumer;
    @Autowired JsonMapper json;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitProperties properties;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired RabbitListenerEndpointRegistry registry;
    @BeforeEach void clean() { repository.deleteAll(); }
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
        assertThatCode(()->consumer.consume(msg,channel)).doesNotThrowAnyException();
        var version=repository.findById(pedido.getId()).orElseThrow().getVersion();
        consumer.consume(msg,mock(Channel.class));
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
            var delivery=channel.basicGet(properties.queues().confirmacion(),false);
            assertThat(delivery).isNotNull();
            var received=new org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter()
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
            var repeatedProps=new org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter()
                .toMessageProperties(repeated.getProps(),repeated.getEnvelope(),"UTF-8");
            consumer.consume(new Message(repeated.getBody(),repeatedProps),newChannel);
            assertThat(repository.findById(pedido.getId()).orElseThrow().getVersion()).isEqualTo(version);
            assertThat(newChannel.basicGet(properties.queues().confirmacion(),true)).isNull(); newChannel.close();
        } finally { registry.start(); }
    }
}
