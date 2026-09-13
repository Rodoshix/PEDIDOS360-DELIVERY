import { lazy, Suspense } from 'react'
import { useParams } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import './pedidos.css'

const DevelopmentPanel = import.meta.env.DEV ? lazy(() => import('./PedidoDetalleDemoPanel.jsx')) : null

export default function PedidoDetallePage() {
  const { id } = useParams()
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId, id])
  return (
    <section className="container pedidos-section">
      <p className="eyebrow">Detalle del pedido</p>
      <h1>Pedido #{id}</h1>
      {DevelopmentPanel
        ? <Suspense key={key} fallback={<p role="status">Cargando…</p>}><DevelopmentPanel pedidoId={id} /></Suspense>
        : <p className="pedidos-card">El detalle del pedido estará disponible al conectar el servicio.</p>}
    </section>
  )
}
