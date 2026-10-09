package cl.duoc.pedidos360.pagos.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(prefix="pedidos360.messaging",name="coordination-mode",havingValue="RABBITMQ")
public class OutboxDispatcher {
    private static final Logger log=LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxStore store;
    private final PagoConfirmacionPublisher publisher;
    private final JsonMapper json;
    private final RabbitProperties properties;
    public OutboxDispatcher(OutboxStore store,PagoConfirmacionPublisher publisher,JsonMapper json,RabbitProperties properties) {
        this.store=store; this.publisher=publisher; this.json=json; this.properties=properties;
    }
    @Scheduled(fixedDelayString="${pedidos360.messaging.dispatch-interval-ms}")
    public void dispatch() {
        for (int i=0;i<properties.batchSize();i++) {
            var claims=store.claim();
            if (claims.isEmpty()) return;
            var claim=claims.getFirst();
            try {
                var command=json.readValue(claim.payload(),ConfirmarPedidoPorPago.class);
                if (!claim.messageId().equals(command.messageId()) || command.version()!=1
                    || command.pagoId()!=claim.pagoId() || command.pedidoId()!=claim.pedidoId()
                    || !"ConfirmarPedidoPorPago".equals(command.type()) || command.occurredAt()==null
                    || command.pedidoId()<1 || command.pagoId()<1) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                if (!store.block(claim)) {
                    log.warn("Outbox messageId={} result=BLOCK_NOT_SETTLED",claim.messageId());
                    return; // Durable lease/state retains work; never claim it again in this dispatch.
                }
                log.error("Outbox messageId={} BLOCKED INVALID_PAYLOAD",claim.messageId());
                continue;
            }
            String error=null;
            try { publisher.publish(claim); }
            catch (Exception failure) {
                error=failure instanceof PublicationRejectedException rejected
                    ?rejected.reason().name():failure.getClass().getSimpleName();
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            // If completion fails, expired lease enables safe republication with the same messageId.
            if (!store.finish(claim,error)) {
                log.warn("Outbox messageId={} result={} completion=NOT_SETTLED",claim.messageId(),
                    error==null?"BROKER_CONFIRMED":"PUBLICATION_FAILED");
                // Do not send again after broker acceptance. Existing lease recovery retains
                // the same messageId/payload; a newer lease is fenced and historical work retained.
                return;
            }
            log.info("Outbox messageId={} result={}",claim.messageId(),error==null?"PUBLISHED":error);
        }
    }
}
