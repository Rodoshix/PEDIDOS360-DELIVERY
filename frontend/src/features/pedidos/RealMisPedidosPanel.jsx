import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createPedidoController } from './pedidoService.js'
import { createPedidoHttpAdapter, pedidoFailure } from './pedidoHttpAdapter.js'
import { ResumenPedido } from './PedidoResumen.jsx'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import './pedidos.css'

/** Historial de pedidos propio (GET /pedidos/me) mediante el BFF. */
export default function RealMisPedidosPanel() {
  const [controller] = useState(createPedidoController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const { busy: sessionBusy, login, authorizeApi } = useAuthSession()
  const location = useLocation()
  const navigate = useNavigate()
  const destination = location.pathname + location.search + location.hash

  useEffect(() => {
    let disposed = false
    controller.connect(null)
    if (!sessionBusy) import('../../services/httpClient.js').then(({ default: client }) => {
      if (disposed) return
      controller.connect(createPedidoHttpAdapter(client))
      void controller.load()
    }).catch(error => {
      if (disposed) return
      const safe = pedidoFailure(error)
      controller.connect({ list: async () => { throw safe } })
      void controller.load()
    })
    return () => { disposed = true; controller.cancelPending() }
  }, [controller, sessionBusy])

  const busy = sessionBusy || ['idle', 'loading'].includes(state.status)
  const error = state.error
  const loadFailed = state.status === 'error' && state.operation === 'load'

  const pedidos = state.pedidos
  return <div aria-busy={busy} className="pedidos-section">
    {state.status === 'loading' && <p role="status">Consultando tus pedidos…</p>}
    {error && <div role="alert" className="pedidos-error">
      <p>{error.message}</p>
      {error.code === 'INTERACTION_REQUIRED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</button>}
      {error.code === 'UNAUTHORIZED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</button>}
      {loadFailed && !['INTERACTION_REQUIRED', 'UNAUTHORIZED'].includes(error.code)
        && <button type="button" className="button button--secondary" disabled={busy} onClick={() => controller.load()}>Reintentar consulta</button>}
    </div>}
    {pedidos && !loadFailed && (pedidos.length === 0
      ? <p className="pedidos-card">Todavía no tienes pedidos. Explora los restaurantes para crear uno.</p>
      : <ul className="pedidos-list">
        {pedidos.map(pedido => <ResumenPedido key={pedido.pedidoId} pedido={pedido}
          onVerDetalle={id => navigate(ROUTE_PATHS.pedidoDetalle.replace(':id', id))} />)}
      </ul>)}
    {!pedidos && !error && !busy && <p><Link to={ROUTE_PATHS.restaurantes}>Explorar restaurantes</Link></p>}
  </div>
}
