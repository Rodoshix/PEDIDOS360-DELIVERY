import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createProfileController, ProfileError } from '../src/features/usuarios/profileService.js'
import { createProfileDemoAdapter, PROFILE_SCENARIOS } from '../src/features/usuarios/profileDemoAdapter.js'
import { createExampleProfile } from '../src/features/usuarios/profileDemo.js'

const draft = { nombre: ' Andrea ', apellido: ' Prueba ', email: 'ANDREA@EXAMPLE.TEST', telefono: ' ' }
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function setup(scenario = 'example') {
  const controller = createProfileController()
  const adapter = createProfileDemoAdapter({ scenario, delayMs: 0 })
  controller.connect(adapter)
  return { controller, adapter }
}

test('no consulta por sí solo ni usa un perfil de respaldo sin adaptador', async () => {
  const controller = createProfileController()
  assert.equal(controller.getSnapshot().status, 'idle')
  assert.equal(await controller.load(), false)
  assert.equal(controller.getSnapshot().error.code, 'NOT_CONFIGURED')
  assert.equal(controller.getSnapshot().profile, null)
})

test('consulta pasa por loading y publica un perfil inmutable de campos conocidos', async () => {
  const controller = createProfileController()
  const result = deferred()
  let calls = 0
  controller.connect({ read: () => { calls++; return result.promise } })
  const states = []
  const unsubscribe = controller.subscribe(() => states.push(controller.getSnapshot().status))
  const operation = controller.load()
  assert.equal(controller.getSnapshot().status, 'loading')
  assert.equal(await controller.load(), false)
  result.resolve({ ...createExampleProfile(), accessToken: 'no-exponer', roles: ['ADMIN'] })
  assert.equal(await operation, true)
  assert.equal(calls, 1)
  assert.deepEqual(states, ['loading', 'ready'])
  assert.equal(controller.getSnapshot().profile.accessToken, undefined)
  assert.equal(controller.getSnapshot().profile.roles, undefined)
  assert.ok(Object.isFrozen(controller.getSnapshot().profile))
  unsubscribe()
  controller.connect(null)
  assert.deepEqual(states, ['loading', 'ready'])
})

test('ausencia explícita devuelve empty, mientras que una excepción no representa ausencia', async () => {
  const { controller } = setup('empty')
  assert.equal(await controller.load(), true)
  assert.equal(controller.getSnapshot().status, 'empty')
  controller.connect({ read: async () => { throw new Error('SQL-TOKEN-PRIVADO') } })
  assert.equal(await controller.load(), false)
  assert.equal(controller.getSnapshot().status, 'error')
  assert.doesNotMatch(controller.getSnapshot().error.message, /SQL-TOKEN/)
  assert.equal(await controller.save(draft), false)
})

test('consulta que falla una vez permite reintento explícito, no automático', async () => {
  const { controller } = setup('load-error')
  assert.equal(await controller.load(), false)
  assert.equal(controller.getSnapshot().error.code, 'LOAD_FAILED')
  assert.equal(await controller.load(), true)
  assert.equal(controller.getSnapshot().profile.nombre, 'Alex')
})

test('creación y edición envían solo el payload permitido y normalizado', async () => {
  const controller = createProfileController()
  const payloads = []
  controller.connect({ read: async () => null, save: async payload => {
    payloads.push(payload)
    return { ...createExampleProfile(), ...payload }
  } })
  await controller.load()
  assert.equal(await controller.save({ ...draft, id: 999, activo: false, roles: ['ADMIN'] }), true)
  assert.deepEqual(payloads[0], { nombre: 'Andrea', apellido: 'Prueba', email: 'andrea@example.test', telefono: null })
  assert.equal(await controller.save({ ...draft, telefono: '+56 9 0000 0000' }), true)
  assert.equal(controller.getSnapshot().profile.telefono, '+56 9 0000 0000')
})

test('doble envío durante saving realiza una única escritura', async () => {
  const controller = createProfileController()
  const result = deferred()
  let writes = 0
  controller.connect({ read: async () => createExampleProfile(), save: () => { writes++; return result.promise } })
  await controller.load()
  const first = controller.save(draft)
  assert.equal(controller.getSnapshot().status, 'saving')
  assert.equal(await controller.save(draft), false)
  assert.equal(await controller.load(), false)
  result.resolve({ ...createExampleProfile(), nombre: 'Andrea' })
  assert.equal(await first, true)
  assert.equal(writes, 1)
})

for (const scenario of ['save-error', 'conflict']) {
  test(`${scenario}: conserva el perfil anterior y permite reintentar el mismo borrador`, async () => {
    const { controller, adapter } = setup(scenario)
    await controller.load()
    assert.equal(await controller.save(draft), false)
    assert.equal(controller.getSnapshot().profile.nombre, 'Alex')
    assert.equal((await adapter.read()).nombre, 'Alex')
    assert.equal(await controller.save(draft), true)
    assert.equal(controller.getSnapshot().profile.nombre, 'Andrea')
    assert.equal(controller.getSnapshot().error, null)
  })
}

test('datos inválidos no llegan al adaptador y cancelar limpia el error, no el perfil', async () => {
  const controller = createProfileController()
  let writes = 0
  controller.connect({ read: async () => createExampleProfile(), save: async () => { writes++ } })
  await controller.load()
  assert.equal(await controller.save({ ...draft, email: '' }), false)
  assert.equal(writes, 0)
  controller.clearError()
  assert.equal(controller.getSnapshot().status, 'ready')
  assert.equal(controller.getSnapshot().profile.nombre, 'Alex')
})

test('rechaza respuestas incompletas o inválidas sin reemplazar el perfil previo', async () => {
  for (const response of [undefined, {}, { ...createExampleProfile(), id: -1 }, { ...createExampleProfile(), activo: 'true' },
    { ...createExampleProfile(), email: '' }, { ...createExampleProfile(), telefono: 123 }, { ...createExampleProfile(), actualizadoEn: 'no-fecha' }]) {
    const controller = createProfileController()
    controller.connect({ read: async () => createExampleProfile(), save: async () => response })
    await controller.load()
    assert.equal(await controller.save(draft), false)
    assert.equal(controller.getSnapshot().error.code, 'INVALID_RESPONSE')
    assert.equal(controller.getSnapshot().profile.nombre, 'Alex')
  }
})

test('errores conocidos manipulados también se sustituyen por mensajes seguros', async () => {
  const controller = createProfileController()
  const error = new ProfileError('FORBIDDEN')
  error.message = 'TOKEN-PRIVADO'
  error.cause = new Error('DATOS-PRIVADOS')
  controller.connect({ read: async () => { throw error } })
  await controller.load()
  assert.equal(controller.getSnapshot().error.code, 'FORBIDDEN')
  assert.doesNotMatch(controller.getSnapshot().error.message, /PRIVADO/)
  assert.equal(controller.getSnapshot().error.cause, undefined)
})

test('una respuesta tardía de la cuenta anterior no reemplaza la nueva, aunque ignore abort', async () => {
  const controller = createProfileController()
  const old = deferred()
  let oldSignal
  controller.connect({ read: ({ signal }) => { oldSignal = signal; return old.promise } })
  const previous = controller.load()
  controller.connect({ read: async () => ({ ...createExampleProfile(), nombre: 'Cuenta B' }) })
  await controller.load()
  assert.ok(oldSignal.aborted)
  old.resolve({ ...createExampleProfile(), nombre: 'Cuenta A' })
  assert.equal(await previous, false)
  assert.equal(controller.getSnapshot().profile.nombre, 'Cuenta B')
})

test('al abandonar la pantalla, el guardado tardío no publica éxito', async () => {
  const controller = createProfileController()
  const result = deferred()
  controller.connect({ read: async () => createExampleProfile(), save: () => result.promise })
  await controller.load()
  const operation = controller.save(draft)
  const states = []
  controller.subscribe(() => states.push(controller.getSnapshot().status))
  controller.cancelPending()
  result.resolve({ ...createExampleProfile(), nombre: 'Tardío' })
  assert.equal(await operation, false)
  assert.deepEqual(states, [])
})

test('cancelar el adaptador durante la espera no escribe ni consume un fallo simulado', async () => {
  const adapter = createProfileDemoAdapter({ scenario: 'save-error', delayMs: 0 })
  const abort = new AbortController()
  const operation = adapter.save(draft, { signal: abort.signal })
  abort.abort()
  await assert.rejects(operation, { name: 'AbortError' })
  assert.equal((await adapter.read()).nombre, 'Alex')
  await assert.rejects(adapter.save(draft), { code: 'SAVE_FAILED' })
})

test('instancias del adaptador no comparten perfiles y sus respuestas son copias', async () => {
  const first = createProfileDemoAdapter({ delayMs: 0 })
  const second = createProfileDemoAdapter({ delayMs: 0 })
  await first.save(draft)
  const copy = await first.read()
  copy.nombre = 'Mutado fuera'
  assert.equal((await first.read()).nombre, 'Andrea')
  assert.equal((await second.read()).nombre, 'Alex')
})

test('acceso denegado no se convierte en perfil vacío e inactivo no permite guardar', async () => {
  const forbidden = setup('forbidden').controller
  assert.equal(await forbidden.load(), false)
  assert.equal(forbidden.getSnapshot().error.code, 'FORBIDDEN')
  const { controller, adapter } = setup('inactive')
  await controller.load()
  assert.equal(controller.getSnapshot().profile.activo, false)
  assert.equal(await controller.save(draft), false)
  assert.equal((await adapter.read()).nombre, 'Alex')
})

test('solo admite los escenarios de prueba declarados', () => {
  assert.equal(PROFILE_SCENARIOS.length, 7)
  assert.throws(() => createProfileDemoAdapter({ scenario: 'produccion' }))
})

test('limpiar un error de consulta no inventa ausencia ni habilita guardar', async () => {
  const { controller } = setup('forbidden')
  await controller.load()
  const failure = controller.getSnapshot()
  controller.clearError()
  assert.equal(controller.getSnapshot(), failure)
  assert.equal(await controller.save(draft), false)
})

test('códigos desconocidos o heredados no se usan como mensajes de error', () => {
  for (const code of ['toString', 'constructor', '__proto__', 'TOKEN-PRIVADO', undefined]) {
    const error = new ProfileError(code)
    assert.equal(error.code, 'LOAD_FAILED')
    assert.equal(error.message, new ProfileError('LOAD_FAILED').message)
  }
})
