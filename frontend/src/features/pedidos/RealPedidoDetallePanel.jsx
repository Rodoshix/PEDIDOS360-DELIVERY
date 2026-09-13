import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link, useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import PedidoDetalle from './PedidoDetalle.jsx'
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

  return <div aria-busy={busy} className="pedidos-section">
    {state.status === 'loading' && <p role="status">Consultando el pedido…</p>}
    {error && <div role="alert" className="pedidos-error">
      <p>{error.message}</p>
      {error.code === 'INTERACTION_REQUIRED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</button>}
      {error.code === 'UNAUTHORIZED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</button>}
      {error.code !== 'NOT_FOUND' && <button type="button" className="button button--secondary" disabled={busy}
        onClick={() => controller.detail(id)}>Reintentar</button>}
      <Link className="button button--secondary" to={ROUTE_PATHS.misPedidos}>Volver a mis pedidos</Link>
    </div>}
    {message && <p role="status" className="pedidos-notice">{message}</p>}
    {state.pedido && <PedidoDetalle pedido={state.pedido} disabled saving={false} onTransition={() => {}} />}
    {/* El cambio de estado es solo ADMIN; aquí el cliente no transiciona. */}
    <p><Link to={ROUTE_PATHS.misPedidos}>← Volver a mis pedidos</Link>
      {' · '}<Link to={ROUTE_PATHS.pago.replace(':pedidoId', id)}>Ir al pago</Link></p>
  </div>
}
