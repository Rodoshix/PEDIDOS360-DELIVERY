import { act } from 'react'
import { createRoot } from 'react-dom/client'
import axios from 'axios'
import KafkaObservations from '../../src/features/carrito/KafkaObservations.jsx'

// No fake hooks or state machine: mount the unchanged component into the browser DOM.
globalThis.IS_REACT_ACT_ENVIRONMENT = true
const container = document.querySelector('#fixture')
const result = document.querySelector('#result')
const reports = []
let pending = []
let requests = []
window.__kafkaObservationsTransport = config => new Promise((resolve, reject) => {
  const path = new URL(config.url).pathname
  if (config.method !== 'get' || ![
    '/api/productos/restaurante/42/estado-restaurante', '/api/carrito/disponibilidad',
  ].includes(path)) throw new Error('Unexpected API request')
  // Record only method/path/signal, never headers or authentication material.
  requests.push({ method: config.method, path, signal: config.signal })
  pending.push({
    signal: config.signal,
    respond(data) { resolve({ data, status: 200, statusText: 'OK', headers: {}, config }) },
    fail() { reject(new axios.AxiosError('synthetic upstream detail', 'ERR_BAD_RESPONSE',
      config, null, { status: 503, data: { detail: 'synthetic upstream detail' } })) },
  })
})
function check(condition, message) { if (!condition) throw new Error(message) }
function html() { return container.textContent }
async function waitFor(condition) {
  const deadline = Date.now() + 5000
  while (!condition()) {
    if (Date.now() > deadline) throw new Error('Fixture deadline exceeded')
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)) })
  }
}
async function respond(action) { await act(async () => { action() }); await waitFor(() => !container.querySelector('button').disabled) }
async function refresh() {
  const count = requests.length
  await act(async () => { container.querySelector('button').click() })
  check(container.querySelector('button').disabled, 'Refresh must become pending')
  check(html().includes('Consultando información adicional'), 'Loading must be visible')
  await waitFor(() => requests.length === count + 1)
}
const timestamp1 = '2026-11-09T15:00:00Z'
const timestamp2 = '2026-11-13T15:00:00Z'
const known = (kind, fresh) => ({ id: '42', conocido: true,
  valor: kind === 'restaurant' ? (fresh ? 'ABIERTO' : 'CERRADO') : (fresh ? 'true' : 'false'),
  version: fresh ? '2' : '1', observadoEn: fresh ? timestamp2 : timestamp1 })
const body = (kind, item) => kind === 'restaurant' ? item : { informativo: true, items: [item] }

try {
  for (const kind of ['restaurant', 'cart']) {
    pending = []; requests = []
    const cart = { id: 1, version: 5, total: 1000, items: [{ productoId: 42, cantidad: 1 }] }
    const original = JSON.stringify(cart)
    const props = kind === 'restaurant' ? { enabled: true, restaurantId: 42 } : { enabled: true, cart }
    const root = createRoot(container)
    try {
      await act(async () => { root.render(<KafkaObservations {...props} />) })
      await waitFor(() => pending.length === 1)
      check(html().includes('Consultando información adicional'), 'Initial loading missing')
      check(container.querySelector('button').disabled, 'Initial refresh must be disabled')
      await respond(() => pending[0].fail())
      check(html().includes('Información adicional no disponible'), 'Error not represented')
      check(!html().includes('synthetic upstream detail'), 'Private error detail leaked')
      check(requests.length === 1, 'Failure must not automatically retry')

      await refresh()
      await respond(() => pending[1].respond(body(kind, { id: '42', conocido: false })))
      check(html().includes('Sin información recibida'), 'Unknown must be explicit')
      check(!container.querySelector('time'), 'Unknown must not invent a freshness timestamp')

      await refresh()
      await respond(() => pending[2].respond(body(kind, known(kind, false))))
      check(container.querySelector('time')?.dateTime === timestamp1, 'Observed timestamp missing')
      check(html().includes(kind === 'restaurant' ? 'CERRADO' : 'No disponible'), 'Known result missing')

      await refresh()
      check(!container.querySelector('time'), 'Old observation must disappear while refresh is pending')
      await respond(() => pending[3].respond(body(kind, known(kind, true))))
      check(container.querySelector('time')?.dateTime === timestamp2, 'Fresh response did not replace timestamp')
      check(html().includes(kind === 'restaurant' ? 'ABIERTO' : 'Disponible en la última observación'), 'Fresh value missing')
      check(html().includes('no confirma disponibilidad de compra'), 'Informative limit missing')
      check(JSON.stringify(cart) === original, 'Cart aggregate mutated')
      check(requests.length === 4 && requests.every(r => r.method === 'get'), 'Unexpected request or write')

      // Late completion of a cancelled observation must not leak into the next scope.
      await refresh()
      const obsolete = pending[4]
      await act(async () => { root.render(<KafkaObservations enabled={false} />) })
      check(obsolete.signal.aborted && container.textContent === '', 'Unmounted scope was not cancelled')
      await act(async () => { obsolete.respond(body(kind, known(kind, false))) })
      check(container.textContent === '', 'Cancelled response resurfaced')
      reports.push({ kind, status: 'PASS', loading: true, error: true, unknown: true,
        manualRecovery: true, freshnessReplacement: true, cancelledResponseIgnored: true,
        aggregateUnchanged: true, methods: [...new Set(requests.map(r => r.method))], requests: requests.length })
    } finally { await act(async () => { root.unmount() }) }
  }
  result.textContent = JSON.stringify({ status: 'PASS', environment: 'local browser; simulated API transport', reports }, null, 2)
} catch (error) {
  result.textContent = JSON.stringify({ status: 'FAIL', message: error.message, reports }, null, 2)
}
