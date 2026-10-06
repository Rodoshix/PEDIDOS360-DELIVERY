// Exclusivo del banco loopback. No es backend, contrato nuevo ni fallback de producción.
export function createPreviewApi() {
  let scenario = 'normal'
  const users = new Map()
  function user(key) {
    if (!users.has(key)) users.set(key, {
      profile: { id: key === 'B' ? 2 : 1, nombre: key === 'B' ? 'Bea' : 'Alex', apellido: 'Ejemplo', email: `${key.toLowerCase()}@example.test`, telefono: null,
        activo: true, creadoEn: '2026-09-10T12:00:00Z', actualizadoEn: '2026-09-10T12:00:00Z' },
      items: [{ productoId: 1, nombre: 'Hamburguesa de prueba', cantidad: 2, precioUnitario: 6990, subtotal: 13980 },
        { productoId: 2, nombre: 'Papas de prueba', cantidad: 1, precioUnitario: 2990, subtotal: 2990 }], version: 0,
    })
    return users.get(key)
  }
  return async function middleware(req, res, next) {
    if (req.url === '/__preview/scenario' && req.method === 'POST') {
      let text = ''; for await (const chunk of req) text += chunk
      if (!['normal', 'empty', 'error', 'slow'].includes(text)) { res.statusCode = 400; res.end(); return }
      scenario = text; users.clear(); res.statusCode = 204; res.end(); return
    }
    if (!req.url?.startsWith('/api/')) return next()
    const key = req.headers.authorization === 'Bearer preview-only-B' ? 'B' : 'A'
    const state = user(key)
    const send = (status, body) => { res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(body === undefined ? undefined : JSON.stringify(body)) }
    if (scenario === 'slow') await new Promise(resolve => setTimeout(resolve, 1500))
    if (scenario === 'error') return send(503, {})
    if (req.url === '/api/usuarios/me' && req.method === 'GET') return send(scenario === 'empty' && !state.created ? 404 : 200, state.profile)
    if ((req.url === '/api/usuarios' && req.method === 'POST') || (req.url === `/api/usuarios/${state.profile.id}` && req.method === 'PUT')) {
      let text = ''; for await (const chunk of req) text += chunk
      try { state.profile = { ...state.profile, ...JSON.parse(text) }; state.created = true; return send(req.method === 'POST' ? 201 : 200, state.profile) }
      catch { return send(400, {}) }
    }
    if (scenario === 'empty') state.items = []
    if (req.url === '/api/carrito' && req.method === 'DELETE') { state.items = []; state.version++; return send(204) }
    const itemId = req.url.match(/^\/api\/carrito\/items\/(\d+)$/)?.[1]
    if (itemId && req.method === 'DELETE') { state.items = state.items.filter(item => item.productoId !== Number(itemId)); state.version++; return send(204) }
    if (itemId && req.method === 'PUT') {
      let text = ''; for await (const chunk of req) text += chunk
      try { const { cantidad } = JSON.parse(text); state.items = state.items.map(item => item.productoId === Number(itemId) ? { ...item, cantidad, subtotal: item.precioUnitario * cantidad } : item); state.version++ }
      catch { return send(400, {}) }
    }
    if ((req.url === '/api/carrito' && req.method === 'GET') || (itemId && req.method === 'PUT')) return send(200, {
      id: key === 'B' ? 2 : 1, restauranteId: state.items.length ? 1 : null, moneda: 'CLP', version: state.version,
      actualizadoEn: '2026-10-06T12:00:00Z', items: state.items, total: state.items.reduce((sum, item) => sum + item.subtotal, 0),
    })
    return send(404, {})
  }
}
