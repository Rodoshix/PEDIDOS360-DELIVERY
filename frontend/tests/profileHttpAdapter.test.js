import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiAccessError } from '../src/auth/ApiAccessError.js'
import { createProfileHttpAdapter } from '../src/features/usuarios/profileHttpAdapter.js'
import { createProfileController } from '../src/features/usuarios/profileService.js'
import { createExampleProfile } from '../src/features/usuarios/profileDemo.js'

function setup(get) {
  const controller = createProfileController()
  controller.connect(createProfileHttpAdapter({ get }))
  return controller
}

test('perfil HTTP consulta /usuarios/me con señal y proyecta solamente datos de perfil', async () => {
  let calls = 0
  const c = setup(async (path, options) => {
    calls++
    assert.equal(path, '/usuarios/me')
    assert.deepEqual(Object.keys(options), ['signal'])
    assert.ok(options.signal instanceof AbortSignal)
    return { status: 200, data: { ...createExampleProfile(), token: 'no-exponer', roles: ['ADMIN'] } }
  })
  assert.equal(await c.load(), true)
  assert.equal(calls, 1)
  assert.equal(c.getSnapshot().status, 'ready')
  assert.equal(c.getSnapshot().profile.token, undefined)
  assert.equal(c.getSnapshot().profile.roles, undefined)
})

test('solo un 404 HTTP controlado representa ausencia', async () => {
  const c = setup(async () => { throw new ApiAccessError('API_ERROR', 404) })
  assert.equal(await c.load(), true)
  assert.equal(c.getSnapshot().status, 'empty')
  assert.equal(c.getSnapshot().profile, null)
})

for (const [code, status, expected] of [
  ['API_UNAUTHORIZED', 401, 'UNAUTHORIZED'], ['API_FORBIDDEN', 403, 'FORBIDDEN'],
  ['API_ERROR', 502, 'LOAD_FAILED'], ['API_NETWORK', undefined, 'LOAD_FAILED'],
  ['INTERACTION_REQUIRED', undefined, 'INTERACTION_REQUIRED'],
  ['SESSION_CHANGED', undefined, 'UNAUTHORIZED'],
]) {
  test(`perfil HTTP no transforma ${code} en perfil ausente ni habilita escritura`, async () => {
    const c = setup(async () => { throw new ApiAccessError(code, status) })
    assert.equal(await c.load(), false)
    assert.equal(c.getSnapshot().status, 'error')
    assert.equal(c.getSnapshot().error.code, expected)
    assert.equal(await c.save({}), false)
  })
}

test('respuestas vacías o inválidas no habilitan creación', async () => {
  for (const response of [{ status: 200, data: null }, { status: 204 }, { status: 200, data: [] }, { status: 200, data: {} }]) {
    const c = setup(async () => response)
    assert.equal(await c.load(), false)
    assert.equal(c.getSnapshot().error.code, 'INVALID_RESPONSE')
  }
})

test('al cambiar cuenta se aborta la consulta y se ignora una respuesta tardía', async () => {
  let resolve, signal
  const c = setup((_path, options) => { signal = options.signal; return new Promise(done => { resolve = done }) })
  const old = c.load()
  c.connect(createProfileHttpAdapter({ get: async () => { throw new ApiAccessError('API_ERROR', 404) } }))
  assert.equal(signal.aborted, true)
  await c.load()
  resolve({ status: 200, data: createExampleProfile() })
  assert.equal(await old, false)
  assert.equal(c.getSnapshot().status, 'empty')
  assert.equal(c.getSnapshot().profile, null)
})

test('errores desconocidos no filtran contenido y no se reintentan automáticamente', async () => {
  let calls = 0
  const c = setup(async () => { calls++; throw Object.assign(new Error('secreto privado'), { status: 404 }) })
  assert.equal(await c.load(), false)
  assert.equal(calls, 1)
  assert.equal(c.getSnapshot().error.code, 'LOAD_FAILED')
  assert.doesNotMatch(c.getSnapshot().error.message, /secreto/)
})

test('bloque 1 rechaza guardar sin hacer escrituras HTTP', async () => {
  const adapter = createProfileHttpAdapter({ get: async () => ({ status: 200, data: createExampleProfile() }) })
  await assert.rejects(adapter.save({}), { code: 'NOT_CONFIGURED' })
})
