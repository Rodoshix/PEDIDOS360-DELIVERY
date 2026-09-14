import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link, useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createPagoController } from './pagoService.js'
import { createPagoHttpAdapter } from './pagoHttpAdapter.js'
import { ETIQUETAS_ESTADO_PAGO, ETIQUETAS_METODO, METODOS_PAGO, validatePagoDraft } from './pagoOperaciones.js'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import './pagos.css'

const clp = new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP', maximumFractionDigits: 0 })

/** Registro de pago real: TARJETA (aprobado) o EFECTIVO (pendiente de cobro). */
export default function RealPagoPanel({ pedidoId }) {
  const [controller] = useState(createPagoController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const [metodo, setMetodo] = useState('TARJETA')
  const [errores, setErrores] = useState({})
  // Intento en curso: la clave de idempotencia va atada al pedidoId y método.
  const [intento, setIntento] = useState(null)
  const errorRender = useRef(null)
  const { busy: sessionBusy, login, authorizeApi } = useAuthSession()
  const location = useLocation()
  const destination = location.pathname + location.search + location.hash
  const id = Number(pedidoId)
  const busy = sessionBusy || state.status === 'loading' || state.status === 'saving'

  const nuevaClave = () => `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`

  useEffect(() => {
    let disposed = false
    controller.connect(null)
    if (!sessionBusy) import('../../services/httpClient.js').then(({ default: client }) => {
      if (disposed) return
      controller.connect(createPagoHttpAdapter(client))
      // Si el pedido ya tiene pago, se muestra.
      void controller.load(id)
    }).catch(() => {})
    return () => { disposed = true; controller.cancelPending() }
  }, [controller, sessionBusy, id])

  useEffect(() => { if (state.error) errorRender.current?.focus() }, [state.error])

  async function enviar() {
    const idempotencyKey = intento ? intento.key : nuevaClave()
    setIntento({ key: idempotencyKey, pedidoId: id, metodo })
    const aplicado = await controller.registrar({ pedidoId: id, metodo }, { idempotencyKey })
    if (aplicado) setIntento(null)
    return aplicado
  }

  async function registrar(event) {
    event.preventDefault()
    if (busy) return
    const draft = { pedidoId: id, metodo }
    const fallos = validatePagoDraft(draft)
    setErrores(fallos)
    if (Object.keys(fallos).length) return
    if (intento && (intento.pedidoId !== draft.pedidoId || intento.metodo !== draft.metodo)) {
      setErrores({ metodo: 'Hay un intento pendiente con otro método. Reintenta ese intento o espera un resultado definitivo.' })
      return
    }
    await enviar()
  }

  const pago = state.pago
  const error = state.error
  return <div className="pagos-section" aria-busy={busy}>
    {state.status === 'loading' && <p role="status">Consultando el pago del pedido…</p>}
    {state.status === 'saving' && <p role="status">Registrando el pago…</p>}
    {state.status === 'empty' && <p role="status">Este pedido todavía no tiene pagos registrados. Elige un método para registrar el primero.</p>}
    {error && <div ref={errorRender} role="alert" className="pagos-error">
      <p>{error.message}</p>
      {error.code === 'INTERACTION_REQUIRED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</button>}
      {error.code === 'UNAUTHORIZED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</button>}
      {state.operation === 'write' && intento
        && <button type="button" className="button button--secondary" disabled={busy} onClick={() => {
          return controller.registrar({ pedidoId: intento.pedidoId, metodo: intento.metodo }, { idempotencyKey: intento.key })
            .then(aplicado => { if (aplicado) setIntento(null) })
        }}>Reintentar pago</button>}
    </div>}
    {pago ? <section className="pagos-card" aria-labelledby="pago-resultado">
      <h2 id="pago-resultado">Pago #{pago.pagoId}</h2>
      <p>Pedido #{pago.pedidoId} · {clp.format(pago.monto)}</p>
      <p>Método: {ETIQUETAS_METODO[pago.metodo]} · Estado: <span className="pagos-estado">{ETIQUETAS_ESTADO_PAGO[pago.estado]}</span></p>
      {pago.estado === 'PENDIENTE' && <p>El cobro en efectivo se registra al entregar el pedido.</p>}
      {pago.estado === 'RECHAZADO' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => { setIntento(null); controller.nuevoIntento(); setMetodo('TARJETA') }}>
        Elegir otro método
      </button>}
      <div className="pagos-actions">
        <Link className="button button--secondary" to={ROUTE_PATHS.pedidoDetalle.replace(':id', pago.pedidoId)}>Ver pedido</Link>
        <Link className="button button--secondary" to={ROUTE_PATHS.misPedidos}>Ir a mis pedidos</Link>
      </div>
    </section> : !error && (
      <form className="pagos-card" onSubmit={registrar} noValidate>
        <h2>Registrar pago</h2>
        <p>Pedido #{id}</p>
        <fieldset disabled={busy}>
          <legend>Método de pago</legend>
          {METODOS_PAGO.map(opcion => <label key={opcion}>
            <input type="radio" name="metodo" value={opcion} checked={metodo === opcion}
              onChange={() => setMetodo(opcion)} />
            {ETIQUETAS_METODO[opcion]}
          </label>)}
        </fieldset>
        {errores.metodo && <p role="alert" className="pagos-error">{errores.metodo}</p>}
        <div className="pagos-actions">
          <button type="submit" className="button button--primary" disabled={busy}>{busy ? 'Registrando…' : 'Pagar'}</button>
          <Link className="button button--secondary" to={ROUTE_PATHS.misPedidos}>Cancelar</Link>
        </div>
      </form>)}
  </div>
}
