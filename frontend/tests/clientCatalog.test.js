import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createServer } from 'vite'
import { renderToStaticMarkup } from 'react-dom/server'
import { ApiAccessError } from '../src/auth/ApiAccessError.js'

// Componente y handlers reales; hooks y lecturas controlados, sin backend/Entra.
async function fixture({ empty = false, products = [], failure = null, estado = 'ABIERTO' } = {}) {
  const key = '__clientCatalogFixture', slots = [], effects = [], calls = [], additions = []
  let index = 0, currentFailure = failure, focuses = 0
  const h = {
    useState(initial) { const n = index++; if (!(n in slots)) slots[n] = typeof initial === 'function' ? initial() : initial
      return [slots[n], value => { slots[n] = typeof value === 'function' ? value(slots[n]) : value }] },
    useRef(initial) { return h.useState(() => ({ current: initial }))[0] },
    useEffect(effect, deps) { const n = index++; if (!slots[n] || deps.some((x, i) => x !== slots[n][i])) effects.push(effect); slots[n] = deps },
  }
  globalThis[key] = h
  const server = await createServer({ cacheDir: 'node_modules/.vite-client-catalog', optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom',
    plugins: [{ name: 'client-catalog-fixture', enforce: 'pre',
      resolveId(id) { if (id === 'virtual:catalog-hooks') return id },
      load(id) { if (id === 'virtual:catalog-hooks') return `export const {useState,useRef,useEffect}=globalThis.${key};` },
      transform(source, id) { if (id.replaceAll('\\', '/').endsWith('/carrito/RealCatalog.jsx')) return source.replace("from 'react'", "from 'virtual:catalog-hooks'") },
    }],
  })
  const Catalog = (await server.ssrLoadModule('/src/features/carrito/RealCatalog.jsx')).default
  const props = { disabled: false, onAdd: id => additions.push(id), onError() {}, adapter: {
    async restaurants() { calls.push('restaurants'); if (currentFailure) throw currentFailure; return empty ? [] : [{ id: 201, nombre: 'Restaurante de prueba', estado }] },
    async products(id) { calls.push(['products', id]); return products },
  } }
  function render() { index = 0; const tree = Catalog(props)
    for (const node of nodes(tree)) if (node.props?.ref) node.props.ref.current = { focus() { focuses++ } }
    for (const effect of effects.splice(0)) effect()
    return tree }
  async function settle() { render(); await new Promise(resolve => setTimeout(resolve, 10)); return render() }
  await settle()
  return { render, settle, props, calls, additions, focuses: () => focuses,
    recover() { currentFailure = null },
    select() { nodes(render()).find(x => x.props?.label === 'Restaurante').props.onChange({ target: { value: '201' } }) },
    close: async () => { await server.close(); delete globalThis[key] } }
}
function nodes(tree) { if (!tree || typeof tree !== 'object') return []; if (Array.isArray(tree)) return tree.flatMap(nodes); return [tree, ...nodes(tree.props?.children)] }
const addButtons = tree => nodes(tree).filter(x => x.props?.['aria-label']?.startsWith('Agregar '))
const available = { id: 301, restauranteId: 201, nombre: 'Producto de prueba', precio: 6990, disponible: true }

test('Catálogo: selección conserva asociación, precio CLP, disponibilidad y unidad agregada', async () => {
  const f = await fixture({ products: [available, { ...available, id: 302, nombre: 'Agotado', disponible: false }] })
  try {
    assert.equal(f.calls.some(Array.isArray), false)
    f.select(); const tree = await f.settle()
    assert.deepEqual(f.calls.filter(Array.isArray), [['products', 201]])
    const buttons = addButtons(tree)
    assert.equal(buttons[0].props.disabled, false); assert.equal(buttons[1].props.disabled, true)
    buttons[0].props.onClick(); assert.deepEqual(f.additions, [301])
    const html = renderToStaticMarkup(tree)
    assert.match(html, /6\.990/); assert.match(html, /No disponible/)
    assert.match(html, /<label[^>]+for=/); assert.match(html, /<select[^>]+aria-describedby=/)
    f.props.disabled = true
    assert.ok(addButtons(f.render()).every(x => x.props.disabled))
  } finally { await f.close() }
})
test('Catálogo: restaurante cerrado impide agregar aunque el producto esté disponible', async () => {
  const f = await fixture({ estado: 'CERRADO', products: [available] })
  try { f.select(); const tree = await f.settle(); assert.equal(addButtons(tree)[0].props.disabled, true)
    assert.match(renderToStaticMarkup(tree), /no está abierto/) } finally { await f.close() }
})
test('Catálogo: distingue restaurantes vacíos de productos vacíos', async () => {
  const f = await fixture({ empty: true })
  try { assert.match(renderToStaticMarkup(f.render()), /No hay restaurantes disponibles/); assert.deepEqual(f.calls, ['restaurants']) } finally { await f.close() }
  const g = await fixture()
  try { g.select(); assert.match(renderToStaticMarkup(await g.settle()), /no tiene productos/); assert.deepEqual(g.additions, []) } finally { await g.close() }
})
for (const failure of [new ApiAccessError('API_ERROR', 503), new ApiAccessError('API_TIMEOUT'), new ApiAccessError('API_ERROR', 403)]) {
  test(`Catálogo: error ${failure.status || failure.code} con foco y recuperación de lectura sin agregar`, async () => {
    const f = await fixture({ failure })
    try {
      const alert = nodes(f.render()).find(x => x.props?.role === 'alert')
      assert.equal(alert.props.tabIndex, -1); assert.ok(f.focuses() > 0)
      f.recover(); nodes(f.render()).find(x => x.props?.children === 'Actualizar catálogo').props.onClick()
      assert.equal(nodes(f.render()).some(x => x.props?.label === 'Consultando catálogo…'), true)
      await f.settle(); assert.equal(nodes(f.render()).some(x => x.props?.role === 'alert'), false)
      assert.deepEqual(f.calls, ['restaurants', 'restaurants']); assert.deepEqual(f.additions, [])
    } finally { await f.close() }
  })
}
