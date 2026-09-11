import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createExampleProfile } from '../src/features/usuarios/profileDemo.js'

test('cada ejemplo de perfil es independiente y solo contiene campos de UsuarioResponse', () => {
  const first = createExampleProfile()
  first.nombre = 'Cambio de prueba'
  const second = createExampleProfile()
  assert.equal(second.nombre, 'Alex')
  assert.equal(second.email, 'alex@example.test')
  assert.equal(second.telefono, null)
  assert.deepEqual(Object.keys(second).sort(), [
    'id', 'nombre', 'apellido', 'email', 'telefono', 'activo', 'creadoEn', 'actualizadoEn',
  ].sort())
})
