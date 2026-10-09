package cl.duoc.pedidos360.carrito.messaging;

import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import cl.duoc.pedidos360.messaging.command.VaciarCarritoPorPedido;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CarritoReceiptStore {
  public enum Result {
    RECEIVED,
    RETRY_READY,
    EMPTIED,
    OMITTED_VERSION_CHANGED,
    REJECTED
  }

  private final JdbcTemplate db;
  private final CarritoRepository carritos;

  public CarritoReceiptStore(JdbcTemplate d, CarritoRepository c) {
    db = d;
    carritos = c;
  }

  @Transactional
  public void accept(VaciarCarritoPorPedido c, boolean retry) {
    if (!retry)
      db.update(
          """
INSERT INTO carrito.vaciado_por_pedido(message_id,tenant_id,entra_object_id,pedido_id,carrito_id,expected_version,canonical_payload)
VALUES(?,?,?,?,?,?,?) ON CONFLICT(message_id) DO NOTHING
""",
          c.messageId(),
          c.propietario().tenantId(),
          c.propietario().entraObjectId(),
          c.pedidoId(),
          c.carritoId(),
          c.expectedCarritoVersion(),
          c.canonical());
    var rows =
        db.queryForList(
            "SELECT canonical_payload,retry_authorized FROM carrito.vaciado_por_pedido WHERE"
                + " message_id=? AND tenant_id=? FOR UPDATE",
            c.messageId(),
            c.propietario().tenantId());
    if (rows.size() != 1
        || !c.canonical().equals(rows.getFirst().get("canonical_payload"))
        || (retry && !Boolean.TRUE.equals(rows.getFirst().get("retry_authorized"))))
      throw new InvalidCartCommand();
  }

  @Transactional
  public Result process(VaciarCarritoPorPedido c, boolean retry) {
    var row = locked(c);
    var state = Result.valueOf((String) row.get("estado"));
    if (state != Result.RECEIVED) return state;
    if (!retry && Boolean.TRUE.equals(row.get("retry_authorized"))) return Result.RETRY_READY;
    var found =
        carritos
            .findByTenantIdAndEntraObjectId(
                c.propietario().tenantId(), c.propietario().entraObjectId())
            .filter(x -> x.getId() == c.carritoId());
    Result outcome;
    if (found.isEmpty() || found.get().getVersion() < c.expectedCarritoVersion())
      outcome = Result.REJECTED;
    else if (found.get().getVersion() > c.expectedCarritoVersion())
      outcome = Result.OMITTED_VERSION_CHANGED;
    else {
      found.get().vaciar();
      carritos.flush();
      outcome = Result.EMPTIED;
    }
    terminal(c, outcome);
    return outcome;
  }

  @Transactional
  public void authorizeRetry(VaciarCarritoPorPedido c) {
    var row = locked(c);
    if (!"RECEIVED".equals(row.get("estado"))) throw new InvalidCartCommand();
    db.update(
        "UPDATE carrito.vaciado_por_pedido SET retry_authorized=true WHERE message_id=?",
        c.messageId());
  }

  @Transactional
  public void reject(VaciarCarritoPorPedido c) {
    var row = locked(c);
    if ("RECEIVED".equals(row.get("estado"))) terminal(c, Result.REJECTED);
  }

  private java.util.Map<String, Object> locked(VaciarCarritoPorPedido c) {
    var rows =
        db.queryForList(
            "SELECT canonical_payload,estado,retry_authorized FROM carrito.vaciado_por_pedido WHERE"
                + " message_id=? AND tenant_id=? FOR UPDATE",
            c.messageId(),
            c.propietario().tenantId());
    if (rows.size() != 1 || !c.canonical().equals(rows.getFirst().get("canonical_payload")))
      throw new InvalidCartCommand();
    return rows.getFirst();
  }

  private void terminal(VaciarCarritoPorPedido c, Result state) {
    db.update(
        "UPDATE carrito.vaciado_por_pedido SET estado=?,finished_at=clock_timestamp() WHERE"
            + " message_id=?",
        state.name(),
        c.messageId());
  }
}
