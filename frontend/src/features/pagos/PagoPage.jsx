import { lazy, Suspense } from 'react'
import { Link, useParams } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import './pagos.css'

const DevelopmentPanel = import.meta.env.DEV ? lazy(() => import('./PagoDemoPanel.jsx')) : null

export default function PagoPage() {
  const { pedidoId } = useParams()
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId, pedidoId])
  return (
    <section className="container pagos-section">
      <p className="eyebrow">Pago del pedido</p>
      <h1>Pagar pedido</h1>
      <p>Registra el pago simulado de tu pedido.</p>
      {DevelopmentPanel
        ? <Suspense key={key} fallback={<p role="status">Cargando…</p>}><DevelopmentPanel pedidoId={Number(pedidoId)} /></Suspense>
        : <p className="pagos-card">El pago estará disponible al conectar el servicio.</p>}
      <p><Link to={ROUTE_PATHS.misPedidos}>← Volver a mis pedidos</Link></p>
    </section>
  )
}
