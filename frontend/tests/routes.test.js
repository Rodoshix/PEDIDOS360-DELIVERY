import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { createServer } from 'vite'

let server
let AppRouter
let AuthSessionContext
let ProfilePanel
let ProfileDetails

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
  ProfilePanel = (await server.ssrLoadModule('/src/features/usuarios/ProfilePanel.jsx')).default
  ProfileDetails = (await server.ssrLoadModule('/src/features/usuarios/ProfileDetails.jsx')).default
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
  assert.doesNotMatch(html, /Perfil de Pedidos360|Ver perfil de ejemplo|Comprobar permiso de API/)
})

test('mientras MSAL está ocupado no monta la vista privada ni ofrece otro login', () => {
  const html = renderRoute('/mi-cuenta', { busy: true, account: { name: 'Cuenta de prueba' } })
  assert.match(html, /Comprobando tu sesión/)
  assert.doesNotMatch(html, /Perfil de Pedidos360|Ver perfil de ejemplo|Entrar para continuar/)
})

test('con sesión la ruta monta su contenido y salir vuelve a bloquearlo', () => {
  assert.match(renderRoute('/mi-cuenta', { account: { name: 'Cuenta de prueba' } }), /Perfil de Pedidos360/)
  assert.match(renderRoute('/mi-cuenta'), /Inicia sesión para continuar/)
})

test('una cancelación presenta el error sin ocultar la opción de reintentar', () => {
  const html = renderRoute('/mi-cuenta', { error: 'Se canceló el inicio de sesión.' })
  assert.match(html, /role="alert"/)
  assert.match(html, /Se canceló el inicio de sesión/)
  assert.match(html, /Entrar para continuar/)
})

test('separa la identidad Microsoft del perfil, sin deducir datos ni cargar el ejemplo', () => {
  const html = renderRoute('/mi-cuenta', { account: { name: 'Cuenta Microsoft', username: 'sesion@example.test',
    idToken: 'TOKEN-NO-VISIBLE', localAccountId: 'OBJECT-NO-VISIBLE', tenantId: 'TENANT-NO-VISIBLE' } })
  assert.match(html, /Cuenta Microsoft/)
  assert.match(html, /sesion@example.test/)
  assert.match(html, /Perfil aún no consultado/)
  assert.match(html, /No sabemos si ya tienes un perfil registrado/)
  assert.doesNotMatch(html, /alex@example.test|TOKEN-NO-VISIBLE|OBJECT-NO-VISIBLE|TENANT-NO-VISIBLE/)
  assert.match(html, /Diagnóstico de acceso a la API/)
  assert.match(html, /Comprobar permiso de API/)
})

test('sin modo demo no ofrece ejemplos ni afirma que falta un perfil en Usuarios', () => {
  const html = renderToStaticMarkup(createElement(ProfilePanel))
  assert.match(html, /Integración pendiente/)
  assert.doesNotMatch(html, /<button|alex@example.test|No tienes perfil/)
})

test('el modo demo empieza vacío y exige una acción explícita', () => {
  const html = renderToStaticMarkup(createElement(ProfilePanel, { demoEnabled: true }))
  assert.match(html, /Ver perfil de ejemplo/)
  assert.doesNotMatch(html, /alex@example.test|Quitar ejemplo/)
})

test('presenta el perfil de solo lectura, teléfono opcional y estado inactivo', () => {
  const html = renderToStaticMarkup(createElement(ProfileDetails, { profile: {
    id: 'ID-NO-VISIBLE', nombre: 'Alex', apellido: 'Ejemplo', email: 'alex@example.test', telefono: null,
    activo: false, roles: ['ROLE-NO-VISIBLE'], tenantId: 'TENANT-NO-VISIBLE',
  } }))
  for (const label of ['Nombre', 'Apellido', 'Email de contacto', 'Teléfono', 'Sin registrar', 'Inactivo']) {
    assert.ok(html.includes(label), label)
  }
  assert.doesNotMatch(html, /ID-NO-VISIBLE|ROLE-NO-VISIBLE|TENANT-NO-VISIBLE|<input/)
})

test('el texto del perfil se escapa sin interpretarlo como HTML', () => {
  const html = renderToStaticMarkup(createElement(ProfileDetails, { profile: {
    nombre: '<script>alert(1)</script>', apellido: 'Ejemplo', email: 'alex@example.test',
    telefono: '+56 9 0000 0000', activo: true,
  } }))
  assert.doesNotMatch(html, /<script>/)
  assert.match(html, /&lt;script&gt;/)
  assert.match(html, /Activo/)
  assert.doesNotMatch(html, /Sin registrar/)
})
