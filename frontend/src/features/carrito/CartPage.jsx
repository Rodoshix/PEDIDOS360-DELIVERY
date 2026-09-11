import { lazy, Suspense } from 'react'
import { useAuthSession } from '../../auth/useAuthSession.js'
import CartPending from './CartPending.jsx'
import './cart.css'

const DevelopmentCart = import.meta.env.DEV ? lazy(() => import('./CartDemoPanel.jsx')) : null

export default function CartPage() {
  const { account } = useAuthSession()
  const cartKey = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container cart-section">
      <p className="eyebrow">Tus productos en Pedidos360</p>
      <h1>Mi carrito</h1>
      <p>Revisa los productos y su total antes de continuar con tu pedido.</p>
      {DevelopmentCart ? <Suspense key={cartKey} fallback={<CartPending />}>
        <DevelopmentCart />
      </Suspense> : <CartPending />}
    </section>
  )
}
