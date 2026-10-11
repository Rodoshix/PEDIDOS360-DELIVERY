package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.identity.IdentityProofException;
import cl.duoc.pedidos360.messaging.identity.IdentityProofException.Reason;
import cl.duoc.pedidos360.messaging.relay.HandoffFailureException;
import cl.duoc.pedidos360.messaging.relay.HandoffPublisher;
import cl.duoc.pedidos360.messaging.relay.QueryDeadlineGuard;
import cl.duoc.pedidos360.messaging.relay.QueryFailureHandler;
import cl.duoc.pedidos360.messaging.relay.QueryReplyPublisher;

/** Captura local de logs y transporte inyectado; no son pruebas de AWS ni de particion de red. */
class IdentityProofDeadlineLoggingTests {
    private static final Instant NOW = Instant.parse("2026-10-11T03:29:36Z");
    private static final UUID MESSAGE = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String CORRELATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String PRIVATE_FIXTURE = "synthetic-not-a-token-personal-data-password-signature";

    private final MessagingProperties properties = MessagingContractTests.propiedades();
    private final HandoffPublisher handoff = mock(HandoffPublisher.class);
    private final QueryReplyPublisher replies = mock(QueryReplyPublisher.class);
    private final MessageProperties original = new MessageProperties();
    private final Logger logger = (Logger) LoggerFactory.getLogger(QueryFailureHandler.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void captureLocally() {
        when(replies.ahora()).thenReturn(NOW);
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.ERROR);
        logger.setAdditive(false);
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(captured);
        captured.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    private QueryFailureHandler handler(Domain domain) {
        return new QueryFailureHandler(handoff, replies, properties, QueryTopology.of(properties, domain));
    }

    private RequestEnvelope request(Domain domain, Instant deadline) {
        var payload = JsonMapper.builder().build().createObjectNode().put("fixture", PRIVATE_FIXTURE);
        return RequestEnvelope.crear(MESSAGE, QueryTopology.of(properties, domain).routingKey(),
                payload, PRIVATE_FIXTURE, NOW.minusSeconds(2), deadline);
    }

    private ILoggingEvent diagnostic(Reason reason, String correlation) {
        assertThat(captured.list).hasSize(1);
        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getFormattedMessage()).isEqualTo("Consulta messageId=" + MESSAGE
                + " correlationId=" + correlation
                + " plazo vencido: no se ejecuta ni se reintenta; IdentityProofException reason=" + reason);
        assertThat(event.getArgumentArray()).containsExactly(MESSAGE.toString(), correlation, reason);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getTimeStamp()).isPositive();
        assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_FIXTURE);
        return event;
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void everyReasonIsLoggedWithoutExceptionMessageActorOrPayload(Reason reason) {
        var request = request(Domain.USUARIOS, NOW);
        var failure = spy(new IdentityProofException(reason));
        doReturn(PRIVATE_FIXTURE).when(failure).getMessage();
        assertThat(handler(Domain.USUARIOS).gestionar(request, 0, failure, CORRELATION,
                properties.queues().responses(), true, original)).isEqualTo(QueryFailureHandler.Resultado.DLQ);
        diagnostic(reason, CORRELATION);
        verify(failure, never()).getMessage();
        verify(handoff).aDlq(request, 0, "IdentityProofException", original);
        verifyNoMoreInteractions(handoff);
        // No reloj/guard adicional para medir el diagnostico; solo el chequeo previo ya existente del handoff.
        verify(replies).ahora();
        verifyNoMoreInteractions(replies);
    }

    @ParameterizedTest
    @ValueSource(strings = {"envelope", "guard"})
    void reasonIsRetainedWhenExpiryIsDetectedByTheEnvelopeOrGuard(String expiry) {
        var request = request(Domain.USUARIOS, expiry.equals("envelope") ? NOW : NOW.plusSeconds(3));
        QueryDeadlineGuard guard = null;
        if (expiry.equals("guard")) {
            guard = new QueryDeadlineGuard(request.expiresAt(), Clock.fixed(NOW, ZoneOffset.UTC), () -> 0);
            guard.invalidate();
        }
        assertThat(handler(Domain.USUARIOS).gestionar(request, 0, new IdentityProofException(Reason.VENCIDA),
                CORRELATION, properties.queues().responses(), false, original, guard))
                .isEqualTo(QueryFailureHandler.Resultado.DLQ);
        diagnostic(Reason.VENCIDA, CORRELATION);
        verify(handoff).aDlq(request, 0, "IdentityProofException", original);
        verifyNoMoreInteractions(handoff);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {PRIVATE_FIXTURE, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\nforged-log", "1-1-1-1-1"})
    void malformedCorrelationCannotInjectSensitiveContentIntoTheNewLog(String correlation) {
        var request = request(Domain.USUARIOS, NOW);
        handler(Domain.USUARIOS).gestionar(request, 0, new IdentityProofException(Reason.VINCULO),
                correlation, properties.queues().responses(), true, original);
        diagnostic(Reason.VINCULO, "DESCONOCIDO");
        assertThat(captured.list.getFirst().getFormattedMessage()).doesNotContain("forged-log");
    }

    @Test
    void failedDlqHandoffKeepsTheOriginalUnsettledWithoutRetry() {
        var request = request(Domain.USUARIOS, NOW);
        doThrow(new HandoffFailureException("synthetic confirm uncertainty",
                HandoffFailureException.ResultadoPublicacion.INCIERTO))
                .when(handoff).aDlq(request, 0, "IdentityProofException", original);
        assertThat(handler(Domain.USUARIOS).gestionar(request, 0, new IdentityProofException(Reason.VENCIDA),
                CORRELATION, properties.queues().responses(), true, original))
                .isEqualTo(QueryFailureHandler.Resultado.SIN_CONFIRMAR);
        assertThat(captured.list.getFirst().getFormattedMessage()).contains("reason=VENCIDA");
        verify(handoff).aDlq(request, 0, "IdentityProofException", original);
        verifyNoMoreInteractions(handoff);
    }

    @ParameterizedTest
    @EnumSource(value = Domain.class, names = {"PAGOS", "RESTAURANTES", "PRODUCTOS"})
    void otherDomainsKeepTheirExistingDeadlineLog(Domain domain) {
        var request = request(domain, NOW);
        handler(domain).gestionar(request, 0, new IdentityProofException(Reason.VINCULO),
                CORRELATION, properties.queues().responses(), true, original);
        assertThat(captured.list.getFirst().getFormattedMessage()).endsWith("; IdentityProofException")
                .doesNotContain("reason=");
        verify(handoff).aDlq(request, 0, "IdentityProofException", original);
    }

    @Test
    void otherUsuariosFailuresKeepTheirExistingDeadlineLog() {
        var request = request(Domain.USUARIOS, NOW);
        handler(Domain.USUARIOS).gestionar(request, 0, new IllegalStateException(PRIVATE_FIXTURE),
                CORRELATION, properties.queues().responses(), true, original);
        assertThat(captured.list.getFirst().getFormattedMessage()).endsWith("; IllegalStateException")
                .doesNotContain("reason=", PRIVATE_FIXTURE);
    }
}
