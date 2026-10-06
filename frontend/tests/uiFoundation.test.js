import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import { MemoryRouter } from 'react-router'

let server, Button, Field, Navigation, Dialog
before(async () => {
  server = await createServer({ cacheDir: 'node_modules/.vite-ui-tests', optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom' })
  Button = (await server.ssrLoadModule('/src/components/ui/Button.jsx')).default
  Field = (await server.ssrLoadModule('/src/components/ui/Field.jsx')).Field
  Navigation = (await server.ssrLoadModule('/src/components/layout/Navigation.jsx')).default
  Dialog = (await server.ssrLoadModule('/src/components/ui/Dialog.jsx')).default
})
after(async () => { await server?.close() })

test('botón base no envía un formulario implícitamente y bloquea interacción durante loading', () => {
  const html = renderToStaticMarkup(createElement(Button, { loading: true, onClick: () => { throw new Error('No invocar al render') } }, 'Guardar'))
  assert.match(html, /type="button"/)
  assert.match(html, /disabled=""/)
  assert.match(html, /aria-busy="true"/)
  assert.match(renderToStaticMarkup(createElement(Button, { type: 'submit' }, 'Guardar')), /type="submit"/)
})
test('campo une etiqueta, ayudas previas y error sin modificar valor ni validación', () => {
  const html = renderToStaticMarkup(createElement(Field, { id: 'email', label: 'Email', hint: 'Usa tu email', error: 'Revisa el email',
    value: 'dato', readOnly: true, 'aria-describedby': 'contexto' }))
  assert.match(html, /for="email"/)
  assert.match(html, /aria-invalid="true"/)
  assert.match(html, /aria-describedby="contexto email-hint email-error"/)
  assert.match(html, /value="dato"/)
  assert.match(html, /id="email-error"/)
})
test('sidebar compacto conserva nombres accesibles, URLs y página actual', () => {
  const html = renderToStaticMarkup(createElement(MemoryRouter, { initialEntries: ['/carrito'] }, createElement(Navigation, { compact: true })))
  for (const label of ['Inicio', 'Restaurantes', 'Carrito', 'Mis pedidos', 'Mi cuenta']) assert.ok(html.includes(`aria-label="${label}"`))
  assert.match(html, /aria-current="page"/)
  assert.match(html, /href="\/carrito"/)
  assert.doesNotMatch(html, /\/admin|\/seguimiento|\/repartidores/)
})
test('dialog cerrado conserva trigger accesible y no inserta navegación modal oculta', () => {
  const html = renderToStaticMarkup(createElement(Dialog, { open: false, onOpenChange: () => {}, title: 'Menú', description: 'Navegación',
    trigger: createElement(Button, { 'aria-label': 'Abrir menú' }, 'Abrir') }, 'Contenido modal'))
  assert.match(html, /aria-haspopup="dialog"/)
  assert.match(html, /aria-expanded="false"/)
  assert.doesNotMatch(html, /Contenido modal/)
})
