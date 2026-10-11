package cl.duoc.pedidos360.carrito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cl.duoc.pedidos360.carrito.messaging.*;
import cl.duoc.pedidos360.messaging.command.*;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.env.MockEnvironment;

class CarritoAckLoggingTests {
  private final UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private final UUID owner = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private final VaciarCarritoPorPedido command = new VaciarCarritoPorPedido(
      UUID.randomUUID(), "VaciarCarritoPorPedido", 1, Instant.now(), 8L, 7L, 0L,
      new VaciarCarritoPorPedido.Propietario(tenant, owner));
  private final CarritoReceiptStore receipts = mock(CarritoReceiptStore.class);
  private final CarritoCommandProperties config = mock(CarritoCommandProperties.class);
  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final CarritoConsumerRecovery recovery = mock(CarritoConsumerRecovery.class);
  private final Channel channel = mock(Channel.class);
  private final Logger logger = (Logger) LoggerFactory.getLogger(CarritoCommandListener.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private CarritoCommandListener listener;

  @BeforeEach
  void setup() {
    when(config.publisherUser()).thenReturn("fixture-publisher");
    when(config.consumerUser()).thenReturn("fixture-consumer");
    listener = new CarritoCommandListener(receipts, config, rabbit, recovery,
        new MockEnvironment().withProperty("entra.tenant-id", tenant.toString()));
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(logs);
    logs.stop();
  }

  private Message message(long tag) {
    var props = new MessageProperties();
    props.setMessageId(command.messageId().toString());
    props.setContentType("application/json");
    props.setReceivedUserId("fixture-publisher");
    props.setReceivedRoutingKey(CarritoCommandProperties.ROUTE);
    props.setReceivedExchange(CarritoCommandProperties.EXCHANGE);
    props.setHeader("retry-count", 0);
    props.setHeader("personal-header", "DO_NOT_LOG");
    props.setDeliveryTag(tag);
    return new Message(command.canonical().getBytes(StandardCharsets.UTF_8), props);
  }

  @ParameterizedTest
  @EnumSource(value = CarritoReceiptStore.Result.class, names = {"EMPTIED", "OMITTED_VERSION_CHANGED"})
  void successLogsOnlyValidatedMessageAndResultAfterAck(CarritoReceiptStore.Result result) throws Exception {
    when(receipts.process(command, false)).thenReturn(result);
    doAnswer(call -> {
      assertThat(logs.list).isEmpty();
      return null;
    }).when(channel).basicAck(1L, false);
    listener.consume(message(1L), channel);
    verify(channel).basicAck(1L, false);
    verifyNoInteractions(recovery, rabbit);
    assertThat(logs.list).hasSize(1);
    var event = logs.list.getFirst();
    assertThat(event.getFormattedMessage()).isEqualTo(
        "messageId=" + command.messageId() + " result=" + result + " ACK_SENT");
    assertThat(event.getArgumentArray()).containsExactly(command.messageId(), result);
    assertThat(event.getThrowableProxy()).isNull();
    assertThat(event.getFormattedMessage()).doesNotContain(tenant.toString(), owner.toString(),
        "DO_NOT_LOG", "fixture-publisher", command.canonical());
  }

  @Test
  void ackExceptionLogsNoSuccessAndRetainsRecoveryWithoutRepublishing() throws Exception {
    when(receipts.process(command, false)).thenReturn(CarritoReceiptStore.Result.EMPTIED);
    doThrow(new IOException("DO_NOT_LOG " + owner)).when(channel).basicAck(1L, false);
    listener.consume(message(1L), channel);
    verify(channel, times(1)).basicAck(1L, false);
    verify(recovery, times(1)).recover();
    verifyNoInteractions(rabbit);
    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.getFirst().getFormattedMessage())
        .isEqualTo("Cart delivery unsettled; recovering with backoff");
    assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
  }

  @Test
  void redeliveryStillAcknowledgesEachDeliveryWithoutClaimingSingleDelivery() throws Exception {
    when(receipts.process(command, false)).thenReturn(CarritoReceiptStore.Result.EMPTIED);
    listener.consume(message(1L), channel);
    var repeated = message(2L);
    repeated.getMessageProperties().setRedelivered(true);
    listener.consume(repeated, channel);
    verify(receipts, times(2)).accept(command, false);
    verify(receipts, times(2)).process(command, false);
    verify(channel).basicAck(1L, false);
    verify(channel).basicAck(2L, false);
    verifyNoInteractions(recovery, rabbit);
    assertThat(logs.list).hasSize(2);
    assertThat(logs.list).allSatisfy(log -> assertThat(log.getFormattedMessage())
        .isEqualTo("messageId=" + command.messageId() + " result=EMPTIED ACK_SENT"));
  }

  @Test
  void uncertainReceiptNeverLogsAckSuccess() {
    doThrow(new IllegalStateException("DO_NOT_LOG")).when(receipts).accept(command, false);
    listener.consume(message(1L), channel);
    verifyNoInteractions(channel, rabbit);
    verify(recovery).recover();
    assertThat(logs.list).noneSatisfy(log -> assertThat(log.getFormattedMessage()).contains("ACK_SENT"));
  }

  @Test
  void invalidMessageIdOnDiagnosticHandoffNeverBecomesBusinessAckLog() throws Exception {
    var invalid = message(1L);
    invalid.getMessageProperties().setMessageId("DO_NOT_LOG\n" + owner);
    doAnswer(call -> {
      org.springframework.amqp.rabbit.connection.CorrelationData corr = call.getArgument(3);
      corr.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbit).send(anyString(), anyString(), any(Message.class),
        any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    when(config.confirmTimeout()).thenReturn(java.time.Duration.ofSeconds(1));
    listener.consume(invalid, channel);
    verifyNoInteractions(receipts, recovery);
    verify(channel).basicAck(1L, false);
    assertThat(logs.list).isEmpty();
  }
}
