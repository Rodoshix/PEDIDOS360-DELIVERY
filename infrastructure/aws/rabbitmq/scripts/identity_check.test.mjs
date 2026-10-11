import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID } from 'node:crypto';
import { mkdtempSync, writeFileSync, rmSync, readFileSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateIdentity, strictJson } from './identity_check.mjs';
import { fixture } from './identity_fixture.mjs';

function isolated(fn) {
  const root = mkdtempSync(join(tmpdir(), 'p360-71-identity-'));
  try { fixture(root); fn(root); } finally { rmSync(root, { recursive: true, force: true }); }
}
function mutate(root, file, fn) {
  const path = join(root, file), data = JSON.parse(readFileSync(path));
  fn(data); writeFileSync(path, JSON.stringify(data), { mode: 0o600 });
}
test('distinct ES256 configuration succeeds without HMAC', () => isolated(root => assert.equal(validateIdentity(root), true)));
for (const [name, change] of [
  ['HMAC', k => { k.kty = 'oct'; k.alg = 'HS256'; }],
  ['downgrade', k => { k.alg = 'ES384'; }],
  ['wrong curve', k => { k.crv = 'P-384'; }],
  ['revoked', k => { k.revoked = true; }],
  ['URL', k => { k.jku = 'https://invalid.test/'; }],
  ['kid malformed', k => { k.kid = 'missing'; }],
  ['padding', k => { k.x += '='; }],
  ['point malformed', k => { k.x = 'A'.repeat(43); k.y = 'A'.repeat(43); }],
]) test(name + ' rejected', () => isolated(root => {
  mutate(root, 'PEDIDOS360_ACTOR_PUBLIC_JWKS', d => change(d.keys[0]));
  assert.throws(() => validateIdentity(root));
}));
for (const name of ['private verification key', 'duplicate kid', 'duplicate material', 'missing active', 'extra set field'])
  test(name + ' rejected', () => isolated(root => {
    mutate(root, 'PEDIDOS360_ACTOR_PUBLIC_JWKS', d => {
      if (name === 'private verification key') d.keys[0].d = 'A'.repeat(43);
      if (name === 'duplicate kid') d.keys.push({ ...d.keys[0] });
      if (name === 'duplicate material') d.keys.push({ ...d.keys[0], kid: randomUUID() });
      if (name === 'missing active') d.keys[0].kid = randomUUID();
      if (name === 'extra set field') d.extra = [];
    });
    assert.throws(() => validateIdentity(root));
  }));
test('private scalar mismatching advertised public key rejected', () => isolated(root => {
  const other = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey.export({ format: 'jwk' });
  mutate(root, 'PEDIDOS360_ACTOR_PRIVATE_JWK', d => { d.d = other.d; });
  assert.throws(() => validateIdentity(root));
}));
test('issuers cannot reuse kid or key material', () => isolated(root => {
  for (const suffix of ['_PRIVATE_JWK', '_PUBLIC_JWKS', '_KEY_ID'])
    writeFileSync(join(root, 'USUARIOS_IDENTITY_PROOF' + suffix), readFileSync(join(root, 'PEDIDOS360_ACTOR' + suffix)));
  assert.throws(() => validateIdentity(root));
}));
test('missing file rejected', () => isolated(root => {
  rmSync(join(root, 'USUARIOS_IDENTITY_PROOF_PRIVATE_JWK')); assert.throws(() => validateIdentity(root));
}));
test('duplicate JSON and trailing document rejected', () => {
  for (const value of ['{"keys":[],"keys":[]}', '{"keys":[{"kid":"a","kid":"b"}]}', '{} {}'])
    assert.throws(() => strictJson(value));
});
test('symlink rejected', { skip: process.platform === 'win32' ? 'Windows symlink privilege not assumed' : false }, () => isolated(root => {
  const path = join(root, 'PEDIDOS360_ACTOR_PRIVATE_JWK'), contents = readFileSync(path);
  rmSync(path); writeFileSync(join(root, 'other'), contents, { mode: 0o600 }); symlinkSync(join(root, 'other'), path);
  assert.throws(() => validateIdentity(root));
}));
