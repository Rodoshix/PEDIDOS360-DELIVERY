package cl.duoc.pedidos360.pagos.messaging;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name="confirmacion_outbox", schema="pagos")
public class OutboxMessage {
    public enum State { PENDING, IN_FLIGHT, PUBLISHED, BLOCKED }
    @Id @Column(name="message_id") UUID messageId;
    @Column(name="pago_id", nullable=false, unique=true, updatable=false) Long pagoId;
    @Column(nullable=false, columnDefinition="text", updatable=false) String payload;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=10) State estado;
    @Column(nullable=false) int attempts;
    @Column(name="next_attempt_at", nullable=false) Instant nextAttemptAt;
    @Column(name="lease_until") Instant leaseUntil;
    @Column(name="lease_token") UUID leaseToken;
    @Column(name="published_at") Instant publishedAt;
    @Column(name="last_error", length=80) String lastError;
    protected OutboxMessage() {}
    OutboxMessage(ConfirmarPedidoPorPago command, String payload) {
        messageId=command.messageId(); pagoId=command.pagoId(); this.payload=payload;
        estado=State.PENDING; nextAttemptAt=command.occurredAt();
    }
    public UUID getMessageId() { return messageId; }
    public String getPayload() { return payload; }
    public State getEstado() { return estado; }
    public int getAttempts() { return attempts; }
}
