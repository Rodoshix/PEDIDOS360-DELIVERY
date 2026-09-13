import assert from 'node:assert/strict'
import { test } from 'node:test'
import { build } from 'vite'

test('producción incluye consulta de perfil real y excluye adaptadores y paneles simulados', async () => {
  const bundle = await build({ logLevel: 'silent', build: { write: false } })
  const chunks = bundle.output.filter(item => item.type === 'chunk')
  const modules = chunks.flatMap(chunk => Object.keys(chunk.modules)).join('\n').replaceAll('\\', '/')
  assert.match(modules, /\/RealProfilePanel\.jsx/)
  assert.match(modules, /\/profileHttpAdapter\.js/)
  assert.match(modules, /\/ProfileForm\.jsx/)
  assert.doesNotMatch(modules, /\/(ProfilePanel|ProfilePending|ProfilePreview)\.jsx/)
  assert.doesNotMatch(modules, /\/(profileDemo|profileDemoAdapter)\.js/)
  const code = chunks.map(chunk => chunk.code).join('\n')
  assert.match(code, /Consultando tu perfil/)
  // El formulario real conserva el ejemplo de sintaxis de email del validador;
  // la exclusión de datos demo se comprueba por módulos, no por ese texto de ayuda.
  assert.doesNotMatch(code, /Cargar escenario|Guardado simulado completado/)
  assert.match(modules, /\/RealCartPanel\.jsx/)
  assert.match(modules, /\/RealCatalog\.jsx/)
  assert.match(modules, /\/cartHttpAdapter\.js/)
  assert.doesNotMatch(modules, /\/(CartDemoPanel|CartPending|CartCatalogForm)\.jsx|\/(cartDemo|cartDemoAdapter|cartCatalogDemo)\.js/)
  assert.match(code, /Consultando tu carrito/)
  assert.doesNotMatch(code, /Hamburguesa de ejemplo|Ver carrito de ejemplo|Ver ejemplo vacío/)
})
