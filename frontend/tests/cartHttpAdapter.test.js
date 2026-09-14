import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createCartHttpAdapter, createCatalogHttpAdapter } from '../src/features/carrito/cartHttpAdapter.js'
import { createCartController } from '../src/features/carrito/cartService.js'
import { ApiAccessError } from '../src/auth/ApiAccessError.js'

const empty = { id: null, restauranteId: null, moneda: 'CLP', total: 0, version: null, actualizadoEn: null, items: [] }
const populated = { id: 1, restauranteId: 20, moneda: 'CLP', total: 200, version: 0, actualizadoEn: '2026-09-12T00:00:00Z',
  items: [{ productoId: 101, nombre: 'Producto', cantidad: 2, precioUnitario: 100, subtotal: 200 }] }
const ok = data => ({ status: 200, data })

test('carrito HTTP consulta y proyecta solo campos válidos; POST y PUT no incluyen identidad ni precios', async () => {
  const calls = []
  const signal = new AbortController().signal
  const adapter = createCartHttpAdapter({
    get: async (path, options) => { calls.push(['GET', path, options]); return ok({ ...empty, token: 'NO_PROPAGAR' }) },
    post: async (...args) => { calls.push(['POST', ...args]); return ok(populated) },
    put: async (...args) => { calls.push(['PUT', ...args]); return ok(populated) },
  })
  assert.deepEqual(await adapter.read({ signal }), empty)
  await adapter.write({ type: 'add', productoId: 101, cantidad: 2, precio: 1, usuarioId: 123 }, { signal })
  await adapter.write({ type: 'quantity', productoId: 101, cantidad: 2 }, { signal })
  assert.deepEqual(calls, [
    ['GET', '/carrito', { signal }],
    ['POST', '/carrito/items', { productoId: 101, cantidad: 2 }, { signal }],
    ['PUT', '/carrito/items/101', { cantidad: 2 }, { signal }],
  ])
})

for (const status of [401, 403, 404, 409, 500]) test('lectura HTTP ' + status + ' no inventa carrito vacío', async () => {
  const adapter = createCartHttpAdapter({ get: async () => { throw new ApiAccessError('API_ERROR', status) } })
  await assert.rejects(adapter.read())
  await assert.rejects(adapter.write({ type: 'clear' }), { code: 'RECONCILE' })
})

test('DELETE se ejecuta una vez y consulta el resultado; si falla la consulta bloquea nuevas escrituras', async () => {
  let reads = 0
  let deletes = 0
  const adapter = createCartHttpAdapter({
    get: async () => { if (++reads === 2) throw new ApiAccessError('API_NETWORK'); return ok(empty) },
    delete: async () => { deletes++; return { status: 204 } },
  })
  await adapter.read()
  await assert.rejects(adapter.write({ type: 'clear' }))
  await assert.rejects(adapter.write({ type: 'clear' }), { code: 'RECONCILE' })
  assert.equal(deletes, 1)
  await adapter.read()
  assert.deepEqual(await adapter.write({ type: 'clear' }), empty)
  assert.equal(deletes, 2)
})

test('escritura incierta conserva carrito y exige lectura antes de repetir sin filtrar causas', async () => {
  let posts = 0
  const controller = createCartController()
  controller.connect(createCartHttpAdapter({
    get: async () => ok(empty),
    post: async () => { posts++; throw new Error('SECRET_INTERNO') },
  }))
  await controller.load()
  await controller.write({ type: 'add', productoId: 101, cantidad: 1 })
  assert.deepEqual(controller.getSnapshot().cart, empty)
  assert.doesNotMatch(controller.getSnapshot().error.message, /SECRET_INTERNO/)
  await controller.write({ type: 'add', productoId: 101, cantidad: 1 })
  assert.equal(posts, 1)
})

test('cancelar sesión ignora respuestas tardías del adaptador real', async () => {
  let resolve
  const controller = createCartController()
  controller.connect(createCartHttpAdapter({ get: () => new Promise(yes => { resolve = yes }) }))
  const pending = controller.load()
  controller.connect(null)
  resolve(ok(populated))
  assert.equal(await pending, false)
  assert.equal(controller.getSnapshot().cart, null)
})

test('catálogo usa rutas fijas, valida asociación y excluye campos desconocidos', async () => {
  let path
  let response = [{ id: 20, nombre: 'Restaurante', estado: 'ABIERTO', secreto: 'NO' }]
  const adapter = createCatalogHttpAdapter({ get: async url => { path = url; return ok(response) } })
  assert.deepEqual(await adapter.restaurants(), [{ id: 20, nombre: 'Restaurante', estado: 'ABIERTO' }])
  assert.equal(path, '/restaurantes')
  response = [{ id: 101, nombre: 'Producto', restauranteId: 20, precio: 100, disponible: true }]
  assert.equal((await adapter.products(20))[0].id, 101)
  assert.equal(path, '/productos/restaurante/20')
  await assert.rejects(adapter.products(21), { code: 'INVALID_RESPONSE' })
  response = [...response, ...response]
  await assert.rejects(adapter.products(20), { code: 'INVALID_RESPONSE' })
  response = null
  await assert.rejects(adapter.restaurants(), { code: 'INVALID_RESPONSE' })
})

test('respuesta malformada tras escritura obliga a reconciliar, no habilita otro POST', async () => {
  const adapter = createCartHttpAdapter({ get: async () => ok(empty), post: async () => ok(null) })
  await adapter.read()
  await assert.rejects(adapter.write({ type: 'add', productoId: 101, cantidad: 1 }), { code: 'INVALID_RESPONSE' })
  await assert.rejects(adapter.write({ type: 'add', productoId: 101, cantidad: 1 }), { code: 'RECONCILE' })
})
