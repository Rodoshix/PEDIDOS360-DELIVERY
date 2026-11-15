import { createServer } from 'vite'

// Local test runner only; no product route, dependency, build flag or application change.
const server = await createServer({
  server: { host: '127.0.0.1', port: 5187, strictPort: true, hmr: false, ws: false },
  plugins: [{
    name: 'kafka-observations-api-fixture', enforce: 'pre',
    resolveId(id) {
      if (id.endsWith('/services/httpClient.js')) return '\0kafka-observations-test-client'
    },
    load(id) {
      if (id === '\0kafka-observations-test-client') return `
        import { createApiClient } from '/src/services/createApiClient.js';
        export default createApiClient({
          baseURL: '/api', origin: window.location.origin,
          getAccessToken: async () => 'SYNTHETIC_TEST_ACCESS',
          transport: config => window.__kafkaObservationsTransport(config),
        });`
    },
    configureServer(vite) {
      vite.middlewares.use(async (request, response, next) => {
        if (request.url !== '/__test-kafka-observations') return next()
        const html = await vite.transformIndexHtml(request.url, `<!doctype html>
          <html lang="es"><meta charset="utf-8"><title>Integración local de observaciones</title>
          <h1>Prueba local: React real y API simulada</h1><div id="fixture"></div>
          <pre id="result" role="status">RUNNING</pre>
          <script type="module" src="/tests/fixtures/kafkaObservations.integration.jsx"></script></html>`)
        response.setHeader('Content-Type', 'text/html; charset=utf-8')
        response.end(html)
      })
    },
  }],
})
await server.listen()
console.log('Local fixture: http://127.0.0.1:5187/__test-kafka-observations')
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => {
  await server.close()
  process.exit(0)
})
