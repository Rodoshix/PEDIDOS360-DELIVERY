import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiAccessError } from '../src/auth/ApiAccessError.js'
import { createCatalogAdminController, validateCatalogDraft } from '../src/features/admin/catalogAdminService.js'
import { createCatalogAdminHttpAdapter } from '../src/features/admin/catalogAdminHttpAdapter.js'

const restaurant = { id: 1, nombre: 'Local', descripcion: '', direccion: '', estado: 'ABIERTO' }
const product = { id: 2, nombre: 'Plato', descripcion: '', restauranteId: 1, categoria: 'Principal', precio: '12.50', disponible: true }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
test('validación coincide con DTO: nombre/estado obligatorios, dirección opcional, límites de texto', () => {
  assert.deepEqual(validateCatalogDraft('restaurantes', restaurant), {})
  assert.ok(validateCatalogDraft('restaurantes', { ...restaurant, nombre: ' ', estado: 'OTRO', descripcion: 'x'.repeat(501) }).nombre)
  assert.ok(validateCatalogDraft('restaurantes', { ...restaurant, direccion: 'x'.repeat(256) }).direccion)
})
test('producto valida asociación, categoría, disponibilidad y precio decimal persistible', () => {
  assert.deepEqual(validateCatalogDraft('productos', product), {})
  for (const precio of ['0', '-1', '12,5', 'NaN', '1e2', '1.001', '100000000', '']) assert.ok(validateCatalogDraft('productos', { ...product, precio }).precio, precio)
  assert.deepEqual(Object.keys(validateCatalogDraft('productos', { ...product, restauranteId: 0, categoria: '', disponible: 'true' })).sort(), ['categoria', 'disponible', 'restauranteId'])
})
test('adapter usa BFF para listar, detalle, alta, edición y desactivación sin enviar identidad o ID del body', async () => {
  const calls = [], client = {
    get: async path => { calls.push(['GET', path]); return { status: 200, data: path === '/restaurantes' ? [restaurant] : restaurant } },
    post: async (path, body) => { calls.push(['POST', path, body]); return { status: 201, data: restaurant } },
    put: async (path, body) => { calls.push(['PUT', path, body]); return { status: 200, data: restaurant } },
    delete: async path => { calls.push(['DELETE', path]); return { status: 204 } },
  }
  const adapter = createCatalogAdminHttpAdapter(client, 'restaurantes')
  assert.equal((await adapter.list('')).rows.length, 1); assert.equal((await adapter.detail(1)).id, 1)
  await adapter.write({ type: 'save', draft: { ...restaurant, roles: ['ADMIN'] } })
  await adapter.write({ type: 'save', id: 1, draft: restaurant }); await adapter.write({ type: 'deactivate', id: 1 })
  assert.deepEqual(calls.map(call => call.slice(0, 2)), [['GET', '/restaurantes'], ['GET', '/restaurantes/1'], ['POST', '/restaurantes'], ['PUT', '/restaurantes/1'], ['DELETE', '/restaurantes/1']])
  assert.deepEqual(Object.keys(calls[2][2]).sort(), ['descripcion', 'direccion', 'estado', 'nombre'])
})
test('productos filtra por restaurante y usa query booleana para disponibilidad', async () => {
  const calls = [], client = {
    get: async path => { calls.push(path); return { status: 200, data: path === '/restaurantes' ? [restaurant] : [product] } },
    post: async (path, body) => { calls.push([path, body]); return { status: 201, data: product } },
    put: async () => ({ status: 200, data: product }),
    patch: async path => { calls.push(path); return { status: 200, data: { ...product, disponible: false } } },
  }
  const adapter = createCatalogAdminHttpAdapter(client, 'productos')
  await adapter.list('1'); await adapter.write({ type: 'save', draft: product }); await adapter.write({ type: 'save', id: 2, draft: product })
  await adapter.write({ type: 'availability', id: 2, disponible: false })
  assert.equal(calls[0], '/productos/restaurante/1'); assert.equal(calls[1], '/restaurantes'); assert.equal(calls.at(-1), '/productos/2/disponibilidad?disponible=false')
  assert.equal(calls[2][1].precio, 12.5)
})
test('errores de autorización no filtran respuesta ni reintentan escrituras', async () => {
  let calls = 0
  for (const [status, code] of [[401, 'UNAUTHORIZED'], [403, 'FORBIDDEN'], [404, 'NOT_FOUND'], [409, 'CONFLICT'], [502, 'WRITE_FAILED']]) {
    const adapter = createCatalogAdminHttpAdapter({ post: async () => { calls++; throw new ApiAccessError('API_ERROR', status) } }, 'restaurantes')
    await assert.rejects(adapter.write({ type: 'save', draft: restaurant }), error => error.code === code && !error.config && !error.response)
  }
  assert.equal(calls, 5)
})
test('respuestas inválidas y IDs no válidos no se presentan como catálogo', async () => {
  const adapter = createCatalogAdminHttpAdapter({ get: async () => ({ status: 200, data: [{ ...restaurant, estado: 'OTRO' }] }) }, 'restaurantes')
  await assert.rejects(adapter.list(''), { code: 'INVALID_RESPONSE' }); await assert.rejects(adapter.detail('../1'), { code: 'INVALID_COMMAND' })
})
test('controller expone carga, vacío y error de consulta sin conservar autorización implícita para escribir', async () => {
  let fail = false, writes = 0
  const controller = createCatalogAdminController({ list: async () => { if (fail) throw Error('secreto'); return { rows: [], restaurants: [] } }, write: async () => writes++ })
  const load = controller.load(); assert.equal(controller.getState().status, 'loading'); await load
  assert.equal(controller.getState().status, 'ready'); assert.deepEqual(controller.getState().rows, [])
  fail = true; await controller.load(); assert.equal(controller.getState().status, 'error'); assert.doesNotMatch(controller.getState().error.message, /secreto/)
  assert.equal(await controller.write({}), false); assert.equal(writes, 0)
})
test('doble envío solo escribe una vez; error exige consulta nueva y conserva borrador en el consumidor', async () => {
  const pending = deferred(); let writes = 0
  const controller = createCatalogAdminController({ list: async () => ({ rows: [restaurant], restaurants: [] }), write: async () => { writes++; await pending.promise } })
  await controller.load(); const first = controller.write({}); assert.equal(controller.getState().status, 'saving')
  assert.equal(await controller.write({}), false); pending.reject(Error('timeout')); assert.equal(await first, false)
  assert.equal(await controller.write({}), false); assert.equal(writes, 1); assert.equal(controller.getState().error.code, 'RECONCILE')
  assert.equal(await controller.load(), true)
})
test('respuesta de filtro anterior no sobrescribe filtro nuevo; dispose aborta peticiones y notificaciones', async () => {
  const old = deferred(); let aborted = false, events = 0
  const controller = createCatalogAdminController({ list: async (filter, signal) => { signal.addEventListener('abort', () => { aborted = true }); return filter === 'old' ? old.promise : { rows: [product], restaurants: [] } } })
  controller.subscribe(() => events++); const first = controller.load('old'); await controller.load('new')
  old.resolve({ rows: [], restaurants: [] }); await first; assert.equal(controller.getState().rows[0].id, 2)
  const another = deferred(), controller2 = createCatalogAdminController({ list: async (_, signal) => { signal.addEventListener('abort', () => { aborted = true }); return another.promise } })
  const task = controller2.load(); controller2.dispose(); another.resolve({ rows: [], restaurants: [] }); await task
  assert.equal(aborted, true); assert.ok(events >= 3)
})
