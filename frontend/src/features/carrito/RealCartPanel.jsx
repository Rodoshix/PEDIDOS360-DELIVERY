import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link, useLocation } from 'react-router'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createCartController } from './cartService.js'
import { createCartHttpAdapter, commerceFailure } from './cartHttpAdapter.js'
import CartSummary from './CartSummary.jsx'

export default function RealCartPanel() {
  const [controller] = useState(createCartController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const [confirmation, setConfirmation] = useState(null)
  const [message, setMessage] = useState('')
  const { busy: sessionBusy, login, authorizeApi } = useAuthSession()
  const location = useLocation()
  const destination = location.pathname + location.search + location.hash
  const notice = useRef(null)
  const confirmNotice = useRef(null)
  const originButton = useRef(null)
  useEffect(() => {
    let disposed = false
    controller.connect(null)
    if (!sessionBusy) import('../../services/httpClient.js').then(({ default: client }) => {
      if (disposed) return
      controller.connect(createCartHttpAdapter(client))

      void controller.load()
    }).catch(error => {
      if (disposed) return
      controller.connect({ read: async () => { throw commerceFailure(error) } })
      void controller.load()
    })
    return () => { disposed = true; controller.cancelPending() }
  }, [controller, sessionBusy])
  const busy = sessionBusy || ['idle', 'loading', 'saving'].includes(state.status)
  const failedRead = state.status === 'error' && state.operation === 'read'
  const error = state.error
  const disabled = busy || Boolean(state.error) || Boolean(confirmation) || !state.cart
  useEffect(() => { if (error || message) notice.current?.focus() }, [error, message])
  useEffect(() => { if (confirmation) confirmNotice.current?.focus() }, [confirmation])
  async function apply(command) {
    if (busy || state.error) return false
    setMessage('')
    const ok = await controller.write(command)
    if (ok) {
      setConfirmation(null)
      setMessage('Carrito actualizado en el servidor.')
    }
    return ok
  }
  function ask(command, button) { originButton.current = button; setConfirmation(command) }
  return <div aria-busy={busy}>
    <p>Carrito y catálogo reales mediante el BFF. No se utilizan ejemplos como respaldo.</p>
    {busy && <p role="status">{state.status === 'saving' ? 'Guardando carrito…' : 'Consultando tu carrito…'}</p>}
    {error && <div ref={notice} tabIndex={-1} role="alert" className="cart-error">
      <p>{error.message}</p>
      {state.operation === 'write' && <p>El estado mostrado puede estar desactualizado. Actualiza el carrito antes de otra operación; las cantidades sin aplicar se descartarán al consultar.</p>}
      {error.code === 'INTERACTION_REQUIRED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</button>}
      {error.code === 'UNAUTHORIZED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</button>}
    </div>}
    {message && <p ref={notice} tabIndex={-1} role="status">{message}</p>}
    {confirmation && <section ref={confirmNotice} tabIndex={-1} className="cart-card" aria-labelledby="cart-confirm-heading">
      <h2 id="cart-confirm-heading">¿Confirmar eliminación?</h2>
      <p>{confirmation.type === 'clear' ? 'Se quitarán todos los productos de tu carrito.' : 'Se quitará este producto de tu carrito.'}</p>
      <button type="button" className="button button--secondary" disabled={busy} onClick={() => {
        setConfirmation(null); originButton.current?.focus()
      }}>Cancelar eliminación</button>
      <button type="button" className="button button--primary" disabled={busy || Boolean(state.error)}
        onClick={() => apply(confirmation)}>Confirmar eliminación</button>
    </section>}
    {state.cart && !failedRead && !sessionBusy && !['idle', 'loading'].includes(state.status) && <CartSummary mode="real" cart={state.cart} disabled={disabled} saving={state.status === 'saving'}
      onQuantity={(id, cantidad) => apply({ type: 'quantity', productoId: id, cantidad })}
      onRemove={(item, button) => ask({ type: 'remove', productoId: item.productoId }, button)} />}
    <div className="cart-actions">
      {!disabled && state.cart?.items.length > 0 && <Link className="button button--primary"
        to={ROUTE_PATHS.confirmarPedido}>Continuar a confirmar pedido</Link>}
      <button type="button" className="button button--secondary" disabled={busy} onClick={() => {
        setConfirmation(null); setMessage(''); void controller.load()
      }}>Actualizar carrito</button>
      {state.cart?.items.length > 0 && !failedRead && <button type="button" className="button button--secondary" disabled={disabled}
        onClick={event => ask({ type: 'clear' }, event.currentTarget)}>Vaciar carrito</button>}
    </div>
    <p>Aplica los cambios de cantidad antes de continuar. Las cantidades sin aplicar se pierden al actualizar, salir o cambiar de cuenta. En el siguiente paso podrás revisar la dirección y confirmar el pedido.</p>
  </div>
}
