import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import PedidoDetalle from './PedidoDetalle.jsx'
import { createPedidoController } from './pedidoService.js'
import { createPedidoDemoAdapter } from './pedidoDemoAdapter.js'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import './pedidos.css'

export default function PedidoDetalleDemoPanel({ pedidoId }) {
  const [controller] = useState(createPedidoController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const [message, setMessage] = useState('')
  const errorRender = useRef(null)
  const busy = state.status === 'loading' || state.status === 'saving'

  useEffect(() => {
    controller.connect(createPedidoDemoAdapter({ scenario: 'example' }))
    controller.detail(Number(pedidoId))
    return () => controller.cancelPending()
  }, [controller, pedidoId])

  useEffect(() => { if (state.error) errorRender.current?.focus() }, [state.error])

  async function transicionar(estado) {
    if (busy) return
    const aplicado = await controller.transition(Number(pedidoId), estado, { showDetail: true })
    if (aplicado) setMessage('Estado actualizado en el ejemplo en memoria; no se guardó en Pedidos.')
  }

  if (state.status === 'loading') return <p role="status">Consultando el pedido…</p>
  if (state.error) {
    return (
      <div ref={errorRender} className="pedidos-error" role="alert">
        <p>{state.error.message}</p>
        <div className="pedidos-actions">
          <button type="button" className="button button--secondary" onClick={() => controller.detail(Number(pedidoId))}>Reintentar</button>
          <Link className="button button--secondary" to={ROUTE_PATHS.pedidos}>Volver a mis pedidos</Link>
        </div>
      </div>
    )
  }
  if (!state.pedido) return null
  return (
    <div className="pedidos-section">
      <p className="pedidos-notice" role="status">
        {message || 'Datos ficticios: este pedido es un ejemplo local.'}
      </p>
      <PedidoDetalle pedido={state.pedido} disabled={busy} saving={state.status === 'saving'} onTransition={transicionar} />
      <p><Link to={ROUTE_PATHS.pedidos}>← Volver a mis pedidos</Link></p>
    </div>
  )
}
