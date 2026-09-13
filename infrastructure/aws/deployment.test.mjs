import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { prepare, validateMaterial, readConfig } from './deployment.mjs'

const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'pedidos360-aws-tests-'))
const config = {
  IMAGE_REGISTRY: '123456789012.dkr.ecr.us-east-1.amazonaws.com', IMAGE_TAG: 'a'.repeat(40),
  FRONTEND_ORIGIN: 'https://app.pedidos360.invalid', PUBLIC_API_BASE_URL: 'https://api.pedidos360.invalid/api',
  ENTRA_TENANT_ID: '11111111-1111-4111-8111-111111111111', ENTRA_API_CLIENT_ID: '22222222-2222-4222-8222-222222222222',
  ENTRA_FRONTEND_CLIENT_ID: '33333333-3333-4333-8333-333333333333', PAGOS_WORKER_CLIENT_ID: '44444444-4444-4444-8444-444444444444',
  RDS_HOST: 'fixture.abc.us-east-1.rds.amazonaws.com', AWS_TLS_DIR: './private/tls', AWS_SECRETS_DIR: './private/secrets',
}
const file = path.join(temp, '.env.deploy')
function writeConfig(overrides = {}) { fs.writeFileSync(file, Object.entries({ ...config, ...overrides }).map(([k,v]) => `${k}=${v}`).join('\n')) }

test('public configuration validates and rejects unsafe/missing/duplicate values', () => {
  writeConfig()
  assert.equal(readConfig(file).IMAGE_TAG, config.IMAGE_TAG)
  for (const bad of [{ IMAGE_TAG: 'latest' }, { RDS_HOST: 'localhost' }, { FRONTEND_ORIGIN: 'http://app.invalid' }, { PUBLIC_API_BASE_URL: 'https://user:password@api.invalid' }, { ENTRA_TENANT_ID: '' }, { DB_PASSWORD: 'do-not-accept' }, { FRONTEND_ORIGIN: 'https://app.invalid/path' }]) {
    writeConfig(bad)
    assert.throws(() => readConfig(file))
  }
  writeConfig()
  fs.appendFileSync(file, '\nIMAGE_TAG=duplicate')
  assert.throws(() => readConfig(file), /duplicada/)
})

test('prepare requires worker and JDK before creating files', () => {
  const root = path.join(temp, 'missing')
  assert.throws(() => prepare(root, { worker: '' }))
  assert.equal(fs.existsSync(root), false)
})

test('up rejects incomplete configuration without invoking Docker or leaking values', () => {
  writeConfig({ IMAGE_TAG: 'private-marker-do-not-print' })
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./deployment.mjs', import.meta.url)), 'up', file], { encoding: 'utf8' })
  assert.equal(result.status, 1)
  assert.match(result.stderr, /IMAGE_TAG debe ser SHA completo/)
  assert.ok(!result.stderr.includes('private-marker-do-not-print'))
  assert.equal(result.stdout, '')
})

test('real keytool generates separate secrets and TLS; tampering and overwrite fail', { timeout: 180000 }, () => {
  const root = path.join(temp, 'private')
  let javaHome = process.env.JAVA_HOME
  if (!javaHome) {
    const result = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
    javaHome = /java.home = (.+)/.exec(result.stderr)?.[1].trim()
  }
  assert.ok(javaHome, 'JDK 21 required')
  const options = { worker: 'fixture-worker-not-a-real-secret', cacerts: path.join(javaHome, 'lib', 'security', 'cacerts') }
  prepare(root, options)
  validateMaterial(root)
  const before = fs.readFileSync(path.join(root, 'secrets', 'tls_password'))
  assert.throws(() => prepare(root, options), /ya existe/)
  assert.deepEqual(fs.readFileSync(path.join(root, 'secrets', 'tls_password')), before)
  const crt = path.join(root, 'tls', 'usuarios.crt')
  fs.chmodSync(crt, 0o600)
  fs.copyFileSync(path.join(root, 'tls', 'bff.crt'), crt)
  assert.throws(() => validateMaterial(root), /no coinciden/)
  const worker = path.join(root, 'secrets', 'worker_secret')
  fs.chmodSync(worker, 0o600)
  fs.writeFileSync(worker, '')
  assert.throws(() => validateMaterial(root))
})

test.after(() => {
  // Only this invocation's exact temporary directory; never a deployment path.
  assert.equal(path.dirname(temp), path.resolve(os.tmpdir()))
  assert.ok(path.basename(temp).startsWith('pedidos360-aws-tests-'))
  fs.rmSync(temp, { recursive: true, force: true })
})
