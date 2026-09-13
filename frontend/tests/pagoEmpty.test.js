import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createPagoHttpAdapter } from '../src/features/pagos/pagoHttpAdapter.js'
import { createPagoController } from '../src/features/pagos/pagoService.js'
import { PagoServiceError } from '../src/features/pagos/pagoErrors.js'

test('lista HTTP vacía es ausencia de pago, no un error', async () => {
  const adapter = createPagoHttpAdapter({ get: async () => ({ status: 200, data: [] }) })
  const controller = createPagoController()
  controller.connect(adapter)
  assert.equal(await controller.load(1), true)
  assert.equal(controller.getSnapshot().status, 'empty')
  assert.equal(controller.getSnapshot().error, null)
  assert.equal(controller.getSnapshot().pago, null)
})

test('un error NOT_FOUND real se conserva', async () => {
  const controller = createPagoController()
  controller.connect(createPagoHttpAdapter({ get: async () => { throw new PagoServiceError('NOT_FOUND') } }))
  assert.equal(await controller.load(99), false)
  assert.equal(controller.getSnapshot().error.code, 'NOT_FOUND')
})

test('una respuesta inválida no se interpreta como lista vacía', async () => {
  const controller = createPagoController()
  controller.connect(createPagoHttpAdapter({ get: async () => ({ status: 200, data: {} }) }))
  assert.equal(await controller.load(1), false)
  assert.equal(controller.getSnapshot().error.code, 'INVALID_RESPONSE')
})

test('null al registrar sigue siendo error, no ausencia de pago', async () => {
  const controller = createPagoController()
  controller.connect({ create: async () => null })
  assert.equal(await controller.registrar({ pedidoId: 1, metodo: 'TARJETA' }, { idempotencyKey: 'prueba-1' }), false)
  assert.equal(controller.getSnapshot().status, 'error')
  assert.equal(controller.getSnapshot().idempotencyKey, 'prueba-1')
})
