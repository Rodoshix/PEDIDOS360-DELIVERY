import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { renderToStaticMarkup } from 'react-dom/server'

async function fixture(props, read) {
 const slots = [], effects = [], cleanups = []; let index = 0
 const hooks = {
  useState(initial) { const n = index++; if (!(n in slots)) slots[n] = initial; return [slots[n], value => { slots[n] = typeof value === 'function' ? value(slots[n]) : value }] },
  useEffect(effect, deps) { const n = index++; if (!slots[n] || deps.some((x, i) => x !== slots[n][i])) { cleanups[n]?.(); effects.push(() => { cleanups[n] = effect() }); slots[n] = deps } },
 }
 globalThis.__kafkaUiFixture = { hooks, client: { get: read } }
 const server = await createServer({ cacheDir: 'node_modules/.vite-kafka-observations', optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom', plugins: [{ name: 'kafka-ui-fixture', enforce: 'pre',
  resolveId(id) { if (id === 'virtual:kafka-hooks' || id.endsWith('/services/httpClient.js')) return id === 'virtual:kafka-hooks' ? id : 'virtual:kafka-client' },
  load(id) { if (id === 'virtual:kafka-hooks') return 'export const { useState, useEffect } = globalThis.__kafkaUiFixture.hooks;'; if (id === 'virtual:kafka-client') return 'export default globalThis.__kafkaUiFixture.client;' },
  transform(source, id) { if (id.replaceAll('\\', '/').endsWith('/carrito/KafkaObservations.jsx')) return source.replace("from 'react'", "from 'virtual:kafka-hooks'") },
 }] })
 const Component = (await server.ssrLoadModule('/src/features/carrito/KafkaObservations.jsx')).default
 function render() { index = 0; const tree = Component(props); for (const effect of effects.splice(0)) effect(); return tree }
 return { props, render, async settle() {
  let tree = render()
  const deadline = Date.now() + 2000
  while (nodes(tree).some(n => n.type === 'button' && n.props.disabled)) {
   if (Date.now() >= deadline) throw new Error('Las observaciones no finalizaron su carga dentro del plazo del fixture.')
   await new Promise(resolve => setTimeout(resolve, 5))
   tree = render()
  }
  return tree
 }, async close() { cleanups.forEach(fn => fn?.()); await server.close(); delete globalThis.__kafkaUiFixture } }
}
const nodes = tree => !tree || typeof tree !== 'object' ? [] : Array.isArray(tree) ? tree.flatMap(nodes) : [tree, ...nodes(tree.props?.children)]
const known = { id: '42', conocido: true, valor: 'CERRADO', version: '2', observadoEn: '2026-11-09T15:00:00Z' }
test('optional observations are disabled without requests', async () => {
 let requests = 0; const f = await fixture({ enabled: false, restaurantId: 42 }, async () => { requests++; return { data: known } })
 try { assert.equal(await f.settle(), null); assert.equal(requests, 0) } finally { await f.close() }
})
test('restaurant observation is informative and failure can be retried independently', async () => {
 let fail = true; const f = await fixture({ enabled: true, restaurantId: 42 }, async () => { if (fail) throw new Error('private upstream'); return { data: known } })
 try {
  assert.match(renderToStaticMarkup(await f.settle()), /Información adicional no disponible/)
  fail = false; nodes(f.render()).find(n => n.type === 'button').props.onClick()
  assert.match(renderToStaticMarkup(await f.settle()), /CERRADO/)
  f.props.restaurantId = 43
  assert.doesNotMatch(renderToStaticMarkup(f.render()), /CERRADO/)
 } finally { await f.close() }
})
test('cart warnings do not mutate the aggregate and unknown is explicit', async () => {
 const cart = { id: 1, version: 5, total: 100, items: [{ productoId: 42, cantidad: 1 }] }, before = JSON.stringify(cart)
 const f = await fixture({ enabled: true, cart }, async () => ({ data: { informativo: true, items: [{ ...known, valor: 'false' }, { id: '43', conocido: false }] } }))
 try { const html = renderToStaticMarkup(await f.settle()); assert.match(html, /No disponible/); assert.match(html, /Sin información recibida/); assert.equal(JSON.stringify(cart), before) } finally { await f.close() }
})
