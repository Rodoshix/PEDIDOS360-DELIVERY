import { useParams } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import RealPagoPanel from './RealPagoPanel.jsx'
import './pagos.css'

export default function PagoPage() {
  const { pedidoId } = useParams()
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId, pedidoId])
  return (
    <section className="container pagos-section">
      <p className="eyebrow">Pago del pedido</p>
      <h1>Pagar pedido</h1>
      <p>Registra el pago de tu pedido.</p>
      <RealPagoPanel key={key} pedidoId={pedidoId} />
    </section>
  )
}
