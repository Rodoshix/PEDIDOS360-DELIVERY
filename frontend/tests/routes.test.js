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
let ProfileForm
let ProfilePending
let CartDemoPanel
let CartSummary
let CartCatalogForm

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
  ProfileForm = (await server.ssrLoadModule('/src/features/usuarios/ProfileForm.jsx')).default
  ProfilePending = (await server.ssrLoadModule('/src/features/usuarios/ProfilePending.jsx')).default
  CartDemoPanel = (await server.ssrLoadModule('/src/features/carrito/CartDemoPanel.jsx')).default
  CartSummary = (await server.ssrLoadModule('/src/features/carrito/CartSummary.jsx')).default
  CartCatalogForm = (await server.ssrLoadModule('/src/features/carrito/CartCatalogForm.jsx')).default
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

test('el panel de producción solo informa integración pendiente, sin acciones simuladas', () => {
  const html = renderToStaticMarkup(createElement(ProfilePending))
  assert.match(html, /Perfil aún no consultado/)
  assert.match(html, /No sabemos si ya tienes un perfil registrado/)
  assert.doesNotMatch(html, /<button|<input|alex@example.test|Crear perfil|No tienes perfil/)
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

test('el formulario nuevo etiqueta cuatro campos, límites, obligatoriedad y simulación', () => {
  const html = renderToStaticMarkup(createElement(ProfileForm))
  assert.match(html, /Crear perfil de prueba/)
  assert.match(html, /Usa datos ficticios/)
  assert.match(html, /no guarda en Usuarios/)
  assert.equal((html.match(/<input /g) || []).length, 4)
  assert.equal((html.match(/<label /g) || []).length, 4)
  assert.equal((html.match(/required=""/g) || []).length, 3)
  assert.equal((html.match(/maxLength="100"/g) || []).length, 2)
  assert.match(html, /maxLength="254"/)
  assert.match(html, /maxLength="30"/)
  assert.match(html, /Aplicar al ejemplo/)
  assert.match(html, /Cancelar/)
  assert.match(html, /Sin cambios pendientes/)
})

test('la edición precarga solo los campos del perfil, sin aplicar durante el render', () => {
  const initial = Object.freeze({ nombre: 'Alex', apellido: 'Ejemplo', email: 'alex@example.test', telefono: null,
    id: 'ID-NO-EDITABLE', roles: ['ROL-NO-EDITABLE'], activo: true })
  const html = renderToStaticMarkup(createElement(ProfileForm, { initialProfile: initial,
    onApply: () => { throw new Error('No aplicar durante el render') } }))
  assert.match(html, /Editar perfil de prueba/)
  assert.match(html, /value="Alex"/)
  assert.match(html, /value="alex@example.test"/)
  assert.match(html, /disabled="">Aplicar al ejemplo/)
  assert.doesNotMatch(html, /ID-NO-EDITABLE|ROL-NO-EDITABLE/)
})

test('durante el guardado marca el formulario ocupado y bloquea campos, aplicar y cancelar', () => {
  const html = renderToStaticMarkup(createElement(ProfileForm, { saving: true }))
  assert.match(html, /aria-busy="true"/)
  assert.match(html, /<fieldset disabled=""/)
  assert.match(html, /disabled="">Guardando ejemplo…/)
  assert.match(html, /disabled="">Cancelar/)
  assert.doesNotMatch(html, /Guardado simulado completado/)
})

test('Carrito aparece en navegación sin revelar contenido privado al abrir un enlace directo', () => {
  const html = renderRoute('/carrito?tab=productos#resumen')
  assert.match(html, /href="\/carrito"/)
  assert.match(html, /Inicia sesión para continuar/)
  assert.doesNotMatch(html, /Mi carrito|Carrito aún no consultado|Ver carrito de ejemplo|Hamburguesa de ejemplo/)
})

test('Carrito espera la sesión y con cuenta no deduce que esté vacío ni inventa productos', () => {
  const account = { name: 'Prueba', username: 'sesion@example.test', localAccountId: 'ID-PRIVADO' }
  assert.doesNotMatch(renderRoute('/carrito', { account, busy: true }), /Mi carrito|Carrito aún no consultado/)
  const html = renderRoute('/carrito', { account })
  assert.match(html, /Mi carrito/)
  assert.match(html, /Carrito aún no consultado/)
  assert.doesNotMatch(html, /Carrito vacío en este ejemplo|Hamburguesa de ejemplo|ID-PRIVADO/)
})

test('el simulador de Carrito no carga ejemplos por sí solo', () => {
  const html = renderToStaticMarkup(createElement(CartDemoPanel))
  assert.match(html, /Ver carrito de ejemplo/)
  assert.match(html, /Ver ejemplo vacío/)
  assert.doesNotMatch(html, /Hamburguesa de ejemplo|Quitar ejemplo|<input/)
})

test('el resumen presenta cantidades y montos CLP sin exponer IDs ni ofrecer checkout', () => {
  const html = renderToStaticMarkup(createElement(CartSummary, { cart: {
    id: 'ID-PRIVADO', restauranteId: 'RESTAURANTE-PRIVADO', moneda: 'CLP', total: 11000,
    items: [{ productoId: 1, nombre: '<b>Ejemplo</b>', precioUnitario: 5500, cantidad: 2, subtotal: 11000 }],
  } }))
  for (const text of ['Precio unitario', 'Cantidad', 'Subtotal', 'Total de productos (CLP)', '$5.500', '$11.000', '&lt;b&gt;']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(html, /ID-PRIVADO|RESTAURANTE-PRIVADO|<b>|<button|<input/)
})

test('un ejemplo vacío se identifica como simulado y muestra cero sin deducir datos reales', () => {
  const html = renderToStaticMarkup(createElement(CartSummary, { cart: { moneda: 'CLP', items: [], total: 0 } }))
  assert.match(html, /Carrito vacío en este ejemplo/)
  assert.match(html, /No describe tu carrito real/)
  assert.match(html, /\$0/)
})

test('edición de cantidad está etiquetada y bloqueada durante confirmación', () => {
  const html = renderToStaticMarkup(createElement(CartSummary, { cart: { total: 5500, items: [
    { productoId: 1, nombre: 'Prueba', precioUnitario: 5500, cantidad: 1, subtotal: 5500 },
  ] }, disabled: true, onQuantity: () => {}, onRemove: () => {} }))
  assert.match(html, /Nueva cantidad de Prueba/)
  assert.match(html, /inputMode="numeric"/)
  assert.match(html, /<fieldset disabled=""/)
  assert.match(html, /disabled="">Aplicar cantidad/)
  assert.match(html, /disabled="">Eliminar Prueba/)
})

test('catálogo muestra productos ficticios de dos restaurantes e indisponible sin enviar al render', () => {
  const html = renderToStaticMarkup(createElement(CartCatalogForm, { disabled: true, onAdd: () => { throw new Error('No enviar durante render') } }))
  assert.match(html, /Restaurante A de prueba/)
  assert.match(html, /Restaurante B de prueba/)
  assert.match(html, /No disponible/)
  assert.match(html, /<fieldset class="cart-edit-fields" disabled=""/)
  assert.match(html, /Cantidad para agregar/)
})

test('formularios del carrito informan operación en curso sin anunciar éxito', () => {
  const catalog = renderToStaticMarkup(createElement(CartCatalogForm, { disabled: true, saving: true }))
  const summary = renderToStaticMarkup(createElement(CartSummary, { cart: { total: 5500, items: [
    { productoId: 1, nombre: 'Prueba', precioUnitario: 5500, cantidad: 1, subtotal: 5500 },
  ] }, disabled: true, saving: true, onQuantity: () => {}, onRemove: () => {} }))
  for (const html of [catalog, summary]) {
    assert.match(html, /aria-busy="true"/)
    assert.match(html, /disabled=""/)
    assert.doesNotMatch(html, /Producto agregado|Cantidad actualizada/)
  }
})

test('los campos de varias líneas y catálogo tienen IDs únicos, etiquetas y ayudas enlazadas', () => {
  const html = renderToStaticMarkup(createElement('div', null,
    createElement(CartSummary, { cart: { total: 7000, items: [
      { productoId: 1, nombre: 'Primero', precioUnitario: 5500, cantidad: 1, subtotal: 5500 },
      { productoId: 2, nombre: 'Segundo', precioUnitario: 1500, cantidad: 1, subtotal: 1500 },
    ] }, onQuantity: () => {} }), createElement(CartCatalogForm)))
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1])
  assert.equal(ids.length, new Set(ids).size)
  const labels = [...html.matchAll(/\bfor="([^"]+)"/g)].map(match => match[1])
  const controls = [...html.matchAll(/<(?:input|select)\b[^>]*\bid="([^"]+)"/g)].map(match => match[1])
  assert.equal(controls.length, 4)
  for (const id of controls) assert.ok(labels.includes(id), `Etiqueta para ${id}`)
  for (const [, references] of html.matchAll(/aria-(?:describedby|labelledby)="([^"]+)"/g)) {
    for (const id of references.split(' ')) assert.ok(ids.includes(id), `Referencia existente ${id}`)
  }
})

test('carrito vacío editable no presenta acciones de líneas ni dispara callbacks al renderizar', () => {
  const unexpected = () => { throw new Error('No ejecutar operaciones durante el render') }
  const html = renderToStaticMarkup(createElement(CartSummary, {
    cart: { moneda: 'CLP', items: [], total: 0 }, onQuantity: unexpected, onRemove: unexpected,
  }))
  assert.match(html, /Carrito vacío en este ejemplo/)
  assert.match(html, /\$0/)
  assert.doesNotMatch(html, /<form|<input|<button|Eliminar|Aplicar cantidad/)
})
