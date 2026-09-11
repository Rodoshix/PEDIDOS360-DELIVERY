import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

// Banco visual separado del arranque de la aplicación: solo loopback y datos ficticios.
// No entra en el build de index.html, no instancia MSAL ni carga un perfil real.
const server = await createServer({
  root: fileURLToPath(new URL('../', import.meta.url)),
  server: { host: '127.0.0.1', port: 0, strictPort: false },
  plugins: [{
    name: 'profile-preview-only',
    configureServer(vite) {
      vite.middlewares.use(async (req, res, next) => {
        if (req.url !== '/') return next()
        try {
          const html = await vite.transformIndexHtml('/', `<!doctype html>
            <html lang="es"><head><meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>Mi cuenta y Carrito — banco de pruebas</title></head><body>
            <div id="root" data-preview-route="${process.argv.includes('--cart') ? '/carrito?tab=productos#resumen' : '/mi-cuenta?tab=datos#contacto'}"></div><script type="module" src="/tools/fixtures/ProfilePreview.jsx"></script>
            </body></html>`)
          res.setHeader('Content-Type', 'text/html; charset=utf-8')
          res.end(html)
        } catch (error) { next(error) }
      })
    },
  }],
})
await server.listen()
server.printUrls()
console.log('Banco visual de prueba, sin Microsoft ni backend. Ctrl+C para detener.')
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, async () => { await server.close(); process.exit(0) })
}
