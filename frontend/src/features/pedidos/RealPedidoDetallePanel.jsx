import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link, useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { ClientPedidoDetail } from './ClientPedidoView.jsx'
import LoadingState from '../../components/feedback/LoadingState.jsx'
import { Alert } from '../../components/feedback/Feedback.jsx'
import './clientCommerce.css'
import { createPedidoController } from './pedidoService.js'
import { createPedidoHttpAdapter } from './pedidoHttpAdapter.js'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import './pedidos.css'

/** Detalle de un pedido propio; solo el propietario o ADMIN (el backend lo valida). */
export default function RealPedidoDetallePanel({ pedidoId }) {
  const [controller] = useState(createPedidoController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const [message, setMessage] = useState('')
  const { busy: sessionBusy, login, authorizeApi } = useAuthSession()
  const location = useLocation()
  const destination = location.pathname + location.search + location.hash
  const id = Number(pedidoId)

  useEffect(() => {
    let disposed = false
    controller.connect(null)
    if (!sessionBusy) import('../../services/httpClient.js').then(({ default: client }) => {
      if (disposed) return
      controller.connect(createPedidoHttpAdapter(client))
      void controller.detail(id)
    }).catch(() => { if (!disposed) setMessage('No se pudo preparar la consulta del pedido.') })
    return () => { disposed = true; controller.cancelPending() }
  }, [controller, sessionBusy, id])

  const busy = sessionBusy || ['idle', 'loading', 'saving'].includes(state.status)
  const error = state.error
  const errorRender = useRef(null)
  useEffect(() => { if (error) errorRender.current?.focus() }, [error])

  return <div aria-busy={busy} className="pedidos-section client-commerce">
    {state.status === 'loading' && <LoadingState label="Consultando el pedido…" compact />}
    {error && <div ref={errorRender} tabIndex={-1} role="alert" className="pedidos-error ui-alert ui-alert--danger">
      <p>{error.message}</p>
      {error.code === 'INTERACTION_REQUIRED' && <button type="button" className="ui-button ui-button--primary" disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</button>}
      {error.code === 'UNAUTHORIZED' && <button type="button" className="ui-button ui-button--primary" disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</button>}
      {error.code !== 'NOT_FOUND' && <button type="button" className="ui-button ui-button--secondary" disabled={busy}
        onClick={() => controller.detail(id)}>Reintentar</button>}
      <Link className="ui-button ui-button--secondary" to={ROUTE_PATHS.misPedidos}>Volver a mis pedidos</Link>
    </div>}
    {message && <Alert>{message}</Alert>}
    {state.pedido && <ClientPedidoDetail pedido={state.pedido} />}
    {/* El cambio de estado es solo ADMIN; aquí el cliente no transiciona. */}
    <div className="pedidos-actions">
      <Link className="ui-button ui-button--secondary" to={ROUTE_PATHS.misPedidos}>Volver a mis pedidos</Link>
      <Link className="ui-button ui-button--secondary" to={ROUTE_PATHS.pago.replace(':pedidoId', id)}>Ir al pago</Link>
    </div>
  </div>
}
