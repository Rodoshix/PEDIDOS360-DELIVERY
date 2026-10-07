package cl.duoc.pedidos360.pagos.messaging;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxMessage,UUID> {
    @Query(value="""
        SELECT * FROM pagos.confirmacion_outbox
        WHERE (estado='PENDING' AND next_attempt_at <= :now)
           OR (estado='IN_FLIGHT' AND lease_until <= :now)
        ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED
        """, nativeQuery=true)
    List<OutboxMessage> claimable(@Param("now") Instant now, @Param("batch") int batch);

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
