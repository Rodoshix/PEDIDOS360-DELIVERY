import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createHandler, admissionFromEnvironment } from './index.mjs'

const tenantId = '11111111-1111-1111-1111-111111111111'
const objectId = '22222222-2222-2222-2222-222222222222'
const token = 'synthetic-academic-token'
const origin = 'https://fixture.execute-api.us-east-1.amazonaws.com'
const start = Date.parse('2026-10-11T12:00:00Z')
const options = { host: '172.31.1.2', apiId: 'fixture', origin, ca: '-----BEGIN CERTIFICATE-----fixture' }
const config = () => ({ enabled: 'true', tenantId, objectId, sourceIp: '192.0.2.1',
  tokenSha256: createHash('sha256').update(token).digest('hex'), expiresAt: '2026-10-11T12:20:00Z',
  routes: JSON.stringify(['GET /api/usuarios/me', 'GET /api/carrito', 'POST /api/pedidos', 'GET /api/pedidos/8', 'DELETE /api/carrito']) })
const event = (path = '/api/carrito', method = 'GET') => ({ version: '2.0', rawPath: path, rawQueryString: '',
  headers: { authorization: `Bearer ${token}`, origin }, requestContext: { apiId: 'fixture',
    http: { method, sourceIp: '192.0.2.1' }, authorizer: { jwt: { claims: { tid: tenantId, oid: objectId, roles: ['CLIENTE'], scp: 'access_as_user' } } } } })
const setup = (admission = config(), clock = () => start, upstreamStatus = 200) => {
  const calls = []
  const handler = createHandler({ ...options, admission, now: clock, transport: async (...args) => {
    calls.push(args); return { statusCode: upstreamStatus, headers: {}, body: Buffer.from('{}') }
  } })
  return { handler, calls }
}

test('disabled admission preserves API and static behavior, including unrelated existing API routes', async () => {
  const { handler, calls } = setup({ enabled: 'false' })
  const input = event('/api/pagos', 'POST')
  input.requestContext.authorizer.jwt.claims = { sub: 'existing' }
  assert.equal((await handler(input)).statusCode, 200)
  assert.equal((await handler(event('/mis-pedidos'))).statusCode, 200)
  assert.equal(calls.length, 2)
  assert.deepEqual(admissionFromEnvironment({}), { enabled: undefined, tenantId: undefined,
    objectId: undefined, sourceIp: undefined, tokenSha256: undefined, expiresAt: undefined, routes: undefined })
})

test('enabled admission allows only exact methods and paths and keeps BFF TLS and bearer unchanged', async () => {
  const { handler, calls } = setup()
  for (const route of JSON.parse(config().routes)) {
    const [method, path] = route.split(' ')
    assert.equal((await handler(event(path, method))).statusCode, 200)
  }
  for (const [request] of calls) {
    assert.equal(request.headers.authorization, `Bearer ${token}`)
    assert.equal(request.servername, 'bff')
    assert.equal(request.rejectUnauthorized, true)
  }
  const denied = [event('/api/pagos', 'POST'), event('/api/pedidos/9'), event('/api/carrito', 'POST'),
    event('/api/carrito/'), event('/api/usuarios'), event('/api/restaurantes/admin/acceso'),
    event('/api/carrito', 'OPTIONS'), event('/api/carrito', 'HEAD')]
  const before = calls.length
  for (const input of denied) assert.equal((await handler(input)).statusCode, 403)
  assert.equal(calls.length, before)
  const cfg = config()
  cfg.routes = JSON.stringify(['GET /api/productos/restaurante/1', 'GET /api/productos/restaurante/1/disponibles', 'GET /api/pedidos/me'])
  const catalog = setup(cfg)
  for (const route of JSON.parse(cfg.routes)) assert.equal((await catalog.handler(event(route.slice(4)))).statusCode, 200)
  assert.equal((await catalog.handler(event('/api/productos/restaurante/2'))).statusCode, 403)
})

test('untrusted identity headers do not substitute validated authorizer, tenant, owner, role or scope', async () => {
  const { handler, calls } = setup()
  const mutations = [
    input => { delete input.requestContext.authorizer },
    input => { delete input.headers.authorization },
    input => { input.requestContext.authorizer.jwt.claims.oid = tenantId },
    input => { input.requestContext.authorizer.jwt.claims.tid = objectId },
    input => { input.requestContext.authorizer.jwt.claims.roles = ['ADMIN'] },
    input => { input.requestContext.authorizer.jwt.claims.roles = ['CLIENTE', 'ADMIN'] },
    input => { input.requestContext.authorizer.jwt.claims.roles = 'CLIENTE' },
    input => { input.requestContext.authorizer.jwt.claims.scp = 'another_scope' },
  ]
  for (const mutate of mutations) {
    const input = event()
    Object.assign(input.headers, { 'x-user-id': objectId, 'x-tenant-id': tenantId, 'x-roles': 'CLIENTE' })
    mutate(input)
    assert.ok([401, 403].includes((await handler(input)).statusCode))
  }
  assert.equal(calls.length, 0)
})

test('another bearer for the same validated identity is denied without exporting credential or fingerprint', async () => {
  const { handler, calls } = setup()
  const input = event()
  input.headers.authorization = 'Bearer other-synthetic-session'
  input.headers['x-token-sha256'] = config().tokenSha256
  const response = await handler(input)
  assert.equal(response.statusCode, 403)
  assert.ok(!JSON.stringify(response).includes(token))
  assert.ok(!JSON.stringify(response).includes(config().tokenSha256))
  assert.equal(calls.length, 0)
})

test('only AWS sourceIp is used; spoofed forwarding cannot authorize a different IP', async () => {
  const { handler, calls } = setup()
  const input = event()
  input.requestContext.http.sourceIp = '192.0.2.2'
  input.headers['x-forwarded-for'] = config().sourceIp
  input.headers['x-real-ip'] = config().sourceIp
  assert.equal((await handler(input)).statusCode, 403)
  delete input.requestContext.http.sourceIp
  assert.equal((await handler(input)).statusCode, 403)
  const valid = event()
  valid.headers['x-forwarded-for'] = '198.51.100.9'
  assert.equal((await handler(valid)).statusCode, 200)
  assert.equal(calls.length, 1)
  assert.equal(calls[0][0].headers['x-forwarded-for'], undefined)
})

test('Gateway serialized roles and exact IPv6 context work without expanding roles', async () => {
  const cfg = config(); cfg.sourceIp = '2001:db8::1'
  const { handler } = setup(cfg)
  const input = event()
  input.requestContext.http.sourceIp = cfg.sourceIp
  input.requestContext.authorizer.jwt.claims.roles = '["CLIENTE"]'
  assert.equal((await handler(input)).statusCode, 200)
})

test('alternative paths, queries, duplicate header names and wrong API context never reach upstream', async () => {
  const { handler, calls } = setup()
  const inputs = [event('/api'), event('/api/api/carrito'), event('/api/carrito/../pedidos'),
    event('/API/carrito'), event('/carrito'), event('/api%2fcarrito'), event('//api/carrito'),
    event('/assets/../api/carrito'), event('/assets/api/carrito.js'), event('/api/carrito.json')]
  const query = event(); query.rawQueryString = 'route=/api/pedidos'; inputs.push(query)
  const duplicate = event(); duplicate.headers.Authorization = duplicate.headers.authorization; inputs.push(duplicate)
  const context = event(); context.requestContext.apiId = 'other'; inputs.push(context)
  for (const input of inputs) assert.ok((await handler(input)).statusCode >= 400)
  assert.equal(calls.length, 0)
})

test('only explicit public static resources load; static never forwards credentials or query', async () => {
  const { handler, calls } = setup()
  for (const path of ['/', '/index.html', '/assets/index-fixture.js', '/assets/index-fixture.css']) {
    const input = event(path); delete input.requestContext.authorizer
    input.rawQueryString = 'code=synthetic'
    assert.equal((await handler(input)).statusCode, 200)
  }
  for (const [request] of calls) {
    assert.equal(request.protocol, 'http:')
    assert.equal(request.headers.authorization, undefined)
    assert.ok(!request.path.includes('?'))
  }
  assert.equal((await handler(event('/mis-pedidos'))).statusCode, 403)
})

test('missing, invalid, excessive and disabled leftover configuration fails closed at creation', () => {
  const invalid = [{ enabled: 'true' }, { enabled: 'TRUE' }, { enabled: false },
    { ...config(), enabled: 'false' }, { ...config(), enabled: undefined },
    { ...config(), expiresAt: '2026-10-11T13:00:00Z' }, { ...config(), expiresAt: '2026-10-11T11:59:59Z' },
    { ...config(), expiresAt: '2026-02-30T12:20:00Z' }, { ...config(), sourceIp: '192.0.2.0/24' },
    { ...config(), tokenSha256: 'bad' }, { ...config(), tenantId: '*' },
    { ...config(), routes: '["POST /api/pagos"]' }, { ...config(), routes: '["GET /api/*"]' },
    { ...config(), routes: '[]' }, { ...config(), routes: 'bad-json' },
    { ...config(), routes: '["GET /api/carrito","GET /api/carrito"]' }]
  for (const key of ['tenantId', 'objectId', 'sourceIp', 'tokenSha256', 'expiresAt', 'routes']) {
    const cfg = config(); delete cfg[key]; invalid.push(cfg)
  }
  for (const cfg of invalid) assert.throws(() => setup(cfg))
  assert.throws(() => setup({ enabled: 'false', enable: 'true' }))
  assert.throws(() => admissionFromEnvironment({ EP2_ADMISSION_ENABELD: 'true' }))
})

test('expiry, clock faults and upstream failures cannot open admission or retry', async () => {
  let time = start
  const { handler, calls } = setup(config(), () => time)
  time = Date.parse(config().expiresAt)
  assert.equal((await handler(event())).statusCode, 503)
  assert.equal((await handler(event('/'))).statusCode, 503)
  time = NaN
  assert.equal((await handler(event())).statusCode, 503)
  time = start - 1
  assert.equal((await handler(event())).statusCode, 503)
  assert.equal(calls.length, 0)
  const failed = setup(config(), () => start, 500)
  assert.equal((await failed.handler(event('/api/pedidos', 'POST'))).statusCode, 502)
  assert.equal(failed.calls.length, 1)
})

test('gate does not translate or bypass upstream 409', async () => {
  const { handler, calls } = setup(config(), () => start, 409)
  assert.equal((await handler(event('/api/carrito', 'DELETE'))).statusCode, 409)
  assert.equal(calls.length, 1)
})

test('configuration is snapshotted, not a mutable per-request public selector', async () => {
  const cfg = config()
  const { handler } = setup(cfg)
  cfg.enabled = 'false'; cfg.routes = '["POST /api/pagos"]'; cfg.objectId = tenantId
  assert.equal((await handler(event('/api/pagos', 'POST'))).statusCode, 403)
  assert.equal((await handler(event())).statusCode, 200)
})
