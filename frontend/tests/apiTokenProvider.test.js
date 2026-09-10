import assert from 'node:assert/strict'
import test from 'node:test'
import { CacheLookupPolicy, EventType, InteractionType } from '@azure/msal-browser'
import { createApiTokenProvider } from '../src/auth/apiTokenProvider.js'
import { createSessionActions } from '../src/auth/session.js'

const account = { tenantId: 'tenant', homeAccountId: 'home', localAccountId: 'local' }
const scope = 'api://11111111-1111-1111-1111-111111111111/access_as_user'
const tokenResult = () => ({ account, accessToken: 'FAKE_TEST_ACCESS', idToken: 'FAKE_TEST_ID', tokenType: 'Bearer', scopes: [scope], expiresOn: new Date(Date.now() + 60_000) })

function setup(acquire = async () => tokenResult()) {
  let active = account
  let listener
  const requests = []
  const instance = {
    getActiveAccount: () => active,
    addEventCallback: callback => { listener = callback; return 'callback' },
    removeEventCallback: () => { listener = undefined },
    acquireTokenSilent: async request => { requests.push(request); return acquire() },
  }
  const provider = createApiTokenProvider(instance, { tenantId: 'tenant', scopes: [scope] })
  return { provider, requests, change: value => { active = value; listener?.({ eventType: EventType.ACTIVE_ACCOUNT_CHANGED }) },
    emit: (eventType, interactionType = InteractionType.Redirect) => listener?.({ eventType, interactionType }) }
}

test('solicita únicamente el ámbito de la API y omite renovación con iframe', async () => {
  const { provider, requests } = setup()
  assert.equal(await provider.getAccessToken(), 'FAKE_TEST_ACCESS')
  assert.deepEqual(requests[0], { account, scopes: [scope], cacheLookupPolicy: CacheLookupPolicy.AccessTokenAndRefreshToken })
})

test('no adquiere tokens sin cuenta activa o para otro directorio', async () => {
  const runtime = setup()
  for (const value of [null, { ...account, tenantId: 'otro' }]) {
    runtime.change(value)
    await assert.rejects(runtime.provider.getAccessToken(), { code: 'SESSION_REQUIRED' })
  }
  assert.equal(runtime.requests.length, 0)
})

test('peticiones concurrentes comparten la adquisición pero no se guarda otra caché de tokens', async () => {
  let finish
  const runtime = setup(() => new Promise(resolve => { finish = resolve }))
  const first = runtime.provider.getAccessToken()
  const second = runtime.provider.getAccessToken()
  assert.equal(runtime.requests.length, 1)
  finish(tokenResult())
  await Promise.all([first, second])
  const third = runtime.provider.getAccessToken()
  assert.equal(runtime.requests.length, 2)
  finish(tokenResult())
  await third
})

test('cambiar de cuenta o iniciar logout descarta el token pendiente', async () => {
  for (const change of ['account', 'logout']) {
    let finish
    const runtime = setup(() => new Promise(resolve => { finish = resolve }))
    const pending = runtime.provider.getAccessToken()
    if (change === 'account') runtime.change({ ...account, localAccountId: 'otra' })
    else runtime.emit(EventType.LOGOUT_START)
    finish(tokenResult())
    await assert.rejects(pending, { code: 'SESSION_CHANGED' })
  }
})

test('una interacción activa bloquea nuevas peticiones', async () => {
  const runtime = setup()
  runtime.emit(EventType.ACQUIRE_TOKEN_START)
  await assert.rejects(runtime.provider.getAccessToken(), { code: 'AUTH_BUSY' })
  assert.equal(runtime.requests.length, 0)
})

test('necesidad de consentimiento no abre Microsoft ni filtra mensajes crudos', async () => {
  const runtime = setup(async () => { throw { errorCode: 'consent_required', message: 'FAKE_PRIVATE_CONTENT' } })
  await assert.rejects(runtime.provider.getAccessToken(), error => {
    assert.equal(error.code, 'INTERACTION_REQUIRED')
    assert.doesNotMatch(JSON.stringify(error) + error.message, /FAKE_PRIVATE_CONTENT/)
    assert.equal(error.cause, undefined)
    return true
  })
  assert.equal(runtime.requests.length, 1)
})

test('rechaza ID token, token vencido, cuenta distinta y permisos ajenos', async () => {
  for (const override of [{ accessToken: '' }, { accessToken: 'FAKE_TEST_ID' }, { tokenType: 'PoP' },
    { expiresOn: new Date(0) }, { account: { ...account, localAccountId: 'otro' } }, { scopes: ['User.Read'] }]) {
    const runtime = setup(async () => ({ ...tokenResult(), ...override }))
    await assert.rejects(runtime.provider.getAccessToken(), { code: 'TOKEN_UNAVAILABLE' })
  }
})

test('errores desconocidos se simplifican y permiten reintentar manualmente', async () => {
  let fail = true
  const runtime = setup(async () => { if (fail) throw new Error('FAKE_PRIVATE_CONTENT'); return tokenResult() })
  await assert.rejects(runtime.provider.getAccessToken(), { code: 'TOKEN_UNAVAILABLE' })
  fail = false
  assert.equal(await runtime.provider.getAccessToken(), 'FAKE_TEST_ACCESS')
})

test('el botón explícito solicita el ámbito de la API y conserva una clave de retorno', async () => {
  const requests = []
  const actions = createSessionActions({ acquireTokenRedirect: async request => requests.push(request) }, {
    onPending: () => {}, onError: () => {}, apiTokenRequest: { scopes: [scope] },
    returnDestinationStore: { save: destination => { assert.equal(destination, '/mi-cuenta'); return 'opaque-id' } },
  })
  await actions.authorizeApi(account, '/mi-cuenta')
  assert.deepEqual(requests, [{ account, scopes: [scope], state: 'opaque-id' }])
})
