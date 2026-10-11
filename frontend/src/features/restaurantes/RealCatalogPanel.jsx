import { useEffect, useState, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createCartController } from '../carrito/cartService.js'
import { createCartHttpAdapter, createCatalogHttpAdapter, commerceFailure } from '../carrito/cartHttpAdapter.js'
import RealCatalog from '../carrito/RealCatalog.jsx'
import Button from '../../components/ui/Button.jsx'
import { Alert } from '../../components/feedback/Feedback.jsx'

/** Catálogo de restaurantes y productos, con agregado al carrito real vía BFF. */
export default function RealCatalogPanel() {
  const [controller] = useState(createCartController)
  // Suscripción real al estado: sin esto el panel no reacciona a la carga ni a los errores.
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const [catalog, setCatalog] = useState(null)
  const [catalogError, setCatalogError] = useState(null)
  const [message, setMessage] = useState('')
  const { busy: sessionBusy, login, authorizeApi } = useAuthSession()
  const location = useLocation()
  const destination = location.pathname + location.search + location.hash

  useEffect(() => {
    let disposed = false
    controller.connect(null)
    if (!sessionBusy) import('../../services/httpClient.js').then(({ default: client }) => {
      if (disposed) return
      controller.connect(createCartHttpAdapter(client))
      setCatalog(createCatalogHttpAdapter(client))
      // Cargar el carrito es requisito para agregar: write() exige un carrito cargado.
      void controller.load()
    }).catch(error => {
      if (!disposed) setCatalogError(commerceFailure(error))
    })
    return () => { disposed = true; controller.cancelPending() }
  }, [controller, sessionBusy])

  const busy = sessionBusy || ['idle', 'loading', 'saving'].includes(state.status)
  const error = catalogError || state.error
  const loadFailed = state.status === 'error' && state.operation === 'read'

  async function add(productoId) {
    if (busy || state.error) return
    setMessage('')
    const ok = await controller.write({ type: 'add', productoId, cantidad: 1 })
    if (ok) setMessage('Producto agregado a tu carrito.')
  }

  return <div className="client-catalog-panel" aria-busy={busy}>
    {busy && <p role="status">{state.status === 'saving' ? 'Agregando producto al carrito…' : 'Preparando tu carrito…'}</p>}
    {error && <div>
      {(state.error || !catalog) && <Alert>{error.message}</Alert>}
      <div className="client-catalog__actions">
      {error.code === 'INTERACTION_REQUIRED' && <Button disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</Button>}
      {error.code === 'UNAUTHORIZED' && <Button disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</Button>}
      {loadFailed && !['INTERACTION_REQUIRED', 'UNAUTHORIZED'].includes(error.code)
        && <Button variant="secondary" disabled={busy}
          onClick={() => controller.load()}>Reintentar consulta</Button>}
      </div>
    </div>}
    {message && <Alert tone="success">{message}</Alert>}
    {catalog && !sessionBusy && <RealCatalog adapter={catalog} disabled={busy || Boolean(state.error) || !state.cart}
      onError={setCatalogError} onAdd={add} />}
  </div>
}
