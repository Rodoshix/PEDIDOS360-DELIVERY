import { useAuthSession } from '../../auth/useAuthSession.js'
import RealConfirmarPedidoPanel from './RealConfirmarPedidoPanel.jsx'
import './pedidos.css'

export default function ConfirmarPedidoPage() {
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container pedidos-section">
      <h1>Confirmar pedido</h1>
      <p>Revisa los datos de entrega y confirma tu pedido.</p>
      <RealConfirmarPedidoPanel key={key} />
    </section>
  )
}
