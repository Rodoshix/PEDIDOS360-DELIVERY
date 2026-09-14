import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createCartController, validateCartResponse } from '../src/features/carrito/cartService.js'
import { createCartDemoAdapter, CART_SCENARIOS } from '../src/features/carrito/cartDemoAdapter.js'
import { CartServiceError } from '../src/features/carrito/cartErrors.js'
import { createExampleCart, createEmptyExampleCart } from '../src/features/carrito/cartDemo.js'

const add = { type: 'add', productoId: 1001, cantidad: 1 }
function deferred() {
  let resolve
  const promise = new Promise(yes => { resolve = yes })
  return { promise, resolve }
}
function setup(scenario = 'example') {
  const controller = createCartController()
  const adapter = createCartDemoAdapter({ scenario, delayMs: 0 })
  controller.connect(adapter)
  return { controller, adapter }
}

test('sin adaptador no carga ejemplos ni habilita operaciones', async () => {
  const controller = createCartController()
  assert.equal(controller.getSnapshot().status, 'idle')
  assert.equal(await controller.write(add), false)
  assert.equal(await controller.load(), false)
  assert.equal(controller.getSnapshot().error.code, 'NOT_CONFIGURED')
  assert.equal(controller.getSnapshot().cart, null)
})

test('carga informa loading, bloquea doble consulta y publica DTO seguro e inmutable', async () => {
  const controller = createCartController()
  const result = deferred()
  controller.connect({ read: () => result.promise })
  const states = []
  const unsubscribe = controller.subscribe(() => states.push(controller.getSnapshot().status))
  const load = controller.load()
  assert.equal(await controller.load(), false)
  assert.equal(await controller.write(add), false)
  result.resolve({ ...createExampleCart(), accessToken: 'PRIVADO', roles: ['ADMIN'] })
  assert.equal(await load, true)
  assert.deepEqual(states, ['loading', 'ready'])
  const cart = controller.getSnapshot().cart
  assert.ok(Object.isFrozen(cart) && Object.isFrozen(cart.items) && Object.isFrozen(cart.items[0]))
  assert.equal(cart.accessToken, undefined)
  assert.equal(cart.roles, undefined)
  unsubscribe()
  controller.connect(null)
  assert.deepEqual(states, ['loading', 'ready'])
})

test('vacío requiere un DTO explícito; null y fallos no inventan ausencia', async () => {
  const { controller } = setup('empty')
  assert.equal(await controller.load(), true)
  assert.equal(controller.getSnapshot().status, 'empty')
  controller.connect({ read: async () => null })
  assert.equal(await controller.load(), false)
  assert.equal(controller.getSnapshot().error.code, 'INVALID_RESPONSE')
  controller.clearWriteError()
  assert.equal(controller.getSnapshot().status, 'error')
  assert.equal(await controller.write(add), false)
})

test('consulta falla una vez y solo se recupera al reintentar explícitamente', async () => {
  const { controller } = setup('load-error')
  assert.equal(await controller.load(), false)
  assert.equal(controller.getSnapshot().cart, null)
  assert.equal(controller.getSnapshot().error.code, 'LOAD_FAILED')
  assert.equal(await controller.load(), true)
  assert.equal(controller.getSnapshot().cart.total, 12500)
})

test('payload de operaciones solo contiene tipo, producto y cantidad permitidos', async () => {
  const controller = createCartController()
  const calls = []
  controller.connect({ read: async () => createExampleCart(), write: async payload => {
    calls.push(payload)
    assert.ok(Object.isFrozen(payload))
    return createExampleCart()
  } })
  await controller.load()
  await controller.write({ ...add, cantidad: ' 2 ', total: 1, nombre: 'No enviar', precioUnitario: 0, tenantId: 'NO' })
  await controller.write({ type: 'remove', productoId: 1001, cantidad: 9, total: 1 })
  await controller.write({ type: 'clear', productoId: 999, roles: ['ADMIN'] })
  assert.deepEqual(calls, [{ type: 'add', productoId: 1001, cantidad: 2 }, { type: 'remove', productoId: 1001 }, { type: 'clear' }])
})

test('comandos inválidos se bloquean antes del adaptador, conservando el carrito', async () => {
  const controller = createCartController()
  let calls = 0
  controller.connect({ read: async () => createExampleCart(), write: async () => { calls++ } })
  await controller.load()
  for (const command of [null, {}, { ...add, type: 'checkout' }, { ...add, productoId: -1 }, { ...add, cantidad: '1e1' }]) {
    assert.equal(await controller.write(command), false)
    assert.equal(controller.getSnapshot().cart.total, 12500)
    controller.clearWriteError()
    assert.equal(controller.getSnapshot().status, 'ready')
  }
  assert.equal(calls, 0)
})

test('bloqueo inmediato evita doble escritura y consulta mientras se escribe', async () => {
  const controller = createCartController()
  const result = deferred()
  let calls = 0
  controller.connect({ read: async () => createExampleCart(), write: () => { calls++; return result.promise } })
  await controller.load()
  const write = controller.write(add)
  assert.equal(controller.getSnapshot().status, 'saving')
  assert.equal(await controller.write(add), false)
  assert.equal(await controller.load(), false)
  result.resolve(createExampleCart())
  assert.equal(await write, true)
  assert.equal(calls, 1)
})

for (const scenario of ['write-error', 'conflict']) {
  for (const command of [add, { type: 'quantity', productoId: 1001, cantidad: 4 }, { type: 'remove', productoId: 1002 }, { type: 'clear' }]) {
    test(`${scenario}: ${command.type} conserva datos y permite un reintento manual`, async () => {
      const { controller, adapter } = setup(scenario)
      await controller.load()
      const previous = controller.getSnapshot().cart
      assert.equal(await controller.write(command), false)
      assert.equal(controller.getSnapshot().cart, previous)
      assert.deepEqual(await adapter.read(), createExampleCart())
      assert.equal(await controller.write(command), true)
      assert.equal(controller.getSnapshot().error, null)
      assert.equal(controller.getSnapshot().cart.total, { add: 18000, quantity: 23500, remove: 11000, clear: 0 }[command.type])
    })
  }
}

test('acceso denegado impide consultar/crear y nunca produce carrito vacío', async () => {
  const { controller, adapter } = setup('forbidden')
  assert.equal(await controller.load(), false)
  assert.equal(controller.getSnapshot().error.code, 'FORBIDDEN')
  assert.equal(await controller.write(add), false)
  await assert.rejects(adapter.write(add), { code: 'FORBIDDEN' })
  controller.clearWriteError()
  assert.equal(controller.getSnapshot().status, 'error')
})

test('valida metadatos, líneas únicas, cantidades, precios, subtotales y total de respuesta', () => {
  const cart = createExampleCart()
  for (const value of [undefined, {}, { ...cart, id: -1 }, { ...cart, id: null }, { ...cart, version: '0' },
    { ...cart, actualizadoEn: 'no-fecha' }, { ...cart, moneda: 'USD' }, { ...cart, restauranteId: null },
    { ...cart, total: 1 }, { ...cart, items: [...cart.items, cart.items[0]] },
    ...[{ nombre: '' }, { cantidad: 0 }, { cantidad: 100 }, { precioUnitario: -1 }, { precioUnitario: 1_000_000_001 },
      { subtotal: 1 }, { productoId: 0 }].map(change => ({ ...cart, items: [{ ...cart.items[0], ...change }] })),
    { ...createEmptyExampleCart(), restauranteId: 1 }, { ...cart, items: Array(51).fill(cart.items[0]) }]) {
    assert.throws(() => validateCartResponse(value), { code: 'INVALID_RESPONSE' })
  }
  assert.deepEqual(validateCartResponse(createEmptyExampleCart()), createEmptyExampleCart())
})

test('respuesta de escritura inválida no reemplaza el carrito ni publica éxito', async () => {
  const controller = createCartController()
  controller.connect({ read: async () => createExampleCart(), write: async () => ({ ...createExampleCart(), total: 1 }) })
  await controller.load()
  assert.equal(await controller.write(add), false)
  assert.equal(controller.getSnapshot().cart.total, 12500)
  assert.equal(controller.getSnapshot().error.code, 'INVALID_RESPONSE')
})

test('mensajes crudos, causas y códigos heredados no se exponen', async () => {
  const controller = createCartController()
  for (const failure of [new Error('TOKEN-PRIVADO'), Object.assign(new CartServiceError('CONFLICT'), { message: 'TOKEN-PRIVADO', cause: 'CAUSA-PRIVADA' }),
    Object.assign(new CartServiceError('CONFLICT'), { code: '__proto__' })]) {
    controller.connect({ read: async () => { throw failure } })
    await controller.load()
    const error = controller.getSnapshot().error
    assert.doesNotMatch(error.message, /PRIVAD|function|Object/)
    assert.equal(error.cause, undefined)
  }
})

test('respuesta de cuenta anterior se ignora aunque el adaptador no respete abort', async () => {
  const controller = createCartController()
  const result = deferred()
  let signal
  controller.connect({ read: options => { signal = options.signal; return result.promise } })
  const old = controller.load()
  controller.connect({ read: async () => createEmptyExampleCart() })
  await controller.load()
  assert.ok(signal.aborted)
  result.resolve(createExampleCart())
  assert.equal(await old, false)
  assert.equal(controller.getSnapshot().status, 'empty')
})

test('salir durante escritura cancela la publicación de éxito tardía', async () => {
  const controller = createCartController()
  const result = deferred()
  controller.connect({ read: async () => createExampleCart(), write: () => result.promise })
  await controller.load()
  const write = controller.write(add)
  const states = []
  controller.subscribe(() => states.push(controller.getSnapshot().status))
  controller.cancelPending()
  result.resolve(createEmptyExampleCart())
  assert.equal(await write, false)
  assert.deepEqual(states, [])
})

test('abortar antes de escribir no modifica el adaptador ni consume el fallo de una vez', async () => {
  const adapter = createCartDemoAdapter({ scenario: 'write-error', delayMs: 0 })
  const abort = new AbortController()
  const write = adapter.write(add, { signal: abort.signal })
  abort.abort()
  await assert.rejects(write, { name: 'AbortError' })
  assert.deepEqual(await adapter.read(), createExampleCart())
  await assert.rejects(adapter.write(add), { code: 'WRITE_FAILED' })
})

test('instancias y respuestas de adaptadores no comparten líneas', async () => {
  const first = setup().adapter
  const second = setup().adapter
  await first.write(add)
  const copy = await first.read()
  copy.items[0].cantidad = 99
  assert.equal((await first.read()).items[0].cantidad, 3)
  assert.equal((await second.read()).items[0].cantidad, 2)
})

test('restricciones del catálogo se conservan como errores seguros sin cambios', async () => {
  const { controller } = setup()
  await controller.load()
  for (const [id, code] of [[2001, 'RESTAURANT'], [1003, 'UNAVAILABLE'], [999, 'PRODUCT']]) {
    assert.equal(await controller.write({ ...add, productoId: id }), false)
    assert.equal(controller.getSnapshot().error.code, code)
    assert.equal(controller.getSnapshot().cart.total, 12500)
  }
  assert.equal(CART_SCENARIOS.length, 6)
  assert.throws(() => createCartDemoAdapter({ scenario: 'real' }))
})
