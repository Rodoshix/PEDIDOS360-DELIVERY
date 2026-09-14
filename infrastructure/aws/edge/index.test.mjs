import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHandler } from './index.mjs'
const origin = 'https://fixture.execute-api.us-east-1.amazonaws.com'
const config = { host: '172.31.1.2', apiId: 'fixture', origin, ca: '-----BEGIN CERTIFICATE-----fixture' }
const event = (path = '/api/pedidos', method = 'GET') => ({ version: '2.0', rawPath: path, rawQueryString: '', headers: { authorization: 'Bearer fixture', origin }, requestContext: { apiId: 'fixture', http: { method }, authorizer: { jwt: { claims: { sub: 'fixture' } } } } })

test('API forwards only allowed headers, pins BFF TLS and never changes idempotency key', async () => {
  let seen
  const handler = createHandler({ ...config, transport: async (...args) => { seen = args; return { statusCode: 201, headers: { 'content-type': 'application/json', 'set-cookie': 'private', server: 'internal' }, body: Buffer.from('{}') } } })
  const input = event('/api/pagos', 'POST')
  input.body = '{"pedidoId":1}'
  Object.assign(input.headers, { 'idempotency-key': 'attempt-1', cookie: 'private', 'x-user-id': '1', 'x-roles': 'ADMIN', host: 'evil.invalid' })
  const response = await handler(input)
  assert.equal(response.statusCode, 201)
  assert.equal(seen[0].path, '/pagos')
  assert.equal(seen[0].servername, 'bff')
  assert.equal(seen[0].rejectUnauthorized, true)
  assert.equal(seen[0].headers['idempotency-key'], 'attempt-1')
  assert.equal(seen[0].headers.cookie, undefined)
  assert.equal(seen[0].headers['x-user-id'], undefined)
  assert.equal(response.headers['set-cookie'], undefined)
  assert.equal(response.headers['cache-control'], 'no-store')
})

test('static requests strip tokens, cookies and redirect query; binary data survives', async () => {
  let seen
  const bytes = Buffer.from([0, 255, 128])
  const handler = createHandler({ ...config, transport: async options => { seen = options; return { statusCode: 200, headers: { 'content-type': 'image/png' }, body: bytes } } })
  const input = event('/assets/icon.png')
  input.rawQueryString = 'code=sensitive'
  input.headers.cookie = 'private'
  const response = await handler(input)
  assert.equal(seen.protocol, 'http:')
  assert.equal(seen.path, '/assets/icon.png')
  assert.equal(seen.headers.authorization, undefined)
  assert.equal(seen.headers.cookie, undefined)
  assert.deepEqual(Buffer.from(response.body, 'base64'), bytes)
})

test('invalid requests are rejected without upstream calls', async () => {
  let calls = 0
  const handler = createHandler({ ...config, transport: async () => { calls++; throw new Error() } })
  for (const [input, expected] of [
    [event('/api/internal/pedidos'), 404], [event('/internal/pedidos'), 404],
    [event('/api/pedidos/../usuarios'), 400], [event('/api/pedidos%2f1'), 400],
    [event('/.env'), 400], [event('//evil.invalid'), 400], [event('/', 'POST'), 405],
    [{ ...event(), headers: {} }, 401],
    [{ ...event(), headers: { authorization: 'Bearer fixture', origin: 'https://evil.invalid' } }, 403],
    [{ ...event(), requestContext: { apiId: 'other', http: { method: 'GET' } } }, 403],
    [{ ...event(), requestContext: { apiId: 'fixture', http: { method: 'GET' } } }, 401],
    [{ ...event('/api/pagos', 'POST'), body: 'a'.repeat(1024 * 1024 + 1) }, 413],
    [{ ...event('/api/pagos', 'POST'), body: '%%%bad', isBase64Encoded: true }, 400],
    [{ ...event(), rawQueryString: 'q=a\r\nHost:x' }, 400],
  ]) assert.equal((await handler(input)).statusCode, expected)
  assert.equal(calls, 0)
})

test('redirects, errors and oversized responses are hidden; no retries', async () => {
  for (const statusCode of [302, 500, 503]) {
    let calls = 0
    const handler = createHandler({ ...config, transport: async () => { calls++; return { statusCode, headers: {}, body: Buffer.from('private-details') } } })
    const response = await handler(event())
    assert.equal(response.statusCode, 502)
    assert.ok(!response.body.includes('private-details'))
    assert.equal(calls, 1)
  }
  const handler = createHandler({ ...config, transport: async () => { throw new Error('private-details') } })
  assert.equal((await handler(event())).statusCode, 502)
  const oversized = createHandler({ ...config, transport: async () => ({ statusCode: 200, headers: {}, body: Buffer.alloc(4 * 1024 * 1024 + 1) }) })
  assert.equal((await oversized(event())).statusCode, 502)
  assert.throws(() => createHandler({ ...config, host: '1.2.3.4' }))
})
