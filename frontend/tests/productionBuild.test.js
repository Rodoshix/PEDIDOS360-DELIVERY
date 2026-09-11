import assert from 'node:assert/strict'
import { test } from 'node:test'
import { build } from 'vite'

test('producción conserva el perfil pendiente y excluye todo el flujo simulado', async () => {
  const bundle = await build({ logLevel: 'silent', build: { write: false } })
  const chunks = bundle.output.filter(item => item.type === 'chunk')
  const modules = chunks.flatMap(chunk => Object.keys(chunk.modules)).join('\n').replaceAll('\\', '/')
  assert.match(modules, /\/ProfilePending\.jsx/)
  assert.doesNotMatch(modules, /\/(ProfilePanel|ProfileForm|ProfilePreview)\.jsx/)
  assert.doesNotMatch(modules, /\/(profileDemo|profileDemoAdapter|profileService|profileForm)\.js/)
  const code = chunks.map(chunk => chunk.code).join('\n')
  assert.match(code, /Perfil aún no consultado/)
  assert.doesNotMatch(code, /alex@example\.test|Cargar escenario|Guardado simulado completado/)
  assert.match(modules, /\/CartPending\.jsx/)
  assert.doesNotMatch(modules, /\/(CartDemoPanel|CartSummary)\.jsx|\/cartDemo\.js/)
  assert.doesNotMatch(modules, /\/(CartQuantityForm|CartCatalogForm)\.jsx|\/(cartOperations|cartCatalogDemo)\.js/)
  assert.match(code, /Carrito aún no consultado/)
  assert.doesNotMatch(code, /Hamburguesa de ejemplo|Ver carrito de ejemplo|Ver ejemplo vacío/)
})
