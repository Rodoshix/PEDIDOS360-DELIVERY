import { useAuthSession } from '../../auth/useAuthSession.js'
import RealCatalogPanel from './RealCatalogPanel.jsx'
import '../carrito/cart.css'
import './catalog.css'

export default function RestaurantesPage() {
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container cart-section client-catalog-page">
      <h1>Restaurantes</h1>
      <p>Selecciona un restaurante y agrega productos a tu carrito.</p>
      <RealCatalogPanel key={key} />
    </section>
  )
}
