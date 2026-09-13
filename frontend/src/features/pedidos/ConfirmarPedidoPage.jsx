import { lazy, Suspense } from 'react'
import { useAuthSession } from '../../auth/useAuthSession.js'
import './pedidos.css'

const DevelopmentPanel = import.meta.env.DEV ? lazy(() => import('./ConfirmarPedidoDemoPanel.jsx')) : null

export default function ConfirmarPedidoPage() {
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container pedidos-section">
      <p className="eyebrow">Cierre de compra</p>
      <h1>Confirmar pedido</h1>
      <p>Revisa los datos de entrega y confirma tu pedido.</p>
      {DevelopmentPanel
        ? <Suspense key={key} fallback={<p role="status">Cargando…</p>}><DevelopmentPanel /></Suspense>
        : <p className="pedidos-card">La confirmación de pedido estará disponible al conectar el servicio.</p>}
    </section>
  )
}
