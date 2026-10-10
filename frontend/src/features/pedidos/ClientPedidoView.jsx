import Button from '../../components/ui/Button.jsx'
import Badge from '../../components/ui/Badge.jsx'
import { ETIQUETAS_ESTADO, ESTADOS_TERMINALES } from './pedidoOperaciones.js'
import { formatClp, formatFecha } from './pedidoFormat.js'

// Solo presentación CLIENTE: no transiciones, consultas ni datos adicionales.
export function ClientPedidoSummary({ pedido, onVerDetalle }) {
  return <li className="commerce-order">
    <div className="commerce-order__heading"><h2>Pedido #{pedido.pedidoId}</h2>
      <Badge>{ETIQUETAS_ESTADO[pedido.estado]}</Badge></div>
    <p className="commerce-date">{formatFecha(pedido.fechaCreacion)}</p>
    <p className="commerce-address">{pedido.direccionEntrega}</p>
    <div className="commerce-order__footer"><strong className="commerce-total">{formatClp(pedido.total)}</strong>
      <div className="pedidos-actions"><Button variant="secondary" aria-label={`Ver detalle del pedido ${pedido.pedidoId}`}
        onClick={() => onVerDetalle(pedido.pedidoId)}>Ver detalle</Button>
        {!ESTADOS_TERMINALES.includes(pedido.estado) && <span className="pedidos-notice">En curso</span>}</div></div>
  </li>
}

export function ClientPedidoDetail({ pedido }) {
  return <section className="pedidos-card" aria-labelledby="pedido-detalle-heading">
    <div className="commerce-order__heading"><h2 id="pedido-detalle-heading">Pedido #{pedido.pedidoId}</h2>
      <Badge>{ETIQUETAS_ESTADO[pedido.estado]}</Badge></div>
    <p className="commerce-date">{formatFecha(pedido.fechaCreacion)}</p>
    <h3>Dirección de entrega</h3><p className="commerce-address">{pedido.direccionEntrega}</p>
    <h3>Productos del pedido</h3>
    <ul className="commerce-lines">{pedido.lineas.map(linea => <li key={linea.lineaId}>
      <div><strong>Producto #{linea.productoId}</strong><p>Cantidad: {linea.cantidad} · Precio unitario: {formatClp(linea.precioUnitario)}</p></div>
      <strong>{formatClp(linea.subtotal)}</strong>
    </li>)}</ul>
    <div className="commerce-total-row"><span>Total</span><strong>{formatClp(pedido.total)}</strong></div>
  </section>
}
