package cl.duoc.pedidos360.pedidos.messaging.carrito;

import cl.duoc.pedidos360.messaging.command.*;
import cl.duoc.pedidos360.pedidos.entity.Pedido;
import cl.duoc.pedidos360.pedidos.security.TenantSistema;
import cl.duoc.pedidos360.pedidos.service.CarritoSnapshotClient.Snapshot;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(
    prefix = "pedidos360.messaging.carrito",
    name = "mode",
    havingValue = "RABBITMQ")
public class CarritoOutboxStore {
  public record Claim(UUID id, UUID token, String payload) {
    @Override
    public String toString() {
      return "Claim[messageId=" + id + "]";
    }
  }

  private final JdbcTemplate db;
  private final CarritoCommandProperties config;
  private final TenantSistema tenant;

  public CarritoOutboxStore(JdbcTemplate db, CarritoCommandProperties c, TenantSistema t) {
    this.db = db;
    config = c;
    tenant = t;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void crear(Pedido p, Snapshot s) {
    if (!p.esNuevoAutenticado()
        || !p.getTenantId().equals(s.tenant())
        || !tenant.obtener().equals(s.tenant()))
      throw new IllegalArgumentException("Ineligible order");
    var cmd =
        new VaciarCarritoPorPedido(
            UUID.randomUUID(),
            "VaciarCarritoPorPedido",
            1,
            Instant.now(),
            p.getId(),
            s.carritoId(),
            s.version(),
            new VaciarCarritoPorPedido.Propietario(s.tenant(), s.oid()));
    db.update(
        "INSERT INTO"
            + " pedidos.carrito_vaciado_outbox(message_id,pedido_id,tenant_id,entra_object_id,carrito_id,expected_version,payload)"
            + " VALUES(?,?,?,?,?,?,?)",
        cmd.messageId(),
        p.getId(),
        s.tenant(),
        s.oid(),
        s.carritoId(),
        s.version(),
        cmd.canonical());
  }

  @Transactional
  public List<Claim> claim() {
    UUID token = UUID.randomUUID();
    return db.query(
        """
UPDATE pedidos.carrito_vaciado_outbox o SET estado='IN_FLIGHT',lease_token=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),attempts=attempts+1
WHERE message_id IN (SELECT o2.message_id FROM pedidos.carrito_vaciado_outbox o2 JOIN pedidos.pedidos p ON p.id=o2.pedido_id
 WHERE p.tenant_id=? AND p.tenant_origin='AUTHENTICATED_NEW' AND o2.tenant_id=p.tenant_id
   AND ((o2.estado='PENDING' AND o2.next_attempt_at<=clock_timestamp()) OR (o2.estado='IN_FLIGHT' AND o2.lease_until<=clock_timestamp()))
 ORDER BY o2.next_attempt_at LIMIT 1 FOR UPDATE OF o2 SKIP LOCKED)
RETURNING message_id,payload
""",
        (rs, n) -> new Claim(rs.getObject(1, UUID.class), token, rs.getString(2)),
        token,
        config.lease().toMillis(),
        tenant.obtener());
  }

  @Transactional
  public boolean finish(Claim c, String error) {
    return db.update(
            """
UPDATE pedidos.carrito_vaciado_outbox o SET estado=?,lease_token=NULL,lease_until=NULL,last_error=?,
 published_at=CASE WHEN CAST(? AS TEXT) IS NULL THEN clock_timestamp() ELSE NULL END,
 next_attempt_at=clock_timestamp()+(?*interval '1 millisecond')
WHERE message_id=? AND lease_token=? AND estado='IN_FLIGHT' AND lease_until>clock_timestamp() AND payload=? AND tenant_id=?
 AND EXISTS(SELECT 1 FROM pedidos.pedidos p WHERE p.id=o.pedido_id AND p.tenant_id=o.tenant_id AND p.tenant_origin='AUTHENTICATED_NEW')
""",
            error == null ? "PUBLISHED" : "PENDING",
            error,
            error,
            config.publisherRetryDelay().toMillis(),
            c.id(),
            c.token(),
            c.payload(),
            tenant.obtener())
        == 1;
  }
}
