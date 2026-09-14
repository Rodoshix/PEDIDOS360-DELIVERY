import assert from 'node:assert/strict'
import { test } from 'node:test'
import { PROFILE_FIELDS, createProfileDraft, hasProfileChanges, normalizeProfileDraft, validateProfileDraft } from '../src/features/usuarios/profileForm.js'

const valid = { nombre: 'Alex', apellido: 'Ejemplo', email: 'alex@example.test', telefono: null }

test('un perfil nuevo empieza vacío, sin deducir datos de identidad', () => {
  assert.deepEqual(createProfileDraft(null), { nombre: '', apellido: '', email: '', telefono: '' })
  assert.deepEqual(createProfileDraft({ name: 'Cuenta MSAL', username: 'otra@example.test', roles: ['ADMIN'] }), createProfileDraft())
})

test('el borrador es una copia editable y no altera el perfil original', () => {
  const original = Object.freeze({ ...valid, id: 12, activo: true })
  const draft = createProfileDraft(original)
  draft.nombre = 'Otro'
  assert.equal(original.nombre, 'Alex')
  assert.deepEqual(Object.keys(draft), ['nombre', 'apellido', 'email', 'telefono'])
})

test('normaliza espacios, email y teléfono vacío sin incluir campos privilegiados', () => {
  const draft = { nombre: '  Álex  ', apellido: '  De la Cruz ', email: '  ALEX@EXAMPLE.TEST  ', telefono: '   ',
    id: 10, activo: false, roles: ['ADMIN'], tenantId: 'no-enviar', actualizadoEn: 'no-enviar' }
  assert.deepEqual(normalizeProfileDraft(draft), { nombre: 'Álex', apellido: 'De la Cruz', email: 'alex@example.test', telefono: null })
  assert.equal(draft.nombre, '  Álex  ')
})

test('conserva formato y prefijo del teléfono, solo recorta extremos', () => {
  assert.equal(normalizeProfileDraft({ ...valid, telefono: '  +56 9 0000 0000  ' }).telefono, '+56 9 0000 0000')
})

test('rechaza los tres campos obligatorios vacíos, pero no exige teléfono', () => {
  assert.deepEqual(Object.keys(validateProfileDraft({ nombre: ' ', apellido: '\t', email: '\n' })), ['nombre', 'apellido', 'email'])
  assert.deepEqual(validateProfileDraft(valid), {})
})

test('comprueba el formato básico del email sin imponer un dominio de Microsoft', () => {
  for (const email of ['sin-arroba', 'a@@example.test', '@example.test', 'alex@', 'a b@example.test', 'alex@exam ple.test']) {
    assert.ok(validateProfileDraft({ ...valid, email }).email, email)
  }
  for (const email of ['alex+pedidos@example.test', 'ALEX@EXAMPLE.TEST', 'alex@localhost']) {
    assert.deepEqual(validateProfileDraft({ ...valid, email }), {}, email)
  }
})

for (const { name, maxLength } of PROFILE_FIELDS) {
  test(`respeta el límite de ${name} (${maxLength}) sobre el valor normalizado`, () => {
    const atLimit = name === 'email' ? `${'a'.repeat(maxLength - 13)}@example.test` : 'a'.repeat(maxLength)
    assert.deepEqual(validateProfileDraft({ ...valid, [name]: ` ${atLimit} ` }), {})
    assert.ok(validateProfileDraft({ ...valid, [name]: `a${atLimit}` })[name])
  })
}

test('tipos inesperados no se convierten en datos de perfil', () => {
  assert.deepEqual(createProfileDraft({ nombre: 42, apellido: {}, email: ['alex@example.test'], telefono: false }), createProfileDraft())
  assert.equal(Object.keys(validateProfileDraft(null)).length, 3)
})

test('detecta cambios pendientes y vuelve a limpio al restaurar el valor original', () => {
  const draft = createProfileDraft(valid)
  assert.equal(hasProfileChanges(draft, valid), false)
  draft.nombre = ' Alex '
  assert.equal(hasProfileChanges(draft, valid), true)
  draft.nombre = valid.nombre
  assert.equal(hasProfileChanges(draft, valid), false)
  draft.id = 'ignorado'
  assert.equal(hasProfileChanges(draft, valid), false)
  assert.equal(hasProfileChanges(createProfileDraft(), null), false)
})
