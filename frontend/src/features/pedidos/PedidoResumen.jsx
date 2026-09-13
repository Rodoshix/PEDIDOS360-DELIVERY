import { ETIQUETAS_ESTADO, ESTADOS_TERMINALES } from './pedidoOperaciones.js'
import { formatClp, formatFecha } from './pedidoFormat.js'

export function EtiquetaEstado({ estado }) {
  return <span className="pedidos-estado">{ETIQUETAS_ESTADO[estado] ?? estado}</span>
}

export function ResumenPedido({ pedido, onVerDetalle }) {
  const finalizado = ESTADOS_TERMINALES.includes(pedido.estado)
  return (
    <li className="pedidos-item">
      <div>
        <p>
          <strong>Pedido #{pedido.pedidoId}</strong> · <EtiquetaEstado estado={pedido.estado} />
        </p>
        <p>{pedido.direccionEntrega}</p>
        <p>
          {formatFecha(pedido.fechaCreacion)} · <span className="pedidos-total">{formatClp(pedido.total)}</span>
        </p>
      </div>
      <div className="pedidos-actions">
        <button type="button" className="button button--secondary" onClick={() => onVerDetalle(pedido.pedidoId)}>
          Ver detalle
        </button>
        {!finalizado && <span className="pedidos-notice">En curso</span>}
      </div>
    </li>
  )
}
