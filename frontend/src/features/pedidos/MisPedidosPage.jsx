import { lazy, Suspense } from 'react'
import { useAuthSession } from '../../auth/useAuthSession.js'

const DevelopmentPanel = import.meta.env.DEV ? lazy(() => import('./MisPedidosDemoPanel.jsx')) : null

export default function MisPedidosPage() {
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container pedidos-section">
      <p className="eyebrow">Tus pedidos en Pedidos360</p>
      <h1>Mis pedidos</h1>
      <p>Revisa el historial y el estado de tus pedidos.</p>
      {DevelopmentPanel
        ? <Suspense key={key} fallback={<p role="status">Cargando…</p>}><DevelopmentPanel /></Suspense>
        : <p className="pedidos-card">La consulta de pedidos estará disponible al conectar el servicio.</p>}
    </section>
  )
}
