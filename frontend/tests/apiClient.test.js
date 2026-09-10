import assert from 'node:assert/strict'
import { before, after, test } from 'node:test'
import { createServer } from 'node:http'
import axios from 'axios'
import { createApiClient } from '../src/services/createApiClient.js'
import { ApiAccessError } from '../src/auth/ApiAccessError.js'

let server
let baseURL
const received = []

before(async () => {
  server = createServer((request, response) => {
    received.push({ url: request.url, authorization: request.headers.authorization })
    if (request.url === '/api/redirect') {
      response.writeHead(302, { Location: '/outside' }); response.end(); return
    }
    const status = request.url === '/api/unauthorized' ? 401 : request.url === '/api/forbidden' ? 403 : 200
    response.writeHead(status, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ ok: status === 200, detail: status === 200 ? 'test' : 'FAKE_PRIVATE_CONTENT' }))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  baseURL = `http://127.0.0.1:${server.address().port}/api`
})

after(async () => { await new Promise(resolve => server.close(resolve)) })

function client(options = {}) {
  return createApiClient({ baseURL, origin: 'http://localhost:5173', getAccessToken: async () => 'FAKE_TEST_ACCESS', ...options })
}

test('envía Bearer a la API y no expone credenciales en la respuesta Axios', async () => {
  const response = await client().get('/users?limit=1')
  assert.equal(received.at(-1).url, '/api/users?limit=1')
  assert.equal(received.at(-1).authorization, 'Bearer FAKE_TEST_ACCESS')
  assert.equal(response.status, 200)
  assert.equal(response.config, undefined)
  assert.equal(response.request, undefined)
})

test('bloquea destinos ajenos y prefijos parecidos antes de adquirir el token', async () => {
  let acquired = 0
  const api = client({ getAccessToken: async () => { acquired += 1; return 'FAKE_TEST_ACCESS' } })
  for (const destination of ['https://evil.test/api/users', baseURL.replace('/api', '/api-extra'),
    baseURL + '/../outside', baseURL + '/%252e%252e/outside', baseURL + '/%2foutside',
    baseURL + '/%5coutside', baseURL + '/users#fragment', 'javascript:alert(1)']) {
    await assert.rejects(api.get(destination), { code: 'API_DESTINATION_BLOCKED' }, destination)
  }
  await assert.rejects(api.get('/users', { baseURL: 'https://evil.test' }), { code: 'API_DESTINATION_BLOCKED' })
  assert.equal(acquired, 0)
})

test('peticiones públicas explícitas no adquieren ni envían Authorization', async () => {
  const api = client({ getAccessToken: async () => { throw new Error('No debe llamarse') } })
  await api.get('/public', { authRequired: false, headers: { Authorization: 'SHOULD_NOT_BE_SENT' }, auth: { username: 'test', password: 'test' } })
  assert.equal(received.at(-1).authorization, undefined)
})

test('401 y 403 no reintentan, no redirigen y no propagan configuración o cuerpo del error', async () => {
  for (const [path, status, code] of [['/unauthorized', 401, 'API_UNAUTHORIZED'], ['/forbidden', 403, 'API_FORBIDDEN']]) {
    const previous = received.length
    await assert.rejects(client().get(path), error => {
      assert.equal(error.code, code)
      assert.equal(error.status, status)
      assert.equal(error.config, undefined)
      assert.equal(error.response, undefined)
      assert.doesNotMatch(JSON.stringify(error) + error.message, /FAKE_TEST_ACCESS|FAKE_PRIVATE_CONTENT/)
      return true
    })
    assert.equal(received.length, previous + 1)
  }
})

test('no sigue redirecciones HTTP ni siquiera dentro del mismo origen', async () => {
  const previous = received.length
  await assert.rejects(client().get('/redirect', { fetchOptions: { redirect: 'follow' } }), { code: 'API_NETWORK' })
  assert.equal(received.length, previous + 1)
  assert.equal(received.at(-1).url, '/api/redirect')
})

test('un fallo de token detiene la petición antes de llegar a la red', async () => {
  const previous = received.length
  const api = client({ getAccessToken: async () => { throw new ApiAccessError('INTERACTION_REQUIRED') } })
  await assert.rejects(api.get('/users'), { code: 'INTERACTION_REQUIRED' })
  assert.equal(received.length, previous)
})

test('respeta cancelación antes y después de esperar el token', async () => {
  const previous = received.length
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(client().get('/users', { signal: controller.signal }), axios.isCancel)
  const later = new AbortController()
  const api = client({ getAccessToken: async () => { later.abort(); return 'FAKE_TEST_ACCESS' } })
  await assert.rejects(api.get('/users', { signal: later.signal }), axios.isCancel)
  assert.equal(received.length, previous)
})

test('revisa el destino después de transformRequest e impide sobrescribir el adaptador', async () => {
  const previous = received.length
  await assert.rejects(client().get('/users', { transformRequest: [function (data) {
    this.url = 'https://evil.test/users'; return data
  }] }), { code: 'API_DESTINATION_BLOCKED' })
  assert.equal(received.length, previous)
  await client().get('/users', { adapter: () => { throw new Error('No usar este adaptador') } })
})

test('rechaza configuración insegura y admite prefijo relativo del mismo origen', async () => {
  for (const invalid of ['http://example.test/api', 'https://user:pass@example.test/api', 'https://example.test/api?query=1']) {
    assert.throws(() => client({ baseURL: invalid }), { code: 'API_CONFIG_INVALID' })
  }
  const api = client({ baseURL: '/api', origin: new URL(baseURL).origin })
  await api.get('/users')
  assert.equal(received.at(-1).url, '/api/users')
})

test('fallos de red y timeout producen errores controlados sin credenciales', async () => {
  for (const [transportCode, expected] of [['ECONNABORTED', 'API_TIMEOUT'], ['ERR_NETWORK', 'API_NETWORK']]) {
    const api = client({ transport: async config => {
      throw new axios.AxiosError('FAKE_PRIVATE_CONTENT', transportCode, config)
    } })
    await assert.rejects(api.get('/users'), error => {
      assert.equal(error.code, expected)
      assert.equal(error.config, undefined)
      assert.doesNotMatch(JSON.stringify(error) + error.message, /FAKE_TEST_ACCESS|FAKE_PRIVATE_CONTENT/)
      return true
    })
  }
})
