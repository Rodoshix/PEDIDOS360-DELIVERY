import { useEffect, useRef, useState } from 'react'
import CartPending from './CartPending.jsx'
import CartSummary from './CartSummary.jsx'
import { createEmptyExampleCart, createExampleCart } from './cartDemo.js'
import CartCatalogForm from './CartCatalogForm.jsx'
import { CART_CATALOG } from './cartCatalogDemo.js'
import { addCartProduct, changeCartQuantity, removeCartProduct, clearCart, CartValidationError } from './cartOperations.js'

export default function CartDemoPanel() {
  const [cart, setCart] = useState(null)
  const [viewVersion, setViewVersion] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [confirmation, setConfirmation] = useState(null)
  const notice = useRef(null)
  const errorNotice = useRef(null)
  const confirmNotice = useRef(null)
  const originButton = useRef(null)
  const restoreFocus = useRef(null)
  const interacted = useRef(false)
  useEffect(() => { if (interacted.current) notice.current?.focus() }, [cart, message])
  useEffect(() => { if (error) errorNotice.current?.focus() }, [error])
  useEffect(() => {
    if (confirmation) confirmNotice.current?.focus()
    else if (restoreFocus.current) {
      restoreFocus.current.focus()
      restoreFocus.current = null
    }
  }, [confirmation])

  function show(next) {
    if (confirmation) return
    interacted.current = true
    setError('')
    setMessage('')
    setViewVersion(value => value + 1)
    setCart(next)
  }

  function apply(operation, success) {
    try {
      const next = operation(cart)
      interacted.current = true
      setError('')
      setCart(next)
      setMessage(`${success} Solo cambió el ejemplo en memoria; no se guardó en Carrito.`)
      return true
    } catch (failure) {
      setMessage('')
      setError(failure instanceof CartValidationError ? failure.message : 'No se pudo modificar el ejemplo. No se aplicaron cambios.')
      errorNotice.current?.focus()
      return false
    }
  }

  function ask(action, button) {
    if (confirmation) return
    originButton.current = button
    setConfirmation(action)
  }

  function confirm() {
    const action = confirmation
    if (!action) return
    apply(current => action.type === 'clear' ? clearCart(current) : removeCartProduct(current, action.productoId),
      action.type === 'clear' ? 'Ejemplo vaciado.' : 'Producto eliminado del ejemplo.')
    setConfirmation(null)
  }

  return (
    <>
      <p ref={notice} tabIndex={-1} role="status" className="cart-notice">
        {message || (cart ? 'Datos ficticios: este carrito es un ejemplo local, no tu carrito real.'
          : 'Sin consulta real. Los ejemplos se muestran solo si los eliges.')}
      </p>
      {error && <p ref={errorNotice} tabIndex={-1} role="alert" className="cart-error">{error}</p>}
      {confirmation && <section ref={confirmNotice} tabIndex={-1} className="cart-card cart-confirm" role="group" aria-labelledby="cart-confirm-heading">
        <h2 id="cart-confirm-heading">{confirmation.type === 'clear' ? '¿Vaciar el carrito de ejemplo?' : `¿Eliminar ${confirmation.nombre}?`}</h2>
        <p>Esta acción solo afecta los datos ficticios. Se descartarán las cantidades sin aplicar de los productos eliminados. No se puede deshacer.</p>
        <div className="cart-actions">
          <button type="button" className="button button--secondary" onClick={() => {
            restoreFocus.current = originButton.current
            setConfirmation(null)
          }}>Cancelar eliminación</button>
          <button type="button" className="button button--primary" onClick={confirm}>Confirmar eliminación</button>
        </div>
      </section>}
      {cart ? <CartSummary key={viewVersion} cart={cart} disabled={Boolean(confirmation)}
        onQuantity={(id, quantity) => apply(current => changeCartQuantity(current, id, quantity), 'Cantidad actualizada.')}
        onRemove={(item, button) => ask({ type: 'remove', productoId: item.productoId, nombre: item.nombre }, button)} /> : <CartPending />}
      {cart && <section className="cart-card cart-demo" aria-labelledby="cart-operations-heading">
        <h2 id="cart-operations-heading">Modificar el ejemplo</h2>
        <p>Catálogo ficticio independiente de Productos. Los nombres de restaurantes son etiquetas de prueba.</p>
        <CartCatalogForm key={viewVersion} disabled={Boolean(confirmation)} onAdd={(id, quantity) => apply(
          current => addCartProduct(current, CART_CATALOG.find(product => product.productoId === id), quantity), 'Producto agregado.')} />
        <button type="button" className="button button--secondary" disabled={Boolean(confirmation) || cart.items.length === 0}
          onClick={event => ask({ type: 'clear' }, event.currentTarget)}>Vaciar ejemplo</button>
      </section>}
      <section className="cart-card cart-demo" aria-labelledby="cart-demo-heading">
        <h2 id="cart-demo-heading">Pruebas de desarrollo</h2>
        <p>Estos controles no consultan ni modifican el backend. Los ejemplos desaparecen al salir de la página, cerrar sesión o cambiar de cuenta.</p>
        <div className="cart-actions">
          <button type="button" className="button button--primary" disabled={Boolean(confirmation)} onClick={() => show(createExampleCart())}>Ver carrito de ejemplo</button>
          <button type="button" className="button button--secondary" disabled={Boolean(confirmation)} onClick={() => show(createEmptyExampleCart())}>Ver ejemplo vacío</button>
          {cart && <button type="button" className="button button--secondary" disabled={Boolean(confirmation)} onClick={() => show(null)}>Quitar ejemplo</button>}
        </div>
        <p>Elegir otro ejemplo reinicia sus productos y cantidades sin aplicar. No hay persistencia ni creación de pedidos.</p>
      </section>
    </>
  )
}
