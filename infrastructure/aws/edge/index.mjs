import fs from 'node:fs'
import http from 'node:http'
import https from 'node:https'

const maxRequest = 1024 * 1024
const maxResponse = 4 * 1024 * 1024
const methods = new Set(['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'])
const responseHeaders = new Set(['content-type', 'etag', 'last-modified', 'content-security-policy', 'x-frame-options', 'referrer-policy'])
const problem = statusCode => ({ statusCode, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' }, body: JSON.stringify({ error: statusCode === 401 ? 'No autorizado' : 'Solicitud no disponible' }) })

function send(options, body) {
  return new Promise((resolve, reject) => {
    const client = options.protocol === 'https:' ? https : http
    const request = client.request({ ...options, agent: false, signal: AbortSignal.timeout(20000) }, response => {
      let size = 0
      const chunks = []
      response.on('data', chunk => {
        size += chunk.length
        if (size > maxResponse) response.destroy(new Error('Response limit'))
        else chunks.push(chunk)
      })
      response.on('error', reject)
      response.on('end', () => resolve({ statusCode: response.statusCode, headers: response.headers, body: Buffer.concat(chunks) }))
    })
    request.on('error', reject)
    request.end(body)
  })
}

export function createHandler({ host, apiId, origin, ca, transport = send }) {
  if (!/^172\.31\.(?:\d{1,3})\.(?:\d{1,3})$/.test(host) || host.split('.').some(n => Number(n) > 255)) throw new Error('Private EC2 address required')
  if (!/^[a-z0-9]+$/.test(apiId) || origin !== `https://${apiId}.execute-api.us-east-1.amazonaws.com`) throw new Error('Expected Academy API origin')
  if (!ca?.includes('-----BEGIN CERTIFICATE-----')) throw new Error('BFF certificate required')
  return async event => {
    try {
      if (event?.version !== '2.0' || event.requestContext?.apiId !== apiId) return problem(403)
      const method = event.requestContext.http?.method
      const rawPath = event.rawPath
      if (!methods.has(method) || typeof rawPath !== 'string' || rawPath.length > 2048 || !/^\/[A-Za-z0-9_./-]*$/.test(rawPath) || rawPath.includes('//') || rawPath.split('/').some(p => p.startsWith('.'))) return problem(400)
      const isApi = rawPath === '/api' || rawPath.startsWith('/api/')
      const headers = {}
      for (const [name, value] of Object.entries(event.headers ?? {})) {
        const key = name.toLowerCase()
        if (Object.hasOwn(headers, key) || typeof value !== 'string' || /[\r\n\0]/.test(value)) return problem(400)
        headers[key] = value
      }
      if (isApi && headers.origin && headers.origin !== origin) return problem(403)
      let upstreamPath = rawPath
      const forwarded = { accept: headers.accept || '*/*', 'accept-encoding': 'identity' }
      if (isApi) {
        // Also enforced by API Gateway JWT authorizer; BFF revalidates the token.
        if (!event.requestContext.authorizer?.jwt?.claims || !/^Bearer [^\s,]+$/i.test(headers.authorization ?? '')) return problem(401)
        if (!/^\/api\/(usuarios|restaurantes|productos|carrito|pedidos|pagos)(?:\/[A-Za-z0-9_-]+)*\/?$/.test(rawPath)) return problem(404)
        upstreamPath = rawPath.slice(4)
        for (const name of ['authorization', 'content-type', 'idempotency-key', 'origin']) if (headers[name]) forwarded[name] = headers[name]
        const query = event.rawQueryString ?? ''
        if (typeof query !== 'string' || query.length > 4096 || /[^\x21-\x7e]|#/.test(query)) return problem(400)
        if (query) upstreamPath += '?' + query
      } else {
        if (!['GET', 'HEAD'].includes(method)) return problem(405)
        if (/^\/(internal|actuator|run|healthz)(\/|$)/.test(rawPath)) return problem(404)
        // Static frontend only: no Authorization, cookies, identity or URL query.
        if (headers['if-none-match']) forwarded['if-none-match'] = headers['if-none-match']
      }
      let body = Buffer.alloc(0)
      if (event.body != null) {
        if (typeof event.body !== 'string' || event.body.length > 2 * maxRequest) return problem(413)
        if (event.isBase64Encoded && !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(event.body)) return problem(400)
        body = Buffer.from(event.body, event.isBase64Encoded ? 'base64' : 'utf8')
      }
      if (body.length > maxRequest) return problem(413)
      if (body.length && ['GET', 'HEAD'].includes(method)) return problem(400)
      if (body.length) forwarded['content-length'] = String(body.length)
      const result = await transport({
        protocol: isApi ? 'https:' : 'http:', hostname: host, port: isApi ? 8443 : 8080,
        method, path: upstreamPath, headers: forwarded,
        ...(isApi ? { ca, servername: 'bff', rejectUnauthorized: true } : {}),
      }, body)
      // Never follow upstream redirects or expose internal server errors.
      if (!Number.isInteger(result.statusCode) || result.statusCode < 200 || result.statusCode >= 500 || (result.statusCode >= 300 && result.statusCode < 400 && result.statusCode !== 304)) return problem(502)
      if (!Buffer.isBuffer(result.body) || result.body.length > maxResponse) return problem(502)
      const outgoing = { 'cache-control': isApi ? 'no-store' : 'no-cache', 'x-content-type-options': 'nosniff' }
      for (const [name, value] of Object.entries(result.headers ?? {})) {
        if (responseHeaders.has(name) && typeof value === 'string' && !/[\r\n\0]/.test(value)) outgoing[name] = value
      }
      return { statusCode: result.statusCode, headers: outgoing, isBase64Encoded: true, body: ['HEAD'].includes(method) || [204, 304].includes(result.statusCode) ? '' : result.body.toString('base64') }
    } catch { return problem(502) }
  }
}

let liveHandler
export async function handler(event) {
  // No request bodies, credentials, headers or upstream exceptions are logged.
  try {
    liveHandler ??= createHandler({ host: process.env.EC2_PRIVATE_IP, apiId: process.env.API_ID, origin: process.env.PUBLIC_ORIGIN, ca: fs.readFileSync(new URL('./bff.crt', import.meta.url), 'utf8') })
    return await liveHandler(event)
  } catch { return problem(503) }
}
