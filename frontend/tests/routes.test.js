import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { createServer } from 'vite'

let server
let AppRouter
let AuthSessionContext

before(async () => {
  // Transformar JSX con la configuración real, sin abrir un puerto ni conectar a Azure.
  server = await createServer({
    cacheDir: 'node_modules/.vite-route-tests',
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, hmr: false, watch: null },
    appType: 'custom',
  })
  AppRouter = (await server.ssrLoadModule('/src/routes/AppRouter.jsx')).default
  AuthSessionContext = (await server.ssrLoadModule('/src/auth/useAuthSession.js')).AuthSessionContext
})

after(async () => { await server?.close() })

function renderRoute(path, overrides = {}) {
  const session = { account: null, busy: false, pending: null, error: null,
    login: () => { throw new Error('No debe iniciar sesión durante el render') },
    logout: () => {}, ...overrides }
  return renderToStaticMarkup(createElement(AuthSessionContext.Provider, { value: session },
    createElement(MemoryRouter, { initialEntries: [path] }, createElement(AppRouter))))
}

test('Inicio y 404 siguen siendo públicos', () => {
  assert.match(renderRoute('/'), /Tu pedido, simple/)
  assert.match(renderRoute('/no-existe'), /No encontramos esta página/)
})

test('enlace directo privado sin sesión muestra acceso, nunca el contenido privado', () => {
  const html = renderRoute('/mi-cuenta?tab=datos#contacto')
  assert.match(html, /Inicia sesión para continuar/)
  assert.match(html, /Entrar para continuar/)
  assert.doesNotMatch(html, /Ya puedes acceder a esta página privada/)
})

test('mientras MSAL está ocupado no monta la vista privada ni ofrece otro login', () => {
  const html = renderRoute('/mi-cuenta', { busy: true, account: { name: 'Cuenta de prueba' } })
  assert.match(html, /Comprobando tu sesión/)
  assert.doesNotMatch(html, /Ya puedes acceder a esta página privada|Entrar para continuar/)
})

test('con sesión la ruta monta su contenido y salir vuelve a bloquearlo', () => {
  assert.match(renderRoute('/mi-cuenta', { account: { name: 'Cuenta de prueba' } }), /Ya puedes acceder a esta página privada/)
  assert.match(renderRoute('/mi-cuenta'), /Inicia sesión para continuar/)
})

test('una cancelación presenta el error sin ocultar la opción de reintentar', () => {
  const html = renderRoute('/mi-cuenta', { error: 'Se canceló el inicio de sesión.' })
  assert.match(html, /role="alert"/)
  assert.match(html, /Se canceló el inicio de sesión/)
  assert.match(html, /Entrar para continuar/)
})
