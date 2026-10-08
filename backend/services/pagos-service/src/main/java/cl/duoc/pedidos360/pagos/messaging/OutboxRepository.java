package cl.duoc.pedidos360.pagos.messaging;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxMessage,UUID> {
    @Query(value="""
        SELECT o.* FROM pagos.confirmacion_outbox o JOIN pagos.pagos p ON p.id=o.pago_id
        WHERE p.tenant_id=:tenant AND p.tenant_origin='AUTHENTICATED_NEW' AND ((o.estado='PENDING' AND o.next_attempt_at <= :now)
           OR (o.estado='IN_FLIGHT' AND o.lease_until <= :now))
        ORDER BY o.next_attempt_at LIMIT :batch FOR UPDATE OF o SKIP LOCKED
        """, nativeQuery=true)
    List<OutboxMessage> claimable(@Param("now") Instant now, @Param("batch") int batch, @Param("tenant") UUID tenant);

    @Modifying
    @Query(value="""
        UPDATE pagos.confirmacion_outbox
        SET estado=:state, published_at=:published, next_attempt_at=:next,
            lease_until=NULL, lease_token=NULL, last_error=:error
        WHERE message_id=:id AND lease_token=:token AND estado='IN_FLIGHT'
        """,nativeQuery=true)
    int complete(@Param("id") UUID id, @Param("token") UUID token,
        @Param("state") String state, @Param("published") Instant published,
        @Param("next") Instant next, @Param("error") String error);
}
