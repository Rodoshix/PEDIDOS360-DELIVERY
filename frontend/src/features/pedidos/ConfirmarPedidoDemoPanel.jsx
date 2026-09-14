import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link, useNavigate } from 'react-router'
import { createPedidoController } from './pedidoService.js'
import { createPedidoDemoAdapter, PEDIDO_SCENARIOS } from './pedidoDemoAdapter.js'
import { PEDIDO_CATALOGO_DEMO, RESTAURANTES_DEMO } from './pedidoDemo.js'
import { validatePedidoDraft } from './pedidoOperaciones.js'
import { formatClp } from './pedidoFormat.js'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import './pedidos.css'

export default function ConfirmarPedidoDemoPanel() {
  const [controller] = useState(createPedidoController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const navigate = useNavigate()
  const [restauranteId, setRestauranteId] = useState(101)
  const [direccionEntrega, setDireccionEntrega] = useState('Av. Ejemplo 123, Depto 4B')
  const [cantidades, setCantidades] = useState({ 1001: 1, 1002: 1 })
  const [errores, setErrores] = useState({})
  const [scenario, setScenario] = useState('example')
  const errorRender = useRef(null)
  const busy = state.status === 'saving'

  useEffect(() => {
    controller.connect(createPedidoDemoAdapter({ scenario: 'example' }))
    return () => controller.cancelPending()
  }, [controller])

  useEffect(() => { if (state.error) errorRender.current?.focus() }, [state.error])

  const disponibles = PEDIDO_CATALOGO_DEMO.filter(item => item.restauranteId === restauranteId && item.disponible)
  const items = disponibles
    .map(item => ({ productoId: item.productoId, cantidad: cantidades[item.productoId] ?? 0 }))
    .filter(item => item.cantidad > 0)
  const total = disponibles.reduce((sum, item) => sum + item.precioUnitario * (cantidades[item.productoId] ?? 0), 0)

  function cambiarCantidad(productoId, valor) {
    const cantidad = Number.parseInt(valor, 10)
    setCantidades(prev => ({ ...prev, [productoId]: Number.isInteger(cantidad) && cantidad > 0 ? cantidad : 0 }))
  }

  async function confirmar(event) {
    event.preventDefault()
    if (busy) return
    const draft = { restauranteId, direccionEntrega, items }
    const fallos = validatePedidoDraft(draft)
    setErrores(fallos)
    if (Object.keys(fallos).length) return
    controller.connect(createPedidoDemoAdapter({ scenario }))
    const creado = await controller.create(draft)
    if (creado && state.pedido) navigate(ROUTE_PATHS.pedidoDetalle.replace(':id', state.pedido.pedidoId))
  }

  return (
    <div className="pedidos-section" aria-busy={busy}>
      <p className="pedidos-notice" role="status">Datos ficticios: el pedido se crea solo en el ejemplo local; no se guarda en Pedidos.</p>
      {state.error && (
        <div ref={errorRender} className="pedidos-error" role="alert"><p>{state.error.message}</p></div>
      )}
      <form className="pedidos-card" onSubmit={confirmar} noValidate>
        <h2>Datos de entrega</h2>
        <label>Restaurante
          <select value={restauranteId} disabled={busy} onChange={event => { setRestauranteId(Number(event.target.value)); setCantidades({}) }}>
            {RESTAURANTES_DEMO.map(restaurante => (
              <option key={restaurante.restauranteId} value={restaurante.restauranteId}>{restaurante.nombre}</option>
            ))}
          </select>
        </label>
        {errores.restauranteId && <p className="pedidos-error" role="alert">{errores.restauranteId}</p>}
        <label>Dirección de entrega
          <input type="text" value={direccionEntrega} maxLength={255} disabled={busy}
            onChange={event => setDireccionEntrega(event.target.value)} />
        </label>
        {errores.direccionEntrega && <p className="pedidos-error" role="alert">{errores.direccionEntrega}</p>}
        <h2>Productos</h2>
        {disponibles.length === 0 && <p className="pedidos-notice">Este restaurante de prueba no tiene productos disponibles.</p>}
        {disponibles.map(item => (
          <label key={item.productoId}>
            {item.nombre} · {formatClp(item.precioUnitario)}
            <input type="number" min="0" max="99" disabled={busy} value={cantidades[item.productoId] ?? 0}
              onChange={event => cambiarCantidad(item.productoId, event.target.value)} />
          </label>
        ))}
        {errores.items && <p className="pedidos-error" role="alert">{errores.items}</p>}
        <p className="pedidos-total">Total: {formatClp(total)}</p>
        <div className="pedidos-actions">
          <button type="submit" className="button button--primary" disabled={busy}>
            {busy ? 'Creando pedido…' : 'Confirmar pedido'}
          </button>
          <Link className="button button--secondary" to={ROUTE_PATHS.cart}>Volver al carrito</Link>
        </div>
      </form>
      <section className="pedidos-card">
        <h2>Pruebas de desarrollo</h2>
        <label className="pedidos-scenario">Escenario de creación
          <select value={scenario} disabled={busy} onChange={event => setScenario(event.target.value)}>
            {PEDIDO_SCENARIOS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
      </section>
    </div>
  )
}
