import { ETIQUETAS_ESTADO, transicionesPermitidas } from './pedidoOperaciones.js'
import { formatClp, formatFecha } from './pedidoFormat.js'
import { EtiquetaEstado } from './PedidoResumen.jsx'

export default function PedidoDetalle({ pedido, disabled, saving, onTransition }) {
  const siguientes = transicionesPermitidas(pedido.estado)
  return (
    <section className="pedidos-card" aria-labelledby="pedido-detalle-heading">
      <h2 id="pedido-detalle-heading">Pedido #{pedido.pedidoId}</h2>
      <p><EtiquetaEstado estado={pedido.estado} /> · {formatFecha(pedido.fechaCreacion)}</p>
      <p>Entrega: {pedido.direccionEntrega}</p>
      <table className="pedidos-lineas">
        <caption className="pedidos-notice">Productos del pedido</caption>
        <thead>
          <tr><th scope="col">Producto</th><th scope="col">Cantidad</th><th scope="col">Precio</th><th scope="col">Subtotal</th></tr>
        </thead>
        <tbody>
          {pedido.lineas.map(linea => (
            <tr key={linea.lineaId}>
              <td>Producto {linea.productoId}</td>
              <td>{linea.cantidad}</td>
              <td>{formatClp(linea.precioUnitario)}</td>
              <td>{formatClp(linea.subtotal)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" colSpan={3}>Total</th>
            <td className="pedidos-total">{formatClp(pedido.total)}</td>
          </tr>
        </tfoot>
      </table>
      {siguientes.length > 0 && (
        <div className="pedidos-actions">
          {siguientes.map(estado => (
            <button key={estado} type="button" className="button button--secondary" disabled={disabled || saving}
              onClick={() => onTransition(estado)}>
              {saving ? 'Aplicando…' : `Pasar a ${ETIQUETAS_ESTADO[estado]}`}
            </button>
          ))}
        </div>
      )}
      {siguientes.length === 0 && <p className="pedidos-notice">Este pedido está en un estado final.</p>}
    </section>
  )
}
