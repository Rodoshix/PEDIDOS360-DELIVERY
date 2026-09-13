import { useParams } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import RealPedidoDetallePanel from './RealPedidoDetallePanel.jsx'
import './pedidos.css'

export default function PedidoDetallePage() {
  const { id } = useParams()
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId, id])
  return (
    <section className="container pedidos-section">
      <p className="eyebrow">Detalle del pedido</p>
      <h1>Pedido #{id}</h1>
      <RealPedidoDetallePanel key={key} pedidoId={id} />
    </section>
  )
}
