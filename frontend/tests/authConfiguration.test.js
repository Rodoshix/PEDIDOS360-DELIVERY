import assert from 'node:assert/strict'
import test from 'node:test'
import { AuthConfigurationError, createAuthConfiguration } from '../src/auth/authConfiguration.js'

const values = {
  VITE_ENTRA_CLIENT_ID: '11111111-1111-1111-1111-111111111111',
  VITE_ENTRA_TENANT_ID: '22222222-2222-2222-2222-222222222222',
  VITE_ENTRA_REDIRECT_URI: 'http://localhost:5173',
  VITE_ENTRA_API_SCOPE: 'api://33333333-3333-3333-3333-333333333333/access_as_user',
}

test('configura un directorio específico, la SPA y únicamente el ámbito de nuestra API', () => {
  const result = createAuthConfiguration(values, { origin: 'http://localhost:5173' })
  assert.equal(result.msalConfig.auth.clientId, values.VITE_ENTRA_CLIENT_ID)
  assert.equal(result.msalConfig.auth.authority, `https://login.microsoftonline.com/${values.VITE_ENTRA_TENANT_ID}`)
  assert.equal(result.msalConfig.auth.redirectUri, values.VITE_ENTRA_REDIRECT_URI)
  assert.equal(result.msalConfig.auth.postLogoutRedirectUri, values.VITE_ENTRA_REDIRECT_URI)
  assert.equal(result.msalConfig.cache.cacheLocation, 'sessionStorage')
  assert.equal(result.msalConfig.system.loggerOptions.piiLoggingEnabled, false)
  assert.deepEqual(result.apiTokenRequest.scopes, [values.VITE_ENTRA_API_SCOPE])
  assert.equal('clientSecret' in result.msalConfig.auth, false)
})

for (const key of Object.keys(values)) {
  test(`rechaza ${key} ausente o vacía sin exponer su contenido`, () => {
    for (const value of [undefined, '', '  ']) {
      assert.throws(() => createAuthConfiguration({ ...values, [key]: value }), {
        name: 'AuthConfigurationError', message: `Falta configurar ${key}.`,
      })
    }
  })
}

test('rechaza identificadores inválidos y autoridades multitenant', () => {
  for (const key of ['VITE_ENTRA_CLIENT_ID', 'VITE_ENTRA_TENANT_ID']) {
    for (const value of ['common', 'organizations', 'no-es-un-uuid']) {
      assert.throws(() => createAuthConfiguration({ ...values, [key]: value }), AuthConfigurationError)
    }
  }
})

test('acepta HTTPS para despliegue y elimina espacios de las variables', () => {
  const result = createAuthConfiguration({
    ...values,
    VITE_ENTRA_CLIENT_ID: ` ${values.VITE_ENTRA_CLIENT_ID} `,
    VITE_ENTRA_REDIRECT_URI: 'https://pedidos.example.test/',
  }, { origin: 'https://pedidos.example.test' })
  assert.equal(result.msalConfig.auth.clientId, values.VITE_ENTRA_CLIENT_ID)
  assert.equal(result.msalConfig.auth.redirectUri, 'https://pedidos.example.test/')
})

test('rechaza redirecciones inseguras, relativas o con datos adicionales', () => {
  for (const uri of ['/login', 'javascript:alert(1)', 'http://pedidos.example.test',
    'https://user:password@pedidos.example.test', 'http://localhost:5173?code=test',
    'http://localhost:5173#fragment', 'http://localhost.evil.test:5173']) {
    assert.throws(() => createAuthConfiguration({ ...values, VITE_ENTRA_REDIRECT_URI: uri }), AuthConfigurationError)
  }
})

test('rechaza abrir el frontend desde otro origen o puerto', () => {
  for (const origin of ['http://localhost:5174', 'http://127.0.0.1:5173', 'https://otro.example.test']) {
    assert.throws(() => createAuthConfiguration(values, { origin }), /mismo origen/)
  }
})

test('rechaza Graph, múltiples ámbitos, default y el ID del frontend como recurso', () => {
  for (const scope of ['User.Read', 'https://graph.microsoft.com/User.Read',
    `${values.VITE_ENTRA_API_SCOPE} User.Read`, 'api://no-valido/access_as_user',
    'api://33333333-3333-3333-3333-333333333333/.default',
    `api://${values.VITE_ENTRA_CLIENT_ID}/access_as_user`]) {
    assert.throws(() => createAuthConfiguration({ ...values, VITE_ENTRA_API_SCOPE: scope }), AuthConfigurationError)
  }
})

test('no modifica las variables de entrada', () => {
  const original = { ...values }
  createAuthConfiguration(Object.freeze(original))
  assert.deepEqual(original, values)
})
