import test from 'node:test'
import assert from 'node:assert/strict'
import { observation, observations } from '../src/features/carrito/kafkaObservations.js'
test('unknown never implies availability', () => {
 assert.deepEqual(observation({ id: '42', conocido: false }, 'cart'), { id: '42', conocido: false })
})
test('strict informative contracts and timestamps', () => {
 const value = { id: '42', conocido: true, valor: 'false', version: '2', observadoEn: '2026-11-09T15:00:00Z' }
 assert.equal(observations({ informativo: true, items: [value] }, 'cart')[0].valor, 'false')
 for (const invalid of [{ ...value, valor: false }, { ...value, version: '0' }, { ...value, observadoEn: 'invalid' }]) assert.throws(() => observation(invalid, 'cart'))
 assert.throws(() => observations({ items: [value] }, 'cart'))
})
