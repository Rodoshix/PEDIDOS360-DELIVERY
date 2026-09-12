import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import PedidoDetalle from '../PedidoDetalle.jsx'
import { ResumenPedido } from '../PedidoResumen.jsx'
import { createPedidoController } from '../pedidoService.js'
import { createPedidoDemoAdapter, PEDIDO_SCENARIOS } from '../pedidoDemoAdapter.js'
import '../pedidos.css'

export default function MisPedidosDemoPanel() {
  const [controller] = useState(createPedidoController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const [scenario, setScenario] = useState('example')
  const [seleccionado, setSeleccionado] = useState(null)
  const [message, setMessage] = useState('')
  const errorRender = useRef(null)
  const busy = state.status === 'loading' || state.status === 'saving'
  const loadFailed = state.status === 'error' && state.operation === 'load'

  useEffect(() => () => controller.cancelPending(), [controller])
  useEffect(() => { if (state.error) errorRender.current?.focus() }, [state.error])

  async function cargar(nextScenario) {
    if (busy) return
    setMessage('')
    setSeleccionado(null)
    controller.connect(nextScenario ? createPedidoDemoAdapter({ scenario: nextScenario }) : null)
    if (nextScenario) {
      setScenario(nextScenario)
      await controller.load()
    }
  }

  async function verDetalle(pedidoId) {
    if (busy) return
    setMessage('')
    const cargado = await controller.detail(pedidoId)
    if (cargado) setSeleccionado(pedidoId)
  }

  async function transicionar(estado) {
    if (busy || !seleccionado) return
    const aplicado = await controller.transition(seleccionado, estado, { showDetail: true })
    if (aplicado) setMessage('Estado actualizado en el ejemplo en memoria; no se guardó en Pedidos.')
  }

  return (
    <div aria-busy={busy} className="pedidos-section">
      <p className="pedidos-notice" role="status">
        {message || 'Datos ficticios: estos pedidos son un ejemplo local, no tus pedidos reales.'}
      </p>
      {state.status === 'loading' && <p role="status">Consultando pedidos de prueba…</p>}
      {state.status === 'saving' && <p role="status">Aplicando operación al ejemplo…</p>}
      {state.error && (
        <div ref={errorRender} className="pedidos-error" role="alert">
          <p>{state.error.message}</p>
          {loadFailed && <button type="button" className="button button--secondary" onClick={() => controller.load()}>Reintentar consulta</button>}
        </div>
      )}
      {state.pedidos && !loadFailed && (
        state.pedidos.length === 0
          ? <p className="pedidos-card">No tienes pedidos de ejemplo.</p>
          : (
            <ul className="pedidos-list">
              {state.pedidos.map(pedido => (
                <ResumenPedido key={pedido.pedidoId} pedido={pedido} onVerDetalle={verDetalle} />
              ))}
            </ul>
          )
      )}
      {seleccionado && state.pedido && (
        <PedidoDetalle pedido={state.pedido} disabled={busy} saving={state.status === 'saving'} onTransition={transicionar} />
      )}
      <section className="pedidos-card">
        <h2>Pruebas de desarrollo</h2>
        <p>Estos controles no consultan ni modifican el backend.</p>
        <div className="pedidos-actions">
          <button type="button" className="button button--primary" disabled={busy} onClick={() => cargar('example')}>Ver pedidos de ejemplo</button>
          <button type="button" className="button button--secondary" disabled={busy} onClick={() => cargar('empty')}>Ver ejemplo sin pedidos</button>
          {state.status !== 'idle' && <button type="button" className="button button--secondary" disabled={busy} onClick={() => cargar(null)}>Quitar ejemplo</button>}
        </div>
        <label className="pedidos-scenario">Escenario de Pedidos
          <select value={scenario} disabled={busy} onChange={event => setScenario(event.target.value)}>
            {PEDIDO_SCENARIOS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
        <button type="button" className="button button--secondary" disabled={busy} onClick={() => cargar(scenario)}>Cargar escenario</button>
      </section>
    </div>
  )
}
