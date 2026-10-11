// Exclusivo del banco loopback. No es backend, contrato nuevo ni fallback de producción.
export function createPreviewApi() {
  let scenario = 'normal'
  const users = new Map()
  let restaurants = [{ id: 1, nombre: 'Burger House de prueba', descripcion: 'Catálogo ficticio para revisión visual.', direccion: 'Calle de prueba 123', estado: 'ABIERTO' }]
  let products = [{ id: 1, restauranteId: 1, nombre: 'Hamburguesa de prueba', descripcion: 'Producto ficticio.', precio: 6990, categoria: 'Principal', disponible: true }]
  const commerceScenarios = ['commerce-empty', 'commerce-approved', 'commerce-pending', 'commerce-rejected', 'commerce-uncertain-order', 'commerce-uncertain-payment', 'commerce-delete-error']
  const sampleOrder = key => ({ pedidoId: key === 'B' ? 800 : 700, usuarioId: key === 'B' ? 2 : 1, restauranteId: 1,
    direccionEntrega: 'Dirección ficticia de entrega 123', estado: 'CREADO', moneda: 'CLP', total: 16970, fechaCreacion: '2026-10-09T12:00:00Z',
    lineas: [{ lineaId: 1, productoId: 1, cantidad: 2, precioUnitario: 6990, subtotal: 13980 }, { lineaId: 2, productoId: 2, cantidad: 1, precioUnitario: 2990, subtotal: 2990 }] })
  function user(key) {
    if (!users.has(key)) users.set(key, {
      profile: { id: key === 'B' ? 2 : 1, nombre: key === 'B' ? 'Bea' : 'Alex', apellido: 'Ejemplo', email: `${key.toLowerCase()}@example.test`, telefono: null,
        activo: true, creadoEn: '2026-09-10T12:00:00Z', actualizadoEn: '2026-09-10T12:00:00Z' },
      orders: [sampleOrder(key)], payments: new Map(),
      items: [{ productoId: 1, nombre: 'Hamburguesa de prueba', cantidad: 2, precioUnitario: 6990, subtotal: 13980 },
        { productoId: 2, nombre: 'Papas de prueba', cantidad: 1, precioUnitario: 2990, subtotal: 2990 }], version: 0,
    })
    return users.get(key)
  }
  return async function middleware(req, res, next) {
    if (req.url === '/__preview/scenario' && req.method === 'POST') {
      let text = ''; for await (const chunk of req) text += chunk
      if (!['normal', 'empty', 'error', 'slow', 'catalog-empty-products', 'catalog-unavailable', 'catalog-denied', ...commerceScenarios].includes(text)) { res.statusCode = 400; res.end(); return }
      scenario = text; users.clear(); res.statusCode = 204; res.end(); return
    }
    if (!req.url?.startsWith('/api/')) return next()
    const key = req.headers.authorization === 'Bearer preview-only-B' ? 'B' : 'A'
    const state = user(key)
    const send = (status, body) => { res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(body === undefined ? undefined : JSON.stringify(body)) }
    if (req.url === '/api/restaurantes/admin/acceso') return send(key === 'A' ? 204 : 403)
    if (scenario === 'slow') await new Promise(resolve => setTimeout(resolve, 1500))
    if (scenario === 'error') return send(503, {})
    // Respuestas ficticias de contratos ya existentes. Solo lecturas o clicks explícitos;
    // sin pedidos/pagos reales y fuera del build de producción.
    if (req.method === 'GET' && req.url === '/api/pedidos/me') return send(200, scenario === 'commerce-empty' ? [] : state.orders)
    const orderId = req.url.match(/^\/api\/pedidos\/(\d+)$/)?.[1]
    if (orderId && req.method === 'GET') {
      const order = state.orders.find(row => row.pedidoId === Number(orderId))
      return order ? send(200, order) : send(404, {})
    }
    const paymentOrder = req.url.match(/^\/api\/pagos\/pedido\/(\d+)$/)?.[1]
    if (paymentOrder && req.method === 'GET') {
      const order = state.orders.find(row => row.pedidoId === Number(paymentOrder))
      if (!order) return send(404, {})
      const existing = [...state.payments.values()].filter(row => row.pedidoId === order.pedidoId)
      if (existing.length) return send(200, existing)
      const status = { 'commerce-approved': 'APROBADO', 'commerce-pending': 'PENDIENTE', 'commerce-rejected': 'RECHAZADO' }[scenario]
      return send(200, status ? [{ pagoId: 901, pedidoId: order.pedidoId, usuarioId: state.profile.id, monto: order.total,
        moneda: 'CLP', metodo: status === 'PENDIENTE' ? 'EFECTIVO' : 'TARJETA', estado: status, fecha: '2026-10-09T12:00:00Z' }] : [])
    }
    if (req.method === 'POST' && ['/api/pedidos', '/api/pagos'].includes(req.url)) {
      let text = ''; for await (const chunk of req) text += chunk
      try {
        const body = JSON.parse(text)
        if (req.url === '/api/pedidos') {
          if (scenario === 'commerce-uncertain-order') return send(503, {})
          if (!body.direccionEntrega?.trim() || !body.items?.length) return send(400, {})
          const lineas = body.items.map((item, index) => {
            const cartItem = state.items.find(row => row.productoId === item.productoId)
            if (!cartItem) throw new Error('fixture item')
            return { lineaId: index + 1, productoId: item.productoId, cantidad: item.cantidad,
              precioUnitario: cartItem.precioUnitario, subtotal: cartItem.precioUnitario * item.cantidad }
          })
          const order = { ...sampleOrder(key), pedidoId: Math.max(...state.orders.map(row => row.pedidoId)) + 1,
            direccionEntrega: body.direccionEntrega, lineas, total: lineas.reduce((sum, row) => sum + row.subtotal, 0) }
          state.orders.push(order); return send(201, order)
        }
        if (scenario === 'commerce-uncertain-payment') return send(503, {})
        const order = state.orders.find(row => row.pedidoId === body.pedidoId), idempotencyKey = req.headers['idempotency-key']
        if (!order || !idempotencyKey || !['TARJETA', 'EFECTIVO'].includes(body.metodo)) return send(400, {})
        if (state.payments.has(idempotencyKey)) return send(201, state.payments.get(idempotencyKey))
        const payment = { pagoId: 902 + state.payments.size, pedidoId: order.pedidoId, usuarioId: state.profile.id, monto: order.total,
          moneda: 'CLP', metodo: body.metodo, estado: body.metodo === 'EFECTIVO' ? 'PENDIENTE' : 'APROBADO', fecha: '2026-10-09T12:00:00Z' }
        state.payments.set(idempotencyKey, payment); return send(201, payment)
      } catch { return send(400, {}) }
    }
    if (scenario === 'catalog-denied' && /^\/api\/(restaurantes|productos)/.test(req.url)) return send(403, {})
    const catalog = req.url.match(/^\/api\/(restaurantes|productos)(?:\/(\d+))?(?:\/disponibilidad\?disponible=(true|false))?$/)
    const restaurantProducts = req.url.match(/^\/api\/productos\/restaurante\/(\d+)$/)
    if (restaurantProducts) return send(200, ['empty', 'catalog-empty-products'].includes(scenario) ? [] : products.filter(row => row.restauranteId === Number(restaurantProducts[1])).map(row => scenario === 'catalog-unavailable' ? { ...row, disponible: false } : row))
    if (catalog) {
      const [, kind, id, availability] = catalog
      let rows = kind === 'restaurantes' ? restaurants : products
      if (req.method === 'GET') return id ? send(200, rows.find(row => row.id === Number(id))) : send(200, scenario === 'empty' ? [] : rows)
      if (key !== 'A') return send(403, {})
      if (req.method === 'DELETE') { rows.find(row => row.id === Number(id)).estado = 'INACTIVO'; return send(204) }
      if (req.method === 'PATCH') { const value = rows.find(row => row.id === Number(id)); value.disponible = availability === 'true'; return send(200, value) }
      let text = ''; for await (const chunk of req) text += chunk
      try {
        const body = JSON.parse(text), value = { ...body, id: id ? Number(id) : Math.max(0, ...rows.map(row => row.id)) + 1 }
        rows = id ? rows.map(row => row.id === Number(id) ? value : row) : [...rows, value]
        if (kind === 'restaurantes') restaurants = rows; else products = rows
        return send(id ? 200 : 201, value)
      } catch { return send(400, {}) }
    }
    if (req.url === '/api/usuarios/me' && req.method === 'GET') return send(scenario === 'empty' && !state.created ? 404 : 200, state.profile)
    if ((req.url === '/api/usuarios' && req.method === 'POST') || (req.url === `/api/usuarios/${state.profile.id}` && req.method === 'PUT')) {
      let text = ''; for await (const chunk of req) text += chunk
      try { state.profile = { ...state.profile, ...JSON.parse(text) }; state.created = true; return send(req.method === 'POST' ? 201 : 200, state.profile) }
      catch { return send(400, {}) }
    }
    if (scenario === 'empty') state.items = []
    if (req.url === '/api/carrito' && req.method === 'DELETE') { if (scenario === 'commerce-delete-error') return send(503, {}); state.items = []; state.version++; return send(204) }
    if (req.url === '/api/carrito/items' && req.method === 'POST') {
      let text = ''; for await (const chunk of req) text += chunk
      try {
        const { productoId, cantidad } = JSON.parse(text), product = products.find(row => row.id === productoId)
        if (!product || !product.disponible || scenario === 'catalog-unavailable' || !Number.isInteger(cantidad) || cantidad < 1) return send(400, {})
        const existing = state.items.find(row => row.productoId === productoId)
        if (existing) { existing.cantidad += cantidad; existing.subtotal = existing.cantidad * existing.precioUnitario }
        else state.items.push({ productoId, nombre: product.nombre, cantidad, precioUnitario: product.precio, subtotal: cantidad * product.precio })
        state.version++
        return send(200, { id: key === 'B' ? 2 : 1, restauranteId: 1, moneda: 'CLP', version: state.version,
          actualizadoEn: '2026-10-06T12:00:00Z', items: state.items, total: state.items.reduce((sum, item) => sum + item.subtotal, 0) })
      } catch { return send(400, {}) }
    }
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
