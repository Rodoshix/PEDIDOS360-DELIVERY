import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link, useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createPedidoController } from './pedidoService.js'
import { createPedidoHttpAdapter, pedidoFailure } from './pedidoHttpAdapter.js'
import { validatePedidoDraft } from './pedidoOperaciones.js'
import { formatClp } from './pedidoFormat.js'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import './pedidos.css'

/**
 * Códigos que representan un fallo DEFINITIVO: la creación no ocurrió y reintentar
 * con los mismos datos es seguro. Cualquier otro (CREATE_FAILED por red/timeout) es
 * un resultado INCIERTO: el servidor pudo haber creado el pedido.
 */
const ERRORES_DEFINITIVOS = Object.freeze(['INVALID_REQUEST', 'INVALID_COMMAND', 'FORBIDDEN', 'UNAUTHORIZED', 'CONFLICT'])
const esDefinitivo = codigo => ERRORES_DEFINITIVOS.includes(codigo)

/**
 * Confirmación de pedido real: lee el carrito vía BFF, crea el pedido
 * (POST /pedidos → 201) y solo entonces vacía el carrito.
 *
 * Si el vaciado falla, NO se navega ni se repite la creación: se muestra el pedido
 * creado (con su pedidoId) y se permite reintentar únicamente el vaciado.
 */
export default function RealConfirmarPedidoPanel() {
  const [controller] = useState(createPedidoController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const { busy: sessionBusy, login, authorizeApi } = useAuthSession()
  const location = useLocation()
  const destination = location.pathname + location.search + location.hash
  const [cart, setCart] = useState(null)
  const [cartError, setCartError] = useState(null)
  const [direccionEntrega, setDireccionEntrega] = useState('')
  const [errores, setErrores] = useState({})
  const [cargando, setCargando] = useState(true)
  // Resultado tras crear el pedido: permite reintentar solo el vaciado.
  const [creado, setCreado] = useState(null)
  // 'pendiente' | 'en_curso' | 'exitoso' | 'fallido'
  const [vaciado, setVaciado] = useState(null)
  // Resultado INCIERTO de la creación: bloquea nuevos intentos hasta reconciliar.
  const [incierto, setIncierto] = useState(false)
  const [creando, setCreando] = useState(false)
  // Guardas síncronas: evitan envíos concurrentes sin depender de un render pendiente.
  const enviandoRef = useRef(false)
  const vaciandoRef = useRef(false)
  const errorRender = useRef(null)

  useEffect(() => {
    let disposed = false
    if (sessionBusy) return undefined
    import('../../services/httpClient.js').then(async ({ default: client }) => {
      if (disposed) return
      // El adaptador se conecta FUERA del submit: reconectar cancela la operación en curso.
      controller.connect(createPedidoHttpAdapter(client))
      try {
        const response = await client.get('/carrito')
        if (!disposed && response.status === 200) setCart(response.data)
        else if (!disposed) setCartError(new Error('carrito'))
      } catch (error) {
        if (!disposed) setCartError(error)
      } finally {
        if (!disposed) setCargando(false)
      }
    }).catch(error => { if (!disposed) { setCargando(false); setCartError(error) } })
    return () => { disposed = true; controller.cancelPending() }
  }, [controller, sessionBusy])

  const busy = sessionBusy || cargando || creando || state.status === 'saving'
  const error = state.error || (cartError ? pedidoFailure(cartError) : null)
  useEffect(() => { if (error) errorRender.current?.focus() }, [error])

  const items = Array.isArray(cart?.items) ? cart.items : []
  const restauranteId = cart?.restauranteId ?? null
  const total = items.reduce((sum, item) => sum + (item.subtotal ?? 0), 0)

  async function vaciarCarrito() {
    // Guarda síncrona: impide DELETE solapados (botón + automático).
    if (vaciandoRef.current) return false
    vaciandoRef.current = true
    setVaciado('en_curso')
    try {
      const client = (await import('../../services/httpClient.js')).default
      await client.delete('/carrito')
      setVaciado('exitoso')
      setCart(current => ({ ...current, items: [], restauranteId: null }))
      return true
    } catch {
      setVaciado('fallido')
      return false
    } finally {
      vaciandoRef.current = false
    }
  }

  async function confirmar(event) {
    event.preventDefault()
    // Guarda síncrona: bloquea doble clic, envíos solapados y reintentos tras resultado incierto.
    if (busy || enviandoRef.current || incierto) return
    const draft = {
      restauranteId,
      direccionEntrega,
      items: items.map(item => ({ productoId: item.productoId, cantidad: item.cantidad })),
    }
    const fallos = validatePedidoDraft(draft)
    setErrores(fallos)
    if (Object.keys(fallos).length) return

    enviandoRef.current = true
    setCreando(true)
    try {
      const ok = await controller.create(draft)
      if (!ok) {
        // Distinguir fallo definitivo (reintentar es seguro) de resultado incierto
        // (el servidor pudo crear el pedido: no se vuelve a emitir POST).
        const codigo = controller.getSnapshot().error?.code
        if (!esDefinitivo(codigo)) setIncierto(true)
        return
      }
      const pedido = controller.getSnapshot().pedido
      setCreado({ pedidoId: pedido.pedidoId, total: pedido.total })
      setVaciado('pendiente')
      await vaciarCarrito()
    } finally {
      enviandoRef.current = false
      setCreando(false)
    }
  }

  return <div className="pedidos-section" aria-busy={busy}>
    {cargando && <p role="status">Consultando tu carrito…</p>}
    {error && !creado && !incierto && <div ref={errorRender} role="alert" className="pedidos-error">
      <p>{error.message}</p>
      {error.code === 'INTERACTION_REQUIRED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</button>}
      {error.code === 'UNAUTHORIZED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</button>}
    </div>}

    {incierto && <div role="alert" className="pedidos-error">
      <h2>Resultado incierto al crear el pedido</h2>
      <p>No se pudo confirmar la respuesta del servidor. El pedido <strong>puede haberse creado</strong>.
        Para evitar duplicados, no se enviará otro pedido: revisa tu historial para reconciliar.</p>
      <div className="pedidos-actions">
        <Link className="button button--primary" to={ROUTE_PATHS.misPedidos}>Ver mis pedidos</Link>
      </div>
    </div>}

    {creado && <div className="pedidos-card">
      <h2>Pedido #{creado.pedidoId} creado</h2>
      <p>Total {formatClp(creado.total)}. Tu pedido ya está registrado; no se creará otro.</p>
      {vaciado === 'en_curso' && <p role="status">Vaciando el carrito…</p>}
      {vaciado === 'exitoso' && <p role="status">Carrito vaciado.</p>}
      {vaciado === 'fallido' && <div role="alert" className="pedidos-error">
        <p>El pedido se creó, pero no se pudo vaciar el carrito. Los productos siguen ahí.</p>
        <button type="button" className="button button--secondary" disabled={busy}
          onClick={() => vaciarCarrito()}>Reintentar vaciar carrito</button>
      </div>}
      <div className="pedidos-actions">
        <Link className="button button--primary" to={ROUTE_PATHS.pago.replace(':pedidoId', creado.pedidoId)}>Ir al pago</Link>
        <Link className="button button--secondary" to={ROUTE_PATHS.misPedidos}>Ver mis pedidos</Link>
      </div>
    </div>}

    {!creado && !incierto && !cargando && items.length === 0 && !error && <div className="pedidos-card">
      <p>Tu carrito está vacío. Agrega productos desde Restaurantes para confirmar un pedido.</p>
      <Link className="button button--primary" to={ROUTE_PATHS.restaurantes}>Explorar restaurantes</Link>
    </div>}
    {!creado && !incierto && !cargando && items.length > 0 && <form className="pedidos-card" onSubmit={confirmar} noValidate>
      <h2>Datos de entrega</h2>
      <p>Restaurante #{restauranteId} · {items.length} producto(s) · Total {formatClp(total)}</p>
      <label>Dirección de entrega
        <input type="text" value={direccionEntrega} maxLength={255} disabled={busy}
          onChange={event => setDireccionEntrega(event.target.value)} />
      </label>
      {errores.direccionEntrega && <p role="alert" className="pedidos-error">{errores.direccionEntrega}</p>}
      {errores.items && <p role="alert" className="pedidos-error">{errores.items}</p>}
      <ul className="pedidos-list">
        {items.map(item => <li key={item.productoId} className="pedidos-item">
          <span>{item.nombre ?? ('Producto ' + item.productoId)} × {item.cantidad}</span>
          <span>{formatClp(item.subtotal ?? 0)}</span>
        </li>)}
      </ul>
      <div className="pedidos-actions">
        <button type="submit" className="button button--primary" disabled={busy}>
          {creando ? 'Creando pedido…' : 'Confirmar pedido'}
        </button>
        <Link className="button button--secondary" to={ROUTE_PATHS.cart}>Volver al carrito</Link>
      </div>
      <p className="pedidos-notice">Al confirmar se crea el pedido y luego se vacía el carrito. Ante un error no se crea un segundo pedido.</p>
    </form>}
  </div>
}
