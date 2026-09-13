import { lazy, Suspense } from 'react'
import { useAuthSession } from '../../auth/useAuthSession.js'
import './restaurantes.css'

const DevelopmentPanel = import.meta.env.DEV
  ? lazy(() => import('./RestaurantesAdminDemoPanel.jsx'))
  : null

export default function RestaurantesAdminPage() {
  const { account } = useAuthSession()

  const key = JSON.stringify([
    account.tenantId,
    account.homeAccountId,
    account.localAccountId,
  ])

  return (
    <section className="container restaurantes-section">
      <p className="eyebrow">Administración del catálogo</p>
      <h1>Administrar restaurantes</h1>
      <p>
        Crea, edita y desactiva restaurantes registrados en Pedidos360.
      </p>

      {DevelopmentPanel ? (
        <Suspense fallback={<p role="status">Cargando administración…</p>}>
          <DevelopmentPanel key={key} />
        </Suspense>
      ) : (
        <p className="restaurantes-card">
          La administración de restaurantes estará disponible al conectar el servicio.
        </p>
      )}
    </section>
  )
}