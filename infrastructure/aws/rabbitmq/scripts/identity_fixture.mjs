// LOCAL TEST ONLY. Never called by operational preflight.
import { generateKeyPairSync, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { validateIdentity } from './identity_check.mjs';

export function fixture(root) {
  for (const prefix of ['PEDIDOS360_ACTOR', 'USUARIOS_IDENTITY_PROOF']) {
    const pair = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const kid = randomUUID(), metadata = { kid, alg: 'ES256', use: 'sig' };
    for (const [suffix, data] of [['_KEY_ID', kid],
      ['_PRIVATE_JWK', JSON.stringify({ ...pair.privateKey.export({ format: 'jwk' }), ...metadata })],
      ['_PUBLIC_JWKS', JSON.stringify({ keys: [{ ...pair.publicKey.export({ format: 'jwk' }), ...metadata }] })]]) {
      writeFileSync(resolve(root, prefix + suffix), data, { mode: 0o600, flag: 'wx' });
    }
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  // Fixed ignored fixture destination, no caller-supplied operational path.
  const root = fileURLToPath(new URL('../test-local/private/identity/', import.meta.url));
  if (!existsSync(root)) { mkdirSync(root, { recursive: true, mode: 0o700 }); fixture(root); }
  validateIdentity(root);
  console.log('Local disposable identity fixture ready; no AWS material generated');
}
