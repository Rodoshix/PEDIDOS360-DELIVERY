import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import CartPending from './CartPending.jsx'
import CartSummary from './CartSummary.jsx'
import CartCatalogForm from './CartCatalogForm.jsx'
import { createCartController } from './cartService.js'
import { createCartDemoAdapter, CART_SCENARIOS } from './cartDemoAdapter.js'

export default function CartDemoPanel() {
  const [controller] = useState(createCartController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const cart = state.cart
  const error = state.error?.message
  const busy = state.status === 'loading' || state.status === 'saving'
  const loadFailed = state.status === 'error' && state.operation === 'read'
  const [scenario, setScenario] = useState('example')
  const [viewVersion, setViewVersion] = useState(0)
  const [message, setMessage] = useState('')
  const [confirmation, setConfirmation] = useState(null)
  const notice = useRef(null)
  const errorNotice = useRef(null)
  const confirmNotice = useRef(null)
  const originButton = useRef(null)
  const restoreFocus = useRef(null)
  const interacted = useRef(false)
  useEffect(() => () => controller.cancelPending(), [controller])
  useEffect(() => { if (interacted.current && !error) notice.current?.focus() }, [cart, message, error])
  useEffect(() => { if (error) errorNotice.current?.focus() }, [error, state.error])
  useEffect(() => {
    if (confirmation) confirmNotice.current?.focus()
    else if (restoreFocus.current) {
      restoreFocus.current.focus()
      restoreFocus.current = null
    }
  }, [confirmation])

  async function show(nextScenario) {
    if (confirmation || busy) return
    interacted.current = true
    setMessage('')
    setViewVersion(value => value + 1)
    controller.connect(nextScenario ? createCartDemoAdapter({ scenario: nextScenario }) : null)
    if (nextScenario) {
      setScenario(nextScenario)
      await controller.load()
    }
  }

  async function apply(command, success) {
    if (busy) return false
    setMessage('')
    const applied = await controller.write(command)
    if (applied) {
      interacted.current = true
      setMessage(`${success} Solo cambió el ejemplo en memoria; no se guardó en Carrito.`)
    }
    return applied
  }

  function ask(action, button) {
    if (confirmation || busy) return
    originButton.current = button
    setConfirmation(action)
  }

  async function confirm() {
    const action = confirmation
    if (!action || busy) return
    const applied = await apply(action.type === 'clear' ? { type: 'clear' } : { type: 'remove', productoId: action.productoId },
      action.type === 'clear' ? 'Ejemplo vaciado.' : 'Producto eliminado del ejemplo.')
    if (applied) setConfirmation(null)
  }

  return (
    <div aria-busy={busy}>
      <p ref={notice} tabIndex={-1} role="status" className="cart-notice">
        {message || (cart ? 'Datos ficticios: este carrito es un ejemplo local, no tu carrito real.'
          : 'Sin consulta real. Los ejemplos se muestran solo si los eliges.')}
      </p>
      {state.status === 'loading' && <p role="status">Consultando carrito de prueba…</p>}
      {state.status === 'saving' && <p role="status">Aplicando operación al ejemplo… Espera antes de volver a intentarlo.</p>}
      {error && <div ref={errorNotice} tabIndex={-1} role="alert" className="cart-error">
        <p>{error}</p>
        {loadFailed && <button type="button" className="button button--secondary" onClick={() => controller.load()}>Reintentar consulta</button>}
      </div>}
      {confirmation && <section ref={confirmNotice} tabIndex={-1} className="cart-card cart-confirm" role="group" aria-labelledby="cart-confirm-heading">
        <h2 id="cart-confirm-heading">{confirmation.type === 'clear' ? '¿Vaciar el carrito de ejemplo?' : `¿Eliminar ${confirmation.nombre}?`}</h2>
        <p>Esta acción solo afecta los datos ficticios. Se descartarán las cantidades sin aplicar de los productos eliminados. No se puede deshacer.</p>
        <div className="cart-actions">
          <button type="button" className="button button--secondary" disabled={busy} onClick={() => {
            controller.clearWriteError()
            restoreFocus.current = originButton.current
            setConfirmation(null)
          }}>Cancelar eliminación</button>
          <button type="button" className="button button--primary" disabled={busy} onClick={confirm}>{busy ? 'Aplicando eliminación…' : 'Confirmar eliminación'}</button>
        </div>
      </section>}
      {cart && !loadFailed ? <CartSummary key={viewVersion} cart={cart} disabled={Boolean(confirmation) || busy} saving={state.status === 'saving'}
        onQuantity={(id, quantity) => apply({ type: 'quantity', productoId: id, cantidad: quantity }, 'Cantidad actualizada.')}
        onRemove={(item, button) => ask({ type: 'remove', productoId: item.productoId, nombre: item.nombre }, button)} />
        : state.status === 'idle' ? <CartPending /> : null}
      {cart && !loadFailed && <section className="cart-card cart-demo" aria-labelledby="cart-operations-heading">
        <h2 id="cart-operations-heading">Modificar el ejemplo</h2>
        <p>Catálogo ficticio independiente de Productos. Los nombres de restaurantes son etiquetas de prueba.</p>
        <CartCatalogForm key={viewVersion} disabled={Boolean(confirmation) || busy} saving={state.status === 'saving'} onAdd={(id, quantity) => apply(
          { type: 'add', productoId: id, cantidad: quantity }, 'Producto agregado.')} />
        <button type="button" className="button button--secondary" disabled={Boolean(confirmation) || busy || cart.items.length === 0}
          onClick={event => ask({ type: 'clear' }, event.currentTarget)}>Vaciar ejemplo</button>
      </section>}
      <section className="cart-card cart-demo" aria-labelledby="cart-demo-heading">
        <h2 id="cart-demo-heading">Pruebas de desarrollo</h2>
        <p>Estos controles no consultan ni modifican el backend. Los ejemplos desaparecen al salir de la página, cerrar sesión o cambiar de cuenta.</p>
        <div className="cart-actions">
          <button type="button" className="button button--primary" disabled={Boolean(confirmation) || busy} onClick={() => show('example')}>Ver carrito de ejemplo</button>
          <button type="button" className="button button--secondary" disabled={Boolean(confirmation) || busy} onClick={() => show('empty')}>Ver ejemplo vacío</button>
          {state.status !== 'idle' && <button type="button" className="button button--secondary" disabled={Boolean(confirmation) || busy} onClick={() => show(null)}>Quitar ejemplo</button>}
        </div>
        <label className="cart-scenario">Escenario de Carrito
          <select value={scenario} disabled={Boolean(confirmation) || busy} onChange={event => setScenario(event.target.value)}>
            {CART_SCENARIOS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
        <button type="button" className="button button--secondary" disabled={Boolean(confirmation) || busy} onClick={() => show(scenario)}>Cargar escenario de Carrito</button>
        <p>Elegir otro ejemplo reinicia sus productos y cantidades sin aplicar. No hay persistencia ni creación de pedidos.</p>
        <p>Consultas y operaciones tienen una demora simulada. Los fallos de una vez se reinician al cargar el escenario; reintentar conserva la instancia actual.</p>
      </section>
    </div>
  )
}
