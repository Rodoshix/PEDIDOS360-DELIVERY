import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createServer } from 'vite'
import { parseCheckoutCoordination } from '../src/config/checkoutCoordination.js'

test('modo de checkout predeterminado y valores estrictos', () => {
  assert.equal(parseCheckoutCoordination(undefined), 'HTTP')
  assert.equal(parseCheckoutCoordination('HTTP'), 'HTTP')
  assert.equal(parseCheckoutCoordination('RABBITMQ'), 'RABBITMQ')
  for (const value of ['', null, 'rabbitmq', ' HTTP', {}, true]) {
    assert.throws(() => parseCheckoutCoordination(value))
  }
})

// Ejecuta JSX y handlers del panel real. Hooks, sesión y HTTP son fixtures;
// no es navegador E2E ni autenticación Microsoft real.
async function panelFixture(mode) {
  const key = `__checkout_${mode}`
  const slots = []
  const effects = []
  let index = 0
  let posts = 0
  let deletes = 0
  let gets = 0
  let deleteFails = false
  let getFails = false
  let cart = { items: [{ productoId: 101, cantidad: 1, subtotal: 5500 }], restauranteId: 20 }
  const pedido = { pedidoId: 700, usuarioId: 10, restauranteId: 20, direccionEntrega: 'Av. Uno',
    estado: 'CREADO', moneda: 'CLP', total: 5500, fechaCreacion: '2026-10-09T12:00:00Z',
    lineas: [{ lineaId: 1, productoId: 101, cantidad: 1, precioUnitario: 5500, subtotal: 5500 }] }
  const h = {
    useState(initial) {
      const n = index++
      if (!(n in slots)) slots[n] = typeof initial === 'function' ? initial() : initial
      return [slots[n], value => { slots[n] = typeof value === 'function' ? value(slots[n]) : value }]
    },
    useRef(initial) { return h.useState(() => ({ current: initial }))[0] },
    useEffect(effect, deps) {
      const n = index++
      if (!slots[n] || deps.some((x, i) => x !== slots[n][i])) effects.push(effect)
      slots[n] = deps
    },
    useSyncExternalStore(_subscribe, snapshot) { return snapshot() },
    client: {
      async get(path) { assert.equal(path, '/carrito'); gets++; if (getFails) throw new Error('fixture'); return { status: 200, data: structuredClone(cart) } },
      async post(path) { assert.equal(path, '/pedidos'); posts++; return { status: 201, data: pedido } },
      async delete(path) { assert.equal(path, '/carrito'); deletes++; if (deleteFails) throw new Error('fixture'); return { status: 204 } },
    },
  }
  globalThis[key] = h
  const server = await createServer({
    define: { 'import.meta.env.VITE_PEDIDOS_CARRITO_MODE': JSON.stringify(mode) ?? 'undefined' },
    cacheDir: `node_modules/.vite-checkout-${mode}`,
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom',
    plugins: [{ name: 'checkout-fixtures', enforce: 'pre',
      resolveId(id) { if (id.startsWith('virtual:checkout-')) return id },
      load(id) {
        if (id === 'virtual:checkout-hooks') return `const h=globalThis[${JSON.stringify(key)}]; export const {useState,useRef,useEffect,useSyncExternalStore}=h;`
        if (id === 'virtual:checkout-router') return 'export const Link="a"; export function useLocation(){return {pathname:"/pedidos/confirmar",search:"",hash:""}}'
        if (id === 'virtual:checkout-session') return 'export function useAuthSession(){return {busy:false,login(){},authorizeApi(){}}}'
        if (id === 'virtual:checkout-http') return `export default globalThis[${JSON.stringify(key)}].client;`
      },
      transform(source, id) {
        if (!id.replaceAll('\\', '/').endsWith('/RealConfirmarPedidoPanel.jsx')) return
        return source.replace("from 'react'", "from 'virtual:checkout-hooks'")
          .replace("from 'react-router'", "from 'virtual:checkout-router'")
          .replace("from '../../auth/useAuthSession.js'", "from 'virtual:checkout-session'")
          .replaceAll("import('../../services/httpClient.js')", "import('virtual:checkout-http')")
      },
    }],
  })
  const Panel = (await server.ssrLoadModule('/src/features/pedidos/RealConfirmarPedidoPanel.jsx')).default
  const render = () => { index = 0; return Panel() }
  render()
  for (const effect of effects.splice(0)) effect()
  const readyUntil = Date.now() + 2000
  while (!nodes(render()).some(n => n.type === 'form')) {
    if (Date.now() >= readyUntil) { await server.close(); delete globalThis[key]; throw new Error('Checkout fixture did not load') }
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  return { render, counts: () => ({ posts, deletes, gets }),
    setCart: value => { cart = value }, failDelete: () => { deleteFails = true }, failGet: () => { getFails = true },
    close: async () => { await server.close(); delete globalThis[key] } }
}

function nodes(tree) {
  if (!tree || typeof tree !== 'object') return []
  if (Array.isArray(tree)) return tree.flatMap(nodes)
  return [tree, ...nodes(tree.props?.children)]
}
function text(tree) {
  if (typeof tree === 'string' || typeof tree === 'number') return String(tree)
  if (Array.isArray(tree)) return tree.map(text).join(' ')
  return tree?.props ? text(tree.props.children) : ''
}
function button(tree, label) { return nodes(tree).find(n => n.type === 'button' && text(n) === label) }
async function submit(f) {
  let tree = f.render()
  nodes(tree).find(n => n.type === 'input').props.onChange({ target: { value: 'Av. Uno' } })
  tree = f.render()
  const handler = nodes(tree).find(n => n.type === 'form').props.onSubmit
  await Promise.all([handler({ preventDefault() {} }), handler({ preventDefault() {} })])
  return handler
}

test('HTTP conserva DELETE tras 201 y reintenta solo DELETE sin POST duplicado', async () => {
  const f = await panelFixture('HTTP')
  try {
    f.failDelete()
    const handler = await submit(f)
    assert.deepEqual(f.counts(), { posts: 1, deletes: 1, gets: 1 })
    await button(f.render(), 'Reintentar vaciar carrito').props.onClick()
    await handler({ preventDefault() {} }) // callback obsoleto después del 201
    assert.deepEqual(f.counts(), { posts: 1, deletes: 2, gets: 1 })
  } finally { await f.close() }
})

test('HTTP exitoso mantiene anuncio de vaciado tras DELETE', async () => {
  const f = await panelFixture(undefined)
  try { await submit(f); assert.equal(f.counts().deletes, 1); assert.match(text(f.render()), /Carrito vaciado\./) }
  finally { await f.close() }
})

test('RabbitMQ solo registra, consulta cambios posteriores y nunca borra ni repite POST', async () => {
  const f = await panelFixture('RABBITMQ')
  try {
    const handler = await submit(f)
    assert.deepEqual(f.counts(), { posts: 1, deletes: 0, gets: 1 })
    assert.equal(button(f.render(), 'Reintentar vaciar carrito'), undefined)
    assert.match(text(f.render()), /se procesa separadamente; todavía no está confirmado/)
    assert.doesNotMatch(text(f.render()), /Carrito vaciado\./)
    f.setCart({ restauranteId: 20, items: [{ productoId: 101 }, { productoId: 102 }] })
    await button(f.render(), 'Consultar carrito').props.onClick()
    assert.match(text(f.render()), /Última lectura:\s+2\s+producto/)
    f.setCart({ items: [] })
    await button(f.render(), 'Consultar carrito').props.onClick()
    assert.match(text(f.render()), /Una lectura vacía no confirma/)
    f.failGet()
    await button(f.render(), 'Consultar carrito').props.onClick()
    assert.match(text(f.render()), /No se pudo consultar/)
    await handler({ preventDefault() {} })
    assert.deepEqual(f.counts(), { posts: 1, deletes: 0, gets: 4 })
  } finally { await f.close() }
})

