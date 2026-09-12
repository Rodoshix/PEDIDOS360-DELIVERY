import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import PedidoDetalle from '../PedidoDetalle.jsx'
import { createPedidoController } from '../pedidoService.js'
import { createPedidoDemoAdapter, PEDIDO_SCENARIOS } from '../pedidoDemoAdapter.js'
import { ETIQUETAS_ESTADO, ESTADOS_TERMINALES } from '../pedidoOperaciones.js'
import { formatClp, formatFecha } from '../pedidoFormat.js'
import { EtiquetaEstado } from '../PedidoResumen.jsx'
import '../pedidos.css'

// Estados que gestiona el restaurante (los logísticos son de I4).
const ACCIONES_RESTAURANTE = Object.freeze(['CONFIRMADO', 'PREPARANDO', 'LISTO'])

export default function RestaurantePedidosDemoPanel() {
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

  async function transicionar(pedidoId, estado) {
    if (busy) return
    const aplicado = await controller.transition(pedidoId, estado)
    if (aplicado) {
      setMessage('Estado actualizado en el ejemplo en memoria; no se guardó en Pedidos.')
      if (seleccionado === pedidoId) await controller.detail(pedidoId)
    }
  }

  return (
    <div className="pedidos-section" aria-busy={busy}>
      <p className="pedidos-notice" role="status">
        {message || 'Datos ficticios: la gestión de estados es un ejemplo local.'}
      </p>
      {state.status === 'loading' && <p role="status">Consultando pedidos de prueba…</p>}
      {state.status === 'saving' && <p role="status">Aplicando cambio de estado…</p>}
      {state.error && (
        <div ref={errorRender} className="pedidos-error" role="alert">
          <p>{state.error.message}</p>
          {loadFailed && <button type="button" className="button button--secondary" onClick={() => controller.load()}>Reintentar consulta</button>}
        </div>
      )}
      {state.pedidos && !loadFailed && (
        state.pedidos.length === 0
          ? <p className="pedidos-card">No hay pedidos de ejemplo por gestionar.</p>
          : (
            <ul className="pedidos-list">
              {state.pedidos.map(pedido => (
                <li key={pedido.pedidoId} className="pedidos-card">
                  <p>
                    <strong>Pedido #{pedido.pedidoId}</strong> · <EtiquetaEstado estado={pedido.estado} />
                    {' · '}{formatClp(pedido.total)} · {formatFecha(pedido.fechaCreacion)}
                  </p>
                  <p>{pedido.direccionEntrega}</p>
                  <div className="pedidos-actions">
                    {!ESTADOS_TERMINALES.includes(pedido.estado) && ACCIONES_RESTAURANTE
                      .filter(estado => estado !== pedido.estado)
                      .map(estado => (
                        <button key={estado} type="button" className="button button--secondary" disabled={busy}
                          onClick={() => transicionar(pedido.pedidoId, estado)}>
                          {`Pasar a ${ETIQUETAS_ESTADO[estado]}`}
                        </button>
                      ))}
                    <button type="button" className="button button--secondary" disabled={busy}
                      onClick={async () => { await controller.detail(pedido.pedidoId); setSeleccionado(pedido.pedidoId) }}>
                      Ver detalle
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )
      )}
      {seleccionado && state.pedido && (
        <PedidoDetalle pedido={state.pedido} disabled={busy} saving={state.status === 'saving'}
          onTransition={estado => transicionar(seleccionado, estado)} />
      )}
      <section className="pedidos-card">
        <h2>Pruebas de desarrollo</h2>
        <p>Estos controles no consultan ni modifican el backend.</p>
        <label className="pedidos-scenario">Escenario de Pedidos
          <select value={scenario} disabled={busy} onChange={event => setScenario(event.target.value)}>
            {PEDIDO_SCENARIOS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
        <div className="pedidos-actions">
          <button type="button" className="button button--primary" disabled={busy} onClick={() => cargar(scenario)}>Cargar escenario</button>
          <button type="button" className="button button--secondary" disabled={busy} onClick={() => cargar('example')}>Ver pedidos de ejemplo</button>
          {state.status !== 'idle' && <button type="button" className="button button--secondary" disabled={busy} onClick={() => cargar(null)}>Quitar ejemplo</button>}
        </div>
      </section>
    </div>
  )
}
