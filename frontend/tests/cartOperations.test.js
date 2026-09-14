import assert from 'node:assert/strict'
import { test } from 'node:test'
import { addCartProduct, changeCartQuantity, removeCartProduct, clearCart, parseQuantity, CartValidationError } from '../src/features/carrito/cartOperations.js'
import { createExampleCart, createEmptyExampleCart } from '../src/features/carrito/cartDemo.js'
import { CART_CATALOG } from '../src/features/carrito/cartCatalogDemo.js'

const burger = CART_CATALOG[0]
const pizza = CART_CATALOG[3]
function frozenCart() {
  const cart = createExampleCart()
  cart.items.forEach(Object.freeze)
  Object.freeze(cart.items)
  return Object.freeze(cart)
}

test('cantidad admite límites enteros pero rechaza vacíos, decimales, exponentes y tipos ajenos', () => {
  assert.equal(parseQuantity(' 1 '), 1)
  assert.equal(parseQuantity(99), 99)
  for (const value of ['', ' ', '1.2', '1e1', '-1', '100', 'NaN', NaN, Infinity, null, undefined, true, {}, [], 0, 100, 1.5]) {
    assert.throws(() => parseQuantity(value), CartValidationError)
  }
})

test('no crea un carrito implícito cuando aún no se ha elegido un ejemplo', () => {
  for (const action of [() => addCartProduct(null, burger, 1), () => changeCartQuantity(null, 1, 1), () => removeCartProduct(null, 1), () => clearCart(null)]) {
    assert.throws(action, /Elige primero/)
  }
})

test('agregar al vacío fija restaurante y copia solo campos conocidos', () => {
  const before = createEmptyExampleCart()
  const after = addCartProduct(before, { ...burger, roles: ['ADMIN'], subtotal: 1 }, '2')
  assert.equal(after.restauranteId, 101)
  assert.equal(after.total, 11000)
  assert.deepEqual(after.items[0], { productoId: 1001, nombre: burger.nombre, precioUnitario: 5500, cantidad: 2, subtotal: 11000 })
  assert.deepEqual(before, createEmptyExampleCart())
})

test('agregar un repetido suma y actualiza nombre/precio sin duplicar ni mutar', () => {
  const before = frozenCart()
  const after = addCartProduct(before, { ...burger, precioUnitario: 6000, nombre: ' Nueva hamburguesa ' }, 1)
  assert.equal(after.items.length, 2)
  assert.equal(after.items[0].cantidad, 3)
  assert.equal(after.items[0].nombre, 'Nueva hamburguesa')
  assert.equal(after.items[0].subtotal, 18000)
  assert.equal(after.total, 19500)
  assert.equal(before.total, 12500)
  assert.notEqual(after.items[1], before.items[1])
})

test('sumar sobre 99 rechaza toda la operación sin actualizar precio ni cantidades', () => {
  const before = frozenCart()
  assert.throws(() => addCartProduct(before, { ...burger, precioUnitario: 1 }, 98), /entre 1 y 99/)
  assert.equal(before.items[0].precioUnitario, 5500)
  assert.equal(before.items[0].cantidad, 2)
})

test('otro restaurante e indisponible no vacían ni modifican el carrito', () => {
  const before = frozenCart()
  assert.throws(() => addCartProduct(before, pizza, 1), /mismo restaurante/)
  assert.throws(() => addCartProduct(before, CART_CATALOG[2], 1), /no está disponible/)
  assert.equal(before.total, 12500)
})

test('valida IDs, nombre y precio del producto antes de modificar', () => {
  for (const product of [null, { ...burger, productoId: -1 }, { ...burger, restauranteId: 0 },
    { ...burger, nombre: ' ' }, { ...burger, nombre: 'x'.repeat(201) }, { ...burger, precioUnitario: -1 },
    { ...burger, precioUnitario: 1.5 }, { ...burger, precioUnitario: 1_000_000_001 }, { ...burger, precioUnitario: '500' }]) {
    assert.throws(() => addCartProduct(frozenCart(), product, 1), /no son válidos/)
  }
})

test('hasta 50 productos diferentes; permite sumar un existente pero rechaza el 51', () => {
  let cart = createEmptyExampleCart()
  for (let id = 1; id <= 50; id++) cart = addCartProduct(cart, { ...burger, productoId: id }, 1)
  assert.equal(cart.items.length, 50)
  assert.throws(() => addCartProduct(cart, { ...burger, productoId: 51 }, 1), /50 productos/)
  const next = addCartProduct(cart, { ...burger, productoId: 1 }, 1)
  assert.equal(next.items.length, 50)
  assert.equal(next.items[0].cantidad, 2)
})

test('totales máximos CLP permanecen enteros exactos y precio cero está permitido', () => {
  let cart = createEmptyExampleCart()
  for (let id = 1; id <= 50; id++) cart = addCartProduct(cart, { ...burger, productoId: id, precioUnitario: 1_000_000_000 }, 99)
  assert.equal(cart.total, 4_950_000_000_000)
  assert.ok(Number.isSafeInteger(cart.total))
  assert.equal(addCartProduct(createEmptyExampleCart(), { ...burger, precioUnitario: 0 }, 1).total, 0)
})

test('cambiar cantidad conserva el precio y normaliza subtotales sin mutar', () => {
  const before = frozenCart()
  const after = changeCartQuantity(before, 1001, 3)
  assert.equal(after.items[0].precioUnitario, 5500)
  assert.equal(after.total, 18000)
  assert.equal(after.version, before.version + 1)
  assert.equal(before.total, 12500)
  assert.equal(changeCartQuantity(before, 1001, 2), before)
})

test('cambiar o eliminar una línea inexistente falla sin mutar', () => {
  const before = frozenCart()
  assert.throws(() => changeCartQuantity(before, 999, 1), /no está/)
  assert.throws(() => removeCartProduct(before, 999), /no está/)
  assert.equal(before.total, 12500)
})

test('eliminar la última línea libera el restaurante sin eliminar ID del carrito', () => {
  const before = frozenCart()
  const one = removeCartProduct(before, 1001)
  assert.equal(one.total, 1500)
  assert.equal(one.restauranteId, 101)
  const empty = removeCartProduct(one, 1002)
  assert.equal(empty.id, before.id)
  assert.equal(empty.restauranteId, null)
  assert.equal(empty.total, 0)
  assert.equal(addCartProduct(empty, pizza, 1).restauranteId, 102)
})

test('vaciar conserva identidad del carrito, libera restaurante y no afecta original', () => {
  const before = frozenCart()
  const after = clearCart(before)
  assert.equal(after.id, before.id)
  assert.equal(after.restauranteId, null)
  assert.deepEqual(after.items, [])
  assert.equal(after.total, 0)
  assert.equal(clearCart(after), after)
  assert.equal(before.items.length, 2)
})
