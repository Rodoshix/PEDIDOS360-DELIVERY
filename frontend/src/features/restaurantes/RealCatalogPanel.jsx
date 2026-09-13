import { useCallback, useEffect, useState } from 'react'
import { useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createCartController } from '../carrito/cartService.js'
import { createCartHttpAdapter, createCatalogHttpAdapter, commerceFailure } from '../carrito/cartHttpAdapter.js'
import RealCatalog from '../carrito/RealCatalog.jsx'

/** Catálogo de restaurantes y productos, con agregado al carrito real. */
export default function RealCatalogPanel() {
  const [controller] = useState(createCartController)
  const [catalog, setCatalog] = useState(null)
  const [catalogError, setCatalogError] = useState(null)
  const [message, setMessage] = useState('')
  const { busy: sessionBusy, login, authorizeApi } = useAuthSession()
  const location = useLocation()
  const destination = location.pathname + location.search + location.hash
  const catalogFailed = useCallback(error => setCatalogError(error), [])

  useEffect(() => {
    let disposed = false
    controller.connect(null)
    if (!sessionBusy) import('../../services/httpClient.js').then(({ default: client }) => {
      if (disposed) return
      controller.connect(createCartHttpAdapter(client))
      setCatalog(createCatalogHttpAdapter(client))
    }).catch(error => {
      if (!disposed) catalogFailed(commerceFailure(error))
    })
    return () => { disposed = true; controller.cancelPending() }
  }, [controller, sessionBusy, catalogFailed])

  const busy = sessionBusy || ['idle', 'loading', 'saving'].includes(controller.getSnapshot().status)
  const error = catalogError || (controller.getSnapshot().status === 'error' ? controller.getSnapshot().error : null)

  async function add(productoId) {
    if (busy) return
    setMessage('')
    const ok = await controller.write({ type: 'add', productoId, cantidad: 1 })
    if (ok) setMessage('Producto agregado a tu carrito.')
  }

  return <div aria-busy={busy}>
    <p>Catálogo real consultado mediante el BFF.</p>
    {busy && <p role="status">Consultando catálogo…</p>}
    {error && <div role="alert" className="cart-error">
      <p>{error.message}</p>
      {error.code === 'INTERACTION_REQUIRED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</button>}
      {error.code === 'UNAUTHORIZED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</button>}
    </div>}
    {message && <p role="status">{message}</p>}
    {catalog && !sessionBusy && <RealCatalog adapter={catalog} disabled={busy}
      onError={catalogFailed} onAdd={add} />}
    {!catalog && sessionBusy && <p role="status">Preparando catálogo…</p>}
  </div>
}
