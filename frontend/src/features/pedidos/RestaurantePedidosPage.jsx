import { lazy, Suspense } from 'react'
import { useAuthSession } from '../../auth/useAuthSession.js'
import './pedidos.css'

const DevelopmentPanel = import.meta.env.DEV ? lazy(() => import('./RestaurantePedidosDemoPanel.jsx')) : null

export default function RestaurantePedidosPage() {
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container pedidos-section">
      <p className="eyebrow">Gestión del restaurante</p>
      <h1>Pedidos del restaurante</h1>
      <p>Revisa y actualiza el estado de los pedidos en preparación.</p>
      {DevelopmentPanel
        ? <Suspense key={key} fallback={<p role="status">Cargando…</p>}><DevelopmentPanel /></Suspense>
        : <p className="pedidos-card">La gestión de pedidos estará disponible al conectar el servicio.</p>}
    </section>
  )
}
