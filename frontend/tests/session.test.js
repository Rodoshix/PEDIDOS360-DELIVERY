import assert from 'node:assert/strict'
import test from 'node:test'
import { authErrorMessage } from '../src/auth/authErrors.js'
import { createSessionActions, restoreSession, selectSessionAccount } from '../src/auth/session.js'

const tenantId = '22222222-2222-2222-2222-222222222222'
const ana = { homeAccountId: 'home-ana', localAccountId: 'ana', tenantId, name: 'Ana', username: 'ana@example.test' }
const otro = { homeAccountId: 'home-otro', localAccountId: 'otro', tenantId }
const externo = { ...ana, tenantId: '33333333-3333-3333-3333-333333333333' }

function fakeMsal({ accounts = [], active = null, response = null, failure } = {}) {
  let current = active
  return {
    getAllAccounts: () => accounts,
    getActiveAccount: () => current,
    setActiveAccount: account => { current = account },
    handleRedirectPromise: async () => {
      if (failure) throw failure
      return response
    },
  }
}

test('sin cuenta en caché no inventa una sesión', () => {
  assert.equal(selectSessionAccount([], null, tenantId), null)
})

test('restaura la única cuenta del directorio y conserva la selección explícita entre varias', () => {
  assert.equal(selectSessionAccount([ana], null, tenantId), ana)
  assert.equal(selectSessionAccount([ana, otro], otro, tenantId), otro)
})

test('varias cuentas sin selección requieren elegir; no se usa la primera', () => {
  assert.equal(selectSessionAccount([ana, otro], null, tenantId), null)
  assert.equal(selectSessionAccount([ana, otro], { homeAccountId: 'eliminada' }, tenantId), null)
})

test('filtra cuentas de otros directorios', () => {
  assert.equal(selectSessionAccount([externo], externo, tenantId), null)
  assert.equal(selectSessionAccount([externo, otro], externo, tenantId), otro)
})

test('al volver de Microsoft establece la cuenta devuelta como activa', async () => {
  const instance = fakeMsal({ accounts: [ana, otro], active: otro, response: { account: ana } })
  assert.deepEqual(await restoreSession(instance, tenantId), { error: null })
  assert.equal(instance.getActiveAccount(), ana)
})

test('la recarga restaura una cuenta única y limpia una selección eliminada', async () => {
  const instance = fakeMsal({ accounts: [ana] })
  await restoreSession(instance, tenantId)
  assert.equal(instance.getActiveAccount(), ana)
  const empty = fakeMsal({ active: ana })
  await restoreSession(empty, tenantId)
  assert.equal(empty.getActiveAccount(), null)
})

test('rechaza la respuesta de otro directorio sin activar esa identidad', async () => {
  const instance = fakeMsal({ response: { account: externo } })
  assert.match((await restoreSession(instance, tenantId)).error, /directorio/)
  assert.equal(instance.getActiveAccount(), null)
})

test('muestra cancelación al procesar el retorno y no filtra el mensaje de Entra', async () => {
  const instance = fakeMsal({ failure: { errorCode: 'access_denied', message: 'TOKEN_PRIVADO usuario@example.test' } })
  const result = await restoreSession(instance, tenantId)
  assert.match(result.error, /canceló/)
  assert.doesNotMatch(result.error, /TOKEN_PRIVADO|usuario@example/)
})

test('login solicita selección de cuenta y permisos de identidad, sin Graph ni roles inventados', async () => {
  let request
  const states = []
  const errors = []
  const actions = createSessionActions({ loginRedirect: async value => { request = value } }, {
    onPending: value => states.push(value), onError: value => errors.push(value),
  })
  await actions.login()
  assert.deepEqual(request, { scopes: ['openid', 'profile'], prompt: 'select_account' })
  assert.deepEqual(states, ['login', null])
  assert.deepEqual(errors, [null])
})

test('doble clic no dispara dos operaciones y permite reintentar después', async () => {
  let calls = 0
  let finish
  const actions = createSessionActions({ loginRedirect: () => {
    calls += 1
    return new Promise(resolve => { finish = resolve })
  } }, { onPending: () => {}, onError: () => {} })
  const first = actions.login()
  await actions.login()
  assert.equal(calls, 1)
  finish()
  await first
  const second = actions.login()
  assert.equal(calls, 2)
  finish()
  await second
})

test('logout se dirige a la cuenta activa y no actúa sin cuenta', async () => {
  const requests = []
  const actions = createSessionActions({ logoutRedirect: async request => { requests.push(request) } }, {
    onPending: () => {}, onError: () => {},
  })
  await actions.logout(null)
  await actions.logout(ana)
  assert.deepEqual(requests, [{ account: ana }])
})

test('un fallo libera el botón y muestra error seguro sin reintento automático', async () => {
  const states = []
  const errors = []
  let calls = 0
  const actions = createSessionActions({ loginRedirect: async () => {
    calls += 1
    throw { errorCode: 'get_request_failed', message: 'TOKEN_PRIVADO' }
  } }, { onPending: value => states.push(value), onError: value => errors.push(value) })
  await actions.login()
  assert.equal(calls, 1)
  assert.deepEqual(states, ['login', null])
  assert.match(errors[1], /conexión/)
  assert.doesNotMatch(errors[1], /TOKEN_PRIVADO/)
})

test('error de logout no afirma que la sesión de Microsoft esté cerrada', async () => {
  let message
  const actions = createSessionActions({ logoutRedirect: async () => { throw new Error('TOKEN_PRIVADO') } }, {
    onPending: () => {}, onError: value => { message = value },
  })
  await actions.logout(ana)
  assert.match(message, /No se pudo completar el cierre/)
  assert.doesNotMatch(message, /TOKEN_PRIVADO/)
})

test('errores desconocidos y operación en curso producen mensajes controlados', () => {
  assert.match(authErrorMessage({ errorCode: 'interaction_in_progress' }), /en curso/)
  assert.equal(authErrorMessage(new Error('TOKEN_PRIVADO')), authErrorMessage(null))
})
