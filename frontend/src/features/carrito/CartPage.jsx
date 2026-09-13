import { useAuthSession } from '../../auth/useAuthSession.js'
import RealCartPanel from './RealCartPanel.jsx'
import './cart.css'

export default function CartPage() {
  const { account } = useAuthSession()
  const cartKey = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container cart-section">
      <p className="eyebrow">Tus productos en Pedidos360</p>
      <h1>Mi carrito</h1>
      <p>Revisa los productos y su total antes de continuar con tu pedido.</p>
      <RealCartPanel key={cartKey} />
    </section>
  )
}
