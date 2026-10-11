import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createServer } from 'vite'
import { ApiAccessError } from '../src/auth/ApiAccessError.js'

// JSX/handlers reales; hooks, sesión y transporte controlados, sin Entra ni broker.
async function fixture(kind, failure) {
  const key = `__recovery_${kind}_${failure}`
  const slots = [], effects = [], calls = []
  let index = 0, getError = failure, focuses = 0
  const cart = { restauranteId: 20, items: [{ productoId: 101, cantidad: 1, subtotal: 5500 }] }
  const h = {
    useState(initial) {
      const n = index++
      if (!(n in slots)) {
        // Borrador previo a una lectura fallida (p.ej. al revalidar la sesión).
        slots[n] = kind === 'checkout' && initial === '' ? 'Dirección conservada'
          : typeof initial === 'function' ? initial() : initial
      }
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
      async get(path) {
        calls.push({ method: 'GET', path })
        if (getError) throw new ApiAccessError(getError === '503' ? 'API_ERROR' : 'API_TIMEOUT', getError === '503' ? 503 : undefined)
        return { status: 200, data: kind === 'checkout' ? cart : [] }
      },
      async post(path, body, options) {
        calls.push({ method: 'POST', path, body, key: options?.headers?.['Idempotency-Key'] })
        throw new ApiAccessError('API_TIMEOUT')
      },
      async delete(path) { calls.push({ method: 'DELETE', path }); throw new Error('Unexpected DELETE') },
    },
  }
  globalThis[key] = h
  const target = kind === 'checkout' ? 'pedidos/RealConfirmarPedidoPanel.jsx' : 'pagos/RealPagoPanel.jsx'
  const server = await createServer({
    define: { 'import.meta.env.VITE_PEDIDOS_CARRITO_MODE': JSON.stringify('HTTP') },
    cacheDir: 'node_modules/.vite-read-recovery',
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom',
    plugins: [{ name: 'read-recovery-fixtures', enforce: 'pre',
      resolveId(id) { if (id.startsWith('virtual:recovery-')) return id },
      load(id) {
        if (id === 'virtual:recovery-hooks') return `const h=globalThis[${JSON.stringify(key)}]; export const {useState,useRef,useEffect,useSyncExternalStore}=h;`
        if (id === 'virtual:recovery-router') return 'export const Link="a"; export function useLocation(){return {pathname:"/test",search:"",hash:""}}'
        if (id === 'virtual:recovery-session') return 'export function useAuthSession(){return {busy:false,login(){},authorizeApi(){}}}'
        if (id === 'virtual:recovery-http') return `export default globalThis[${JSON.stringify(key)}].client;`
      },
      transform(source, id) {
        if (!id.replaceAll('\\', '/').endsWith(target)) return
        return source.replace("from 'react'", "from 'virtual:recovery-hooks'")
          .replace("from 'react-router'", "from 'virtual:recovery-router'")
          .replace("from '../../auth/useAuthSession.js'", "from 'virtual:recovery-session'")
          .replaceAll("import('../../services/httpClient.js')", "import('virtual:recovery-http')")
      },
    }],
  })
  const Panel = (await server.ssrLoadModule('/src/features/' + target)).default
  const render = () => {
    index = 0
    const tree = Panel({ pedidoId: 1 })
    // React conecta refs al DOM antes de ejecutar efectos; simular ese orden.
    for (const node of nodes(tree)) if (node.props?.ref) node.props.ref.current = { focus() { focuses++ } }
    for (const effect of effects.splice(0)) effect()
    return tree
  }
  const until = async predicate => {
    const end = Date.now() + 2000
    while (!predicate(render())) { if (Date.now() > end) throw new Error('Fixture did not settle'); await new Promise(resolve => setTimeout(resolve, 5)) }
  }
  await until(tree => nodes(tree).some(x => x.props?.role === 'alert'))
  return { render, calls, until, focusCount: () => focuses, recover: () => { getError = null },
    failGet: () => { getError = failure }, controller: () => slots[0],
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
const button = (tree, label) => nodes(tree).find(x => x.type === 'button' && text(x) === label)

for (const failure of ['503', 'timeout']) {
  for (const kind of ['pago', 'checkout']) test(`${kind}: GET ${failure}, foco y recuperación sin escrituras`, async () => {
    const f = await fixture(kind, failure)
    try {
      const alert = nodes(f.render()).find(x => x.props?.role === 'alert')
      assert.equal(alert.props.tabIndex, -1)
      assert.ok(f.focusCount() > 0)
      const retry = button(f.render(), kind === 'pago' ? 'Reintentar consulta' : 'Reintentar consulta del carrito')
      f.recover()
      await Promise.all([retry.props.onClick(), retry.props.onClick()])
      const tree = f.render()
      assert.equal(nodes(tree).some(x => x.props?.role === 'alert'), false)
      assert.deepEqual(f.calls.map(x => x.method), ['GET', 'GET'])
      if (kind === 'checkout') assert.equal(nodes(tree).find(x => x.type === 'input').props.value, 'Dirección conservada')
    } finally { await f.close() }
  })
}

test('Pago: recuperación de lectura conserva la clave de un POST incierto y no genera otro pago', async () => {
  const f = await fixture('pago', 'timeout')
  try {
    f.recover()
    await button(f.render(), 'Reintentar consulta').props.onClick()
    await nodes(f.render()).find(x => x.type === 'form').props.onSubmit({ preventDefault() {} })
    const first = f.calls.find(x => x.method === 'POST')
    assert.ok(first.key)
    assert.equal(button(f.render(), 'Reintentar consulta'), undefined)
    f.failGet()
    await f.controller().load(1)
    f.recover()
    await button(f.render(), 'Reintentar consulta').props.onClick()
    assert.equal(f.calls.filter(x => x.method === 'POST').length, 1)
    assert.equal(f.controller().getSnapshot().idempotencyKey, first.key)
    await nodes(f.render()).find(x => x.type === 'form').props.onSubmit({ preventDefault() {} })
    assert.deepEqual(f.calls.filter(x => x.method === 'POST').map(x => x.key), [first.key, first.key])
  } finally { await f.close() }
})
