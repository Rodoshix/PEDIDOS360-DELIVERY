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
    private final cl.duoc.pedidos360.pagos.security.TenantSistema tenant;
    public record Claim(UUID messageId, Long pagoId, Long pedidoId, UUID tenantId, UUID token, String payload) {
        public Claim {
            java.util.Objects.requireNonNull(messageId);
            java.util.Objects.requireNonNull(tenantId);
            java.util.Objects.requireNonNull(token);
            java.util.Objects.requireNonNull(payload);
            if (pagoId == null || pagoId < 1) throw new IllegalArgumentException("Invalid payment association");
            if (pedidoId == null || pedidoId < 1) throw new IllegalArgumentException("Invalid order association");
        }
    }
    public OutboxStore(OutboxRepository repository, RabbitProperties properties, JsonMapper json, cl.duoc.pedidos360.pagos.security.TenantSistema tenant) {
        this.repository=repository; this.properties=properties; this.json=json; this.tenant=tenant;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void crear(Pago pago) {
        if (!pago.estaActivo() || !pago.esNuevoAutenticado() || !tenant.obtener().equals(pago.getTenantId())) throw new IllegalArgumentException("Payment is not eligible");
        var command=ConfirmarPedidoPorPago.crear(pago.getPedidoId(),pago.getId());
        repository.saveAndFlush(new OutboxMessage(command,json.writeValueAsString(command)));
    }
    @Transactional
    public List<Claim> claim() {
        Instant now=Instant.now();
        UUID authorizedTenant=tenant.obtener();
        return repository.claimable(now, 1, authorizedTenant).stream().map(row -> {
            row.estado=OutboxMessage.State.IN_FLIGHT; row.attempts++;
            row.leaseToken=UUID.randomUUID(); row.leaseUntil=now.plus(properties.lease());
            return new Claim(row.messageId,row.pagoId,repository.pedidoIdReclamado(row.messageId,authorizedTenant),
                authorizedTenant,row.leaseToken,row.payload);
        }).toList();
    }
    @Transactional
    public boolean finish(Claim claim, String error) {
        Instant now=Instant.now();
        return repository.complete(claim.messageId(),claim.token(),claim.pagoId(),claim.pedidoId(),claim.payload(),
            claim.tenantId(),tenant.obtener(),error==null?"PUBLISHED":"PENDING",
            error==null?now:null,now.plus(properties.publisherRetryDelay()),error)==1;
    }
    @Transactional
    public boolean block(Claim claim) {
        Instant now=Instant.now();
        return repository.complete(claim.messageId(),claim.token(),claim.pagoId(),claim.pedidoId(),claim.payload(),
            claim.tenantId(),tenant.obtener(),"BLOCKED",null,now,"INVALID_PAYLOAD")==1;
    }
}
