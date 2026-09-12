import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { createPagoController } from '../pagoService.js'
import { createPagoDemoAdapter, PAGO_SCENARIOS } from '../pagoDemoAdapter.js'
import { ETIQUETAS_ESTADO_PAGO, ETIQUETAS_METODO, METODOS_PAGO, validatePagoDraft } from '../pagoOperaciones.js'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import '../pagos.css'

const clp = new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP', maximumFractionDigits: 0 })

export default function PagoDemoPanel({ pedidoId, pedido }) {
  const [controller] = useState(createPagoController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const [metodo, setMetodo] = useState('TARJETA')
  const [scenario, setScenario] = useState('tarjeta')
  const [errores, setErrores] = useState({})
  const errorRender = useRef(null)
  const busy = state.status === 'loading' || state.status === 'saving'

  useEffect(() => () => controller.cancelPending(), [controller])
  useEffect(() => { if (state.error) errorRender.current?.focus() }, [state.error])

  async function registrar(event) {
    event.preventDefault()
    if (busy) return
    const draft = { pedidoId, metodo }
    const fallos = validatePagoDraft(draft)
    setErrores(fallos)
    if (Object.keys(fallos).length) return
    controller.connect(createPagoDemoAdapter({ scenario, pedido }))
    await controller.registrar(draft)
  }

  const pago = state.pago
  return (
    <div className="pagos-section" aria-busy={busy}>
      <p className="pagos-notice" role="status">Datos ficticios: el pago es un ejemplo local; no se cobra nada real.</p>
      {state.status === 'saving' && <p role="status">Registrando el pago…</p>}
      {state.error && (
        <div ref={errorRender} className="pagos-error" role="alert">
          <p>{state.error.message}</p>
          {state.operation === 'write' && <button type="button" className="button button--secondary" disabled={busy} onClick={() => controller.registrar({ pedidoId, metodo })}>Reintentar pago</button>}
        </div>
      )}
      {pago ? (
        <section className="pagos-card" aria-labelledby="pago-resultado">
          <h2 id="pago-resultado">Pago #{pago.pagoId}</h2>
          <p>Pedido #{pago.pedidoId} · {clp.format(pago.monto)}</p>
          <p>Método: {ETIQUETAS_METODO[pago.metodo]} · Estado: <span className="pagos-estado">{ETIQUETAS_ESTADO_PAGO[pago.estado]}</span></p>
          {pago.estado === 'PENDIENTE' && <p>El cobro en efectivo se registra al entregar el pedido.</p>}
          {pago.estado === 'RECHAZADO' && <p>El pago fue rechazado. Puedes intentar con otro método.</p>}
          <div className="pagos-actions">
            <Link className="button button--secondary" to={ROUTE_PATHS.pedidoDetalle.replace(':id', pago.pedidoId)}>Ver pedido</Link>
            <Link className="button button--secondary" to={ROUTE_PATHS.misPedidos}>Ir a mis pedidos</Link>
          </div>
        </section>
      ) : (
        <form className="pagos-card" onSubmit={registrar} noValidate>
          <h2>Registrar pago</h2>
          <p>Pedido #{pedidoId} · Total {clp.format(pedido?.total ?? 12500)}</p>
          <fieldset disabled={busy}>
            <legend>Método de pago</legend>
            {METODOS_PAGO.map(opcion => (
              <label key={opcion}>
                <input type="radio" name="metodo" value={opcion} checked={metodo === opcion}
                  onChange={() => setMetodo(opcion)} />
                {ETIQUETAS_METODO[opcion]}
              </label>
            ))}
          </fieldset>
          {errores.metodo && <p className="pagos-error" role="alert">{errores.metodo}</p>}
          <div className="pagos-actions">
            <button type="submit" className="button button--primary" disabled={busy}>
              {busy ? 'Registrando…' : 'Pagar'}
            </button>
            <Link className="button button--secondary" to={ROUTE_PATHS.misPedidos}>Cancelar</Link>
          </div>
        </form>
      )}
      <section className="pagos-card">
        <h2>Pruebas de desarrollo</h2>
        <label className="pagos-scenario">Escenario de pago
          <select value={scenario} disabled={busy} onChange={event => setScenario(event.target.value)}>
            {PAGO_SCENARIOS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
        <p>Tarjeta se aprueba al pagar; efectivo queda pendiente hasta la entrega.</p>
      </section>
    </div>
  )
}
