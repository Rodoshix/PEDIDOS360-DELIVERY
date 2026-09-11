import { useEffect, useRef, useState } from 'react'
import CartPending from './CartPending.jsx'
import CartSummary from './CartSummary.jsx'
import { createEmptyExampleCart, createExampleCart } from './cartDemo.js'

export default function CartDemoPanel() {
  const [cart, setCart] = useState(null)
  const notice = useRef(null)
  const interacted = useRef(false)
  useEffect(() => { if (interacted.current) notice.current?.focus() }, [cart])

  function show(next) {
    interacted.current = true
    setCart(next)
  }

  return (
    <>
      <p ref={notice} tabIndex={-1} role="status" className="cart-notice">
        {cart ? 'Datos ficticios: este carrito es un ejemplo local, no tu carrito real.'
          : 'Sin consulta real. Los ejemplos se muestran solo si los eliges.'}
      </p>
      {cart ? <CartSummary cart={cart} /> : <CartPending />}
      <section className="cart-card cart-demo" aria-labelledby="cart-demo-heading">
        <h2 id="cart-demo-heading">Pruebas de desarrollo</h2>
        <p>Estos controles no consultan ni modifican el backend. Los ejemplos desaparecen al salir de la página, cerrar sesión o cambiar de cuenta.</p>
        <div className="cart-actions">
          <button type="button" className="button button--primary" onClick={() => show(createExampleCart())}>Ver carrito de ejemplo</button>
          <button type="button" className="button button--secondary" onClick={() => show(createEmptyExampleCart())}>Ver ejemplo vacío</button>
          {cart && <button type="button" className="button button--secondary" onClick={() => show(null)}>Quitar ejemplo</button>}
        </div>
        <p>Primer bloque: solo lectura. Agregar productos, cambiar cantidades, eliminar y vaciar se implementarán en el siguiente.</p>
      </section>
    </>
  )
}
