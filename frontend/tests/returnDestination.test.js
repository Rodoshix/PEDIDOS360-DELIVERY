import assert from 'node:assert/strict'
import test from 'node:test'
import { createReturnDestinationStore, safeReturnDestination, restoreReturnDestination } from '../src/auth/returnDestination.js'
import { createSessionActions, restoreSession } from '../src/auth/session.js'

test('conserva ruta interna, consulta y fragmento', () => {
  assert.equal(safeReturnDestination('/mi-cuenta?tab=datos#contacto'), '/mi-cuenta?tab=datos#contacto')
  assert.equal(safeReturnDestination('/pedidos/123'), '/pedidos/123')
})

test('rechaza destinos externos, ambiguos o malformados', () => {
  const invalid = [undefined, null, {}, '', 'mi-cuenta', 'https://evil.test', '//evil.test',
    '/\\evil.test', '/%5cevil.test', '/%2fevil.test', '/%252fevil.test',
    '/x/..//evil.test', '/x/%2e%2e//evil.test', '/\nevil.test', '/%0devil.test',
    'javascript:alert(1)', '/%ZZ', '/%252525252fevil.test', '/' + 'x'.repeat(2048)]
  for (const value of invalid) assert.equal(safeReturnDestination(value), '/', String(value))
})

test('no conserva parámetros de respuestas de autenticación', () => {
  for (const destination of ['/?code=prueba', '/#access_token=prueba', '/?state=prueba', '/#error=access_denied']) {
    assert.equal(safeReturnDestination(destination), '/')
  }
})

function memoryStore(options = {}) {
  const data = new Map()
  const storage = { setItem: (key, value) => data.set(key, value), getItem: key => data.get(key) ?? null, removeItem: key => data.delete(key) }
  return createReturnDestinationStore(storage, () => 'opaque-test-id', options.now)
}

test('login envía solo una clave opaca a MSAL; el destino queda en la pestaña', async () => {
  const requests = []
  const returnDestinationStore = memoryStore()
  const actions = createSessionActions({ loginRedirect: async request => requests.push(request) }, {
    onPending: () => {}, onError: () => {}, returnDestinationStore,
  })
  await actions.login('/mi-cuenta?tab=datos#contacto')
  assert.equal(requests[0].state, 'opaque-test-id')
  assert.equal(returnDestinationStore.consume(requests[0].state), '/mi-cuenta?tab=datos#contacto')
  await actions.login('//evil.test')
  assert.equal(returnDestinationStore.consume(requests[1].state), '/')
})

function responseInstance(response, failure) {
  return {
    handleRedirectPromise: async () => { if (failure) throw failure; return response },
    setActiveAccount: () => {}, getAllAccounts: () => [], getActiveAccount: () => null,
  }
}

test('solo una respuesta válida del directorio devuelve un destino de retorno', async () => {
  const store = memoryStore()
  const id = store.save('/mi-cuenta?tab=datos#contacto')
  const valid = responseInstance({ account: { tenantId: 'tenant' }, state: id })
  assert.deepEqual(await restoreSession(valid, 'tenant', store), { error: null, returnTo: '/mi-cuenta?tab=datos#contacto' })
  const unsafe = responseInstance({ account: { tenantId: 'tenant' }, state: '//evil.test' })
  assert.equal((await restoreSession(unsafe, 'tenant')).returnTo, '/')
  const foreign = responseInstance({ account: { tenantId: 'otro' }, state: '/mi-cuenta' })
  assert.equal((await restoreSession(foreign, 'tenant')).returnTo, undefined)
})

test('el destino se consume una vez y rechaza claves incorrectas o vencidas', () => {
  let time = 1000
  const store = memoryStore({ now: () => time })
  const id = store.save('/mi-cuenta')
  assert.equal(store.consume('otra-clave'), '/')
  assert.equal(store.consume(id), '/')
  store.save('/mi-cuenta')
  assert.equal(store.consume(id), '/mi-cuenta')
  assert.equal(store.consume(id), '/')
  store.save('/mi-cuenta')
  time += 16 * 60 * 1000
  assert.equal(store.consume(id), '/')
})

test('un almacenamiento bloqueado no inicia una redirección sin guardar el destino', async () => {
  let calls = 0
  let error
  const store = createReturnDestinationStore({ setItem: () => { throw new Error('blocked') }, removeItem: () => {} }, () => 'id')
  const actions = createSessionActions({ loginRedirect: async () => { calls += 1 } }, {
    onPending: () => {}, onError: value => { error = value }, returnDestinationStore: store,
  })
  await actions.login('/mi-cuenta')
  assert.equal(calls, 0)
  assert.ok(error)
})

test('recarga normal, logout y cancelación no restauran destinos anteriores', async () => {
  const store = memoryStore()
  const id = store.save('/mi-cuenta')
  assert.equal((await restoreSession(responseInstance(null), 'tenant', store)).returnTo, undefined)
  assert.equal(store.consume(id), '/')
  store.save('/mi-cuenta')
  const cancelled = responseInstance(null, { errorCode: 'access_denied' })
  const result = await restoreSession(cancelled, 'tenant', store)
  assert.equal(store.consume(id), '/')
  assert.equal(result.returnTo, undefined)
  assert.match(result.error, /canceló/)
})

test('logout limpia cualquier destino guardado antes de salir', async () => {
  const store = memoryStore()
  const id = store.save('/mi-cuenta')
  const actions = createSessionActions({ logoutRedirect: async () => assert.equal(store.consume(id), '/') }, {
    onPending: () => {}, onError: () => {}, returnDestinationStore: store,
  })
  await actions.logout({ tenantId: 'tenant' })
})

test('el retorno reemplaza el historial antes de montar rutas; sin respuesta no navega', () => {
  const calls = []
  const history = { replaceState: (...args) => calls.push(args) }
  restoreReturnDestination(history, undefined)
  restoreReturnDestination(history, null)
  assert.deepEqual(calls, [])
  restoreReturnDestination(history, '/mi-cuenta?tab=datos#contacto')
  restoreReturnDestination(history, '//evil.test')
  assert.deepEqual(calls, [[null, '', '/mi-cuenta?tab=datos#contacto'], [null, '', '/']])
})
