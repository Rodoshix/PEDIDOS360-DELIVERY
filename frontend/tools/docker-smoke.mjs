import assert from 'node:assert/strict'

// Comprobación HTTP del servidor estático; no inicia sesión ni llama al backend.
const origin = new URL(process.argv[2] || 'http://127.0.0.1:5180')
assert.ok(origin.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(origin.hostname), 'Solo loopback local')
assert.ok(!origin.username && !origin.password && origin.pathname === '/' && !origin.search && !origin.hash, 'Usa solo un origen local')
const get = path => fetch(new URL(path, origin), { redirect: 'error', signal: AbortSignal.timeout(5000) })

const health = await get('/healthz')
assert.equal(health.status, 200)
assert.equal(await health.text(), 'ok\n')
const root = await get('/')
assert.equal(root.status, 200)
assert.match(root.headers.get('content-type'), /text\/html/)
assert.equal(root.headers.get('cache-control'), 'no-store')
assert.equal(root.headers.get('x-content-type-options'), 'nosniff')
const html = await root.text()
assert.match(html, /id="root"/)
for (const path of ['/mi-cuenta', '/carrito?tab=productos', '/ruta-inexistente', '/index.html']) {
  const response = await get(path)
  assert.equal(response.status, 200, path)
  assert.equal(await response.text(), html, `Fallback SPA: ${path}`)
}
for (const path of ['/api', '/api/usuarios', '/.env.local', '/.git/config', '/assets/no-existe.js']) {
  const response = await get(path)
  assert.equal(response.status, 404, path)
  assert.notEqual(await response.text(), html, `No usar fallback SPA: ${path}`)
}
const assetPaths = [...html.matchAll(/(?:src|href)="(\/assets\/[^"?]+)"/g)].map(match => match[1])
assert.ok(assetPaths.some(path => path.endsWith('.js')) && assetPaths.some(path => path.endsWith('.css')))
for (const path of assetPaths) {
  const response = await get(path)
  assert.equal(response.status, 200, path)
  assert.match(response.headers.get('cache-control'), /max-age=31536000/)
  assert.match(response.headers.get('content-type'), path.endsWith('.js') ? /javascript/ : /text\/css/)
  const content = await response.text()
  assert.doesNotMatch(content, /Hamburguesa de ejemplo|Ver carrito de ejemplo|alex@example\.test|Guardado simulado completado/)
}
console.log('Smoke Docker correcto: health, SPA, assets, caché, rutas bloqueadas y ausencia de ejemplos en assets iniciales.')
