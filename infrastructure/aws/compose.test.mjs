import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const cwd = fileURLToPath(new URL('.', import.meta.url))
const names = ['frontend', 'bff', 'usuarios', 'restaurantes', 'productos', 'carrito', 'pedidos', 'pagos']
const env = {
  ...process.env,
  IMAGE_REGISTRY: 'registry.example.test/team', IMAGE_TAG: 'a'.repeat(40),
  FRONTEND_ORIGIN: 'https://app.example.test', PUBLIC_API_BASE_URL: 'https://api.example.test/api',
  ENTRA_TENANT_ID: '11111111-1111-4111-8111-111111111111',
  ENTRA_API_CLIENT_ID: '22222222-2222-4222-8222-222222222222',
  ENTRA_FRONTEND_CLIENT_ID: '33333333-3333-4333-8333-333333333333',
  PAGOS_WORKER_CLIENT_ID: '44444444-4444-4444-8444-444444444444',
  RDS_HOST: 'database.example.test', AWS_TLS_DIR: './test-fixture-tls',
  AWS_SECRETS_DIR: './test-fixture-secrets',
}
function config(build = false, overrides = {}) {
  const args = ['compose', '--env-file', '.env.example', '-f', 'compose.yml']
  if (build) args.push('-f', 'compose.build.yml')
  args.push('config', '--format', 'json')
  return spawnSync('docker', args, { cwd, env: { ...env, ...overrides }, encoding: 'utf8' })
}
function model(build = false) {
  const result = config(build)
  assert.equal(result.status, 0, result.stderr)
  return JSON.parse(result.stdout)
}

test('runtime has only eight prebuilt amd64 applications, no database containers', () => {
  const c = model()
  assert.deepEqual(Object.keys(c.services).sort(), [...names].sort())
  assert.equal(Object.keys(c.volumes ?? {}).length, 0)
  for (const [name, s] of Object.entries(c.services)) {
    assert.equal(s.build, undefined)
    assert.equal(s.platform, 'linux/amd64')
    assert.equal(s.image, `${env.IMAGE_REGISTRY}/pedidos360-${name}:${env.IMAGE_TAG}`)
    assert.equal(s.read_only, true)
    assert.equal(s.restart, 'unless-stopped')
    assert.equal(s.logging.options['max-size'], '10m')
    assert.equal(s.logging.options['max-file'], '3')
    assert.ok(s.cap_drop.includes('ALL'))
    for (const p of s.ports ?? []) assert.equal(p.host_ip, '127.0.0.1')
    if (!['frontend', 'bff'].includes(name)) assert.equal(s.ports, undefined)
    for (const dependency of Object.keys(s.depends_on ?? {})) assert.ok(names.includes(dependency))
  }
  assert.equal(c.services.frontend.volumes, undefined, 'do not reuse the local /api bypass proxy')
})

test('six databases require RDS TLS, separate users, file passwords and bounded pools', () => {
  const c = model()
  const users = new Set()
  for (const name of names.slice(2)) {
    const s = c.services[name]
    assert.equal(s.environment.DB_URL, `jdbc:postgresql://${env.RDS_HOST}:5432/pedidos360_${name}?sslmode=verify-full&sslrootcert=/run/tls/rds-ca.pem`)
    users.add(s.environment.DB_USERNAME)
    assert.equal(s.environment.DB_PASSWORD, undefined)
    assert.equal(s.environment.SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE, '5')
    assert.ok(s.secrets.some(v => v.source === `${name}_db_password` && v.target === 'spring.datasource.password'))
    assert.ok(s.volumes.some(v => v.target === '/run/tls/rds-ca.pem' && v.read_only))
    assert.ok(Object.hasOwn(s.networks, 'egress'))
  }
  assert.equal(users.size, 6)
})

test('TLS, Entra and worker contract remain enabled', () => {
  const c = model()
  for (const name of names.slice(1)) {
    const s = c.services[name]
    assert.equal(s.environment.SERVER_SSL_ENABLED, 'true')
    assert.equal(s.environment.LOCAL_IDENTITY_ENABLED, 'false')
    assert.equal(s.environment.SPRING_CONFIG_IMPORT, 'configtree:/run/secrets/')
    assert.ok(s.volumes.some(v => v.target === '/run/tls/server.p12' && v.read_only))
  }
  for (const name of ['bff', 'usuarios', 'carrito', 'pedidos', 'pagos']) {
    assert.equal(c.services[name].environment.ENTRA_ENABLED, 'true')
  }
  assert.equal(c.services.bff.environment.BFF_CORS_ORIGINS, env.FRONTEND_ORIGIN)
  assert.equal(c.services.pagos.environment.PAGOS_WORKER_ENABLED, 'true')
  assert.ok(c.services.pagos.secrets.some(v => v.target === 'pagos.worker.client-secret'))
  assert.equal(c.services.pedidos.environment.INTERNO_ENABLED, 'true')
  assert.equal(c.networks.services.internal, true)
})

test('build overlay uses repo Dockerfiles and public frontend arguments only', () => {
  const c = model(true)
  for (const name of names) assert.ok(c.services[name].build.context)
  const args = c.services.frontend.build.args
  assert.equal(args.VITE_API_BASE_URL, env.PUBLIC_API_BASE_URL)
  assert.equal(args.VITE_ENTRA_REDIRECT_URI, env.FRONTEND_ORIGIN)
  assert.equal(args.VITE_ENTRA_API_SCOPE, `api://${env.ENTRA_API_CLIENT_ID}/access_as_user`)
  assert.equal(Object.keys(args).length, 5)
  assert.ok(!Object.keys(args).some(k => /SECRET|PASSWORD/.test(k)))
})

test('missing mandatory deployment values fail before launch', () => {
  for (const key of ['IMAGE_REGISTRY', 'IMAGE_TAG', 'RDS_HOST', 'FRONTEND_ORIGIN', 'ENTRA_TENANT_ID', 'ENTRA_API_CLIENT_ID', 'ENTRA_FRONTEND_CLIENT_ID', 'PAGOS_WORKER_CLIENT_ID']) {
    const result = config(false, { [key]: '' })
    assert.notEqual(result.status, 0, key)
    assert.ok(result.stderr.includes(key), result.stderr)
  }
  assert.notEqual(config(true, { PUBLIC_API_BASE_URL: '' }).status, 0)
})
