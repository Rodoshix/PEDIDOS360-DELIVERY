import { useAuthSession } from '../../auth/useAuthSession.js'
import RealCatalogPanel from './RealCatalogPanel.jsx'
import '../carrito/cart.css'

export default function RestaurantesPage() {
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container cart-section">
      <p className="eyebrow">Explora y elige</p>
      <h1>Restaurantes</h1>
      <p>Selecciona un restaurante y agrega productos a tu carrito.</p>
      <RealCatalogPanel key={key} />
    </section>
  )
}
