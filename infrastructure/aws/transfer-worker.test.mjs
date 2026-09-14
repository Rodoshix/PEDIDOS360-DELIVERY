import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateKeyPairSync, publicEncrypt, privateDecrypt, constants } from 'node:crypto'
import { parseWorker } from './transfer-worker.mjs'

test('worker parser reads only the expected key and rejects ambiguous/invalid input', () => {
  const marker = 'fixture-only-worker-value'
  assert.equal(parseWorker(`OTHER=ignored\nPAGOS_WORKER_CLIENT_SECRET=${marker}\n`), marker)
  assert.equal(parseWorker(`export PAGOS_WORKER_CLIENT_SECRET="${marker}"`), marker)
  assert.equal(parseWorker(`PAGOS_WORKER_CLIENT_SECRET='${marker}'`), marker)
  for (const value of ['', 'PAGOS_WORKER_CLIENT_SECRET=short', 'PAGOS_WORKER_CLIENT_SECRET=contains spaces', `PAGOS_WORKER_CLIENT_SECRET=${marker}\nPAGOS_WORKER_CLIENT_SECRET=${marker}`, `PAGOS_WORKER_CLIENT_SECRET=${'a'.repeat(447)}`]) {
    assert.throws(() => parseWorker(value))
  }
})

test('RSA OAEP handoff preserves a fictitious worker without transmitting plaintext', () => {
  const keys = generateKeyPairSync('rsa', { modulusLength: 4096 })
  const original = 'fixture-only-worker-value'
  const options = { padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }
  const payload = publicEncrypt({ ...options, key: keys.publicKey }, Buffer.from(original)).toString('base64')
  assert.ok(!payload.includes(original))
  assert.equal(privateDecrypt({ ...options, key: keys.privateKey }, Buffer.from(payload, 'base64')).toString(), original)
  assert.throws(() => privateDecrypt({ ...options, key: keys.privateKey }, Buffer.alloc(512)))
})
