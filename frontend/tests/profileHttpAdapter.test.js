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

test('rechaza guardar antes de consultar sin hacer escrituras HTTP', async () => {
  const adapter = createProfileHttpAdapter({ get: async () => ({ status: 200, data: createExampleProfile() }) })
  await assert.rejects(adapter.save({}), { code: 'NOT_CONFIGURED' })
})

const draft = { nombre: ' Ana ', apellido: ' Prueba ', email: 'ANA@EXAMPLE.TEST', telefono: ' ' }

test('404 seguido de crear usa POST 201 y luego edita por ID del servidor, nunca del draft', async () => {
  const calls = []
  const profile = { ...createExampleProfile(), id: 72 }
  const adapter = createProfileHttpAdapter({
    get: async () => { throw new ApiAccessError('API_ERROR', 404) },
    post: async (...args) => { calls.push(['POST', ...args]); return { status: 201, data: profile } },
    put: async (...args) => { calls.push(['PUT', ...args]); return { status: 200, data: profile } },
  })
  assert.equal(await adapter.read(), null)
  await adapter.save({ ...draft, id: 999, roles: ['ADMIN'], tenantId: 'ajeno' })
  await adapter.save({ ...draft, id: 999 })
  assert.deepEqual(calls.map(([method, path]) => [method, path]), [['POST', '/usuarios'], ['PUT', '/usuarios/72']])
  for (const [, , payload, options] of calls) {
    assert.deepEqual(Object.keys(payload), ['nombre', 'apellido', 'email', 'telefono'])
    assert.equal(payload.nombre, 'Ana')
    assert.equal(payload.email, 'ana@example.test')
    assert.equal(payload.telefono, null)
    assert.deepEqual(Object.keys(options), ['signal'])
  }
})

test('perfil existente actualiza con PUT 200 y conserva el perfil proyectado', async () => {
  const profile = createExampleProfile()
  const c = createProfileController()
  c.connect(createProfileHttpAdapter({
    get: async () => ({ status: 200, data: profile }),
    put: async (path, payload) => {
      assert.equal(path, `/usuarios/${profile.id}`)
      return { status: 200, data: { ...profile, ...payload } }
    },
  }))
  await c.load()
  assert.equal(await c.save(draft), true)
  assert.equal(c.getSnapshot().profile.nombre, 'Ana')
})

for (const [status, code] of [[400, 'INVALID_INPUT'], [401, 'UNAUTHORIZED'], [403, 'FORBIDDEN'], [404, 'NOT_FOUND'], [409, 'CONFLICT'], [502, 'SAVE_FAILED'], [504, 'SAVE_FAILED']]) {
  test(`guardado HTTP ${status} conserva perfil anterior y no repite la escritura`, async () => {
    let writes = 0
    const profile = createExampleProfile()
    const c = createProfileController()
    c.connect(createProfileHttpAdapter({
      get: async () => ({ status: 200, data: profile }),
      put: async () => { writes++; throw new ApiAccessError('API_ERROR', status) },
    }))
    await c.load()
    const previous = c.getSnapshot().profile
    assert.equal(await c.save(draft), false)
    assert.equal(writes, 1)
    assert.equal(c.getSnapshot().profile, previous)
    assert.equal(c.getSnapshot().error.code, code)
  })
}

test('doble clic inmediato produce una escritura y cancelar descarta resultado tardío', async () => {
  let resolve, signal, writes = 0
  const c = createProfileController()
  c.connect(createProfileHttpAdapter({
    get: async () => { throw new ApiAccessError('API_ERROR', 404) },
    post: (_path, _payload, options) => { writes++; signal = options.signal; return new Promise(done => { resolve = done }) },
  }))
  await c.load()
  const pending = c.save(draft)
  assert.equal(await c.save(draft), false)
  assert.equal(writes, 1)
  c.connect(null)
  assert.equal(signal.aborted, true)
  resolve({ status: 201, data: createExampleProfile() })
  assert.equal(await pending, false)
  assert.equal(c.getSnapshot().profile, null)
})

test('perfil inactivo o draft inválido no realiza escritura', async () => {
  let writes = 0
  const adapter = createProfileHttpAdapter({
    get: async () => ({ status: 200, data: { ...createExampleProfile(), activo: false } }),
    put: async () => { writes++ },
  })
  await adapter.read()
  await assert.rejects(adapter.save(draft), { code: 'FORBIDDEN' })
  assert.equal(writes, 0)
  const empty = createProfileHttpAdapter({ get: async () => { throw new ApiAccessError('API_ERROR', 404) }, post: async () => { writes++ } })
  await empty.read()
  await assert.rejects(empty.save({}), { code: 'INVALID_INPUT' })
  assert.equal(writes, 0)
})

test('respuesta PUT con otro ID y POST sin 201 no se aceptan como éxito', async () => {
  const adapter = createProfileHttpAdapter({
    get: async () => ({ status: 200, data: createExampleProfile() }),
    put: async () => ({ status: 200, data: { ...createExampleProfile(), id: 9999 } }),
  })
  await adapter.read()
  await assert.rejects(adapter.save(draft), { code: 'INVALID_RESPONSE' })
  const empty = createProfileHttpAdapter({
    get: async () => { throw new ApiAccessError('API_ERROR', 404) },
    post: async () => ({ status: 200, data: createExampleProfile() }),
  })
  await empty.read()
  await assert.rejects(empty.save(draft), { code: 'INVALID_RESPONSE' })
})
