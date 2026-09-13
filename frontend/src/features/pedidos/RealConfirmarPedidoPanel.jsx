import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createPedidoController } from './pedidoService.js'
import { createPedidoHttpAdapter, pedidoFailure } from './pedidoHttpAdapter.js'
import { validatePedidoDraft } from './pedidoOperaciones.js'
import { formatClp } from './pedidoFormat.js'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import './pedidos.css'

/**
 * Confirmación de pedido real: lee el carrito vía BFF, crea el pedido
 * (POST /pedidos → 201) y solo entonces vacía el carrito. Si el vaciado falla,
 * el pedido creado se conserva y no se repite la creación (contrato #47).
 */
export default function RealConfirmarPedidoPanel() {
  const [controller] = useState(createPedidoController)
  const { busy: sessionBusy, login, authorizeApi } = useAuthSession()
  const location = useLocation()
  const navigate = useNavigate()
  const destination = location.pathname + location.search + location.hash
  const [cart, setCart] = useState(null)
  const [cartError, setCartError] = useState(null)
  const [direccionEntrega, setDireccionEntrega] = useState('')
  const [errores, setErrores] = useState({})
  const [aviso, setAviso] = useState('')
  const [cargando, setCargando] = useState(true)
  const errorRender = useRef(null)

  useEffect(() => {
    let disposed = false
    if (sessionBusy) return undefined
    import('../../services/httpClient.js').then(async ({ default: client }) => {
      if (disposed) return
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

  const state = controller.getSnapshot()
  const busy = sessionBusy || cargando || state.status === 'saving'
  const error = state.error || (cartError ? pedidoFailure(cartError) : null)
  useEffect(() => { if (error) errorRender.current?.focus() }, [error])

  const items = Array.isArray(cart?.items) ? cart.items : []
  const restauranteId = cart?.restauranteId ?? null
  const total = items.reduce((sum, item) => sum + (item.subtotal ?? 0), 0)

  async function confirmar(event) {
    event.preventDefault()
    if (busy) return
    const draft = {
      restauranteId,
      direccionEntrega,
      items: items.map(item => ({ productoId: item.productoId, cantidad: item.cantidad })),
    }
    const fallos = validatePedidoDraft(draft)
    setErrores(fallos)
    if (Object.keys(fallos).length) return

    setAviso('')
    const client = (await import('../../services/httpClient.js')).default
    controller.connect(createPedidoHttpAdapter(client))
    const creado = await controller.create(draft)
    if (!creado) return // el error se muestra; no se reintenta la creación automáticamente
    const pedido = controller.getSnapshot().pedido
    try {
      await client.delete('/carrito')
    } catch {
      setAviso('El pedido se creó, pero no se pudo vaciar el carrito. Puedes vaciarlo desde Carrito; no se creará otro pedido.')
    }
    navigate(ROUTE_PATHS.pago.replace(':pedidoId', pedido.pedidoId))
  }

  return <div className="pedidos-section" aria-busy={busy}>
    {cargando && <p role="status">Consultando tu carrito…</p>}
    {error && <div ref={errorRender} role="alert" className="pedidos-error">
      <p>{error.message}</p>
      {error.code === 'INTERACTION_REQUIRED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => authorizeApi(destination)}>Continuar con Microsoft</button>}
      {error.code === 'UNAUTHORIZED' && <button type="button" className="button button--primary" disabled={busy}
        onClick={() => login(destination)}>Volver a iniciar sesión</button>}
    </div>}
    {aviso && <p role="alert" className="pedidos-error">{aviso}</p>}
    {!cargando && items.length === 0 && !error && <div className="pedidos-card">
      <p>Tu carrito está vacío. Agrega productos desde Restaurantes para confirmar un pedido.</p>
      <Link className="button button--primary" to={ROUTE_PATHS.restaurantes}>Explorar restaurantes</Link>
    </div>}
    {!cargando && items.length > 0 && <form className="pedidos-card" onSubmit={confirmar} noValidate>
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
          {state.status === 'saving' ? 'Creando pedido…' : 'Confirmar pedido'}
        </button>
        <Link className="button button--secondary" to={ROUTE_PATHS.cart}>Volver al carrito</Link>
      </div>
      <p className="pedidos-notice">Al confirmar se crea el pedido y luego se vacía el carrito. Ante un error no se crea un segundo pedido.</p>
    </form>}
  </div>
}
