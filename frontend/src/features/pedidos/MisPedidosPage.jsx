import { useAuthSession } from '../../auth/useAuthSession.js'
import RealMisPedidosPanel from './RealMisPedidosPanel.jsx'
import './pedidos.css'

export default function MisPedidosPage() {
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container pedidos-section">
      <p className="eyebrow">Tus pedidos en Pedidos360</p>
      <h1>Mis pedidos</h1>
      <p>Revisa el historial y el estado de tus pedidos.</p>
      <RealMisPedidosPanel key={key} />
    </section>
  )
}
