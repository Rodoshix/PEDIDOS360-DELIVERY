package cl.duoc.pedidos360.pagos.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import cl.duoc.pedidos360.pagos.entity.Pago;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Transactions end before any broker I/O. Lease token fences stale publishers. */
@Service
public class OutboxStore {
    private final OutboxRepository repository;
    private final RabbitProperties properties;
    private final JsonMapper json;
    public record Claim(UUID messageId, UUID token, String payload) {}
    public OutboxStore(OutboxRepository repository, RabbitProperties properties, JsonMapper json) {
        this.repository=repository; this.properties=properties; this.json=json;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void crear(Pago pago) {
        if (!pago.estaActivo()) throw new IllegalArgumentException("Payment is not eligible");
        var command=ConfirmarPedidoPorPago.crear(pago.getPedidoId(),pago.getId());
        repository.saveAndFlush(new OutboxMessage(command,json.writeValueAsString(command)));
    }
    @Transactional
    public List<Claim> claim() {
        Instant now=Instant.now();
        return repository.claimable(now, 1).stream().map(row -> {
            row.estado=OutboxMessage.State.IN_FLIGHT; row.attempts++;
            row.leaseToken=UUID.randomUUID(); row.leaseUntil=now.plus(properties.lease());
            return new Claim(row.messageId,row.leaseToken,row.payload);
        }).toList();
    }
    @Transactional
    public void finish(Claim claim, String error) {
        Instant now=Instant.now();
        repository.complete(claim.messageId(),claim.token(),error==null?"PUBLISHED":"PENDING",
            error==null?now:null,now.plus(properties.publisherRetryDelay()),error);
    }
    @Transactional
    public void block(Claim claim) {
        repository.complete(claim.messageId(),claim.token(),"BLOCKED",null,Instant.now(),"INVALID_PAYLOAD");
    }
}
