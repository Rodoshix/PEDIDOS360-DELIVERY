import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createEmptyExampleCart, createExampleCart } from '../src/features/carrito/cartDemo.js'
import { formatClp } from '../src/features/carrito/cartMoney.js'

test('carrito de ejemplo respeta los campos del DTO y sus totales enteros', () => {
  const cart = createExampleCart()
  assert.deepEqual(Object.keys(cart).sort(), ['id', 'restauranteId', 'moneda', 'total', 'version', 'actualizadoEn', 'items'].sort())
  assert.equal(cart.moneda, 'CLP')
  assert.equal(cart.total, cart.items.reduce((sum, item) => sum + item.subtotal, 0))
  for (const item of cart.items) {
    assert.equal(item.subtotal, item.precioUnitario * item.cantidad)
    assert.ok(item.cantidad >= 1 && item.cantidad <= 99)
    assert.ok(Number.isSafeInteger(item.subtotal))
  }
})

test('ejemplos y líneas son nuevos por llamada, sin compartir datos entre cuentas', () => {
  const first = createExampleCart()
  first.items[0].cantidad = 90
  first.items.push({ nombre: 'No compartir' })
  const second = createExampleCart()
  assert.equal(second.items.length, 2)
  assert.equal(second.items[0].cantidad, 2)
  assert.notEqual(createEmptyExampleCart().items, createEmptyExampleCart().items)
})

test('el ejemplo vacío coincide con CarritoResponse.vacio', () => {
  assert.deepEqual(createEmptyExampleCart(), { id: null, restauranteId: null, moneda: 'CLP', total: 0,
    version: null, actualizadoEn: null, items: [] })
})

test('CLP no redondea decimales inválidos ni acepta montos negativos o inseguros', () => {
  assert.equal(formatClp(12500), '$12.500')
  assert.equal(formatClp(0), '$0')
  for (const amount of [1.5, -1, NaN, Infinity, '500', null, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => formatClp(amount), RangeError)
  }
})
