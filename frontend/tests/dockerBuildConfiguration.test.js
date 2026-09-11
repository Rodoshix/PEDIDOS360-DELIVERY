import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'

const fixture = {
  VITE_ENTRA_CLIENT_ID: '11111111-1111-1111-1111-111111111111',
  VITE_ENTRA_TENANT_ID: '22222222-2222-2222-2222-222222222222',
  VITE_ENTRA_REDIRECT_URI: 'http://localhost:5180',
  VITE_ENTRA_API_SCOPE: 'api://33333333-3333-3333-3333-333333333333/access_as_user',
}
function validate(values) {
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('VITE_')))
  return spawnSync(process.execPath, ['tools/validate-docker-build.mjs'], {
    env: { ...env, ...values }, encoding: 'utf8', timeout: 5000,
  })
}
test('build Docker rechaza variables ausentes sin cargar el .env.local del host', () => {
  const result = validate({})
  assert.equal(result.status, 1)
  assert.match(result.stderr, /Falta configurar VITE_ENTRA_CLIENT_ID/)
})
test('build Docker rechaza configuración inválida sin imprimir sus valores', () => {
  const result = validate({ ...fixture, VITE_ENTRA_TENANT_ID: 'VALOR-NO-VISIBLE' })
  assert.equal(result.status, 1)
  assert.match(result.stderr, /VITE_ENTRA_TENANT_ID debe ser un UUID/)
  assert.doesNotMatch(result.stderr + result.stdout, /VALOR-NO-VISIBLE/)
})
test('build Docker acepta el formato público sin autenticar ni mostrar identificadores', () => {
  const result = validate(fixture)
  assert.equal(result.status, 0)
  assert.equal(result.stderr + result.stdout, '')
})
