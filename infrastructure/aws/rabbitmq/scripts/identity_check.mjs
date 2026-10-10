// Offline validation only. No key generation, network access or secret output.
import { readFileSync, lstatSync, realpathSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';

export const identityFiles = ['PEDIDOS360_ACTOR_PRIVATE_JWK', 'PEDIDOS360_ACTOR_PUBLIC_JWKS',
  'PEDIDOS360_ACTOR_KEY_ID', 'USUARIOS_IDENTITY_PROOF_PRIVATE_JWK',
  'USUARIOS_IDENTITY_PROOF_PUBLIC_JWKS', 'USUARIOS_IDENTITY_PROOF_KEY_ID'];
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const invalid = () => { throw new Error('EP2 identity configuration invalid; no private material disclosed'); };

// JSON.parse alone silently accepts duplicate object members. Check each object first.
export function strictJson(text) {
  if (!text || text.length > 65536) invalid();
  let pos = 0;
  const ws = () => { while (/\s/.test(text[pos] || '') && pos < text.length) pos++; };
  const str = () => {
    const start = pos++;
    while (pos < text.length) {
      const c = text[pos++];
      if (c === '\\') pos++;
      else if (c === '"') return JSON.parse(text.slice(start, pos));
    }
    invalid();
  };
  const value = (depth = 0) => {
    if (depth > 20) invalid();
    ws();
    const c = text[pos];
    if (c === '"') { str(); return; }
    if (c === '{' || c === '[') {
      const object = c === '{', end = object ? '}' : ']', names = new Set();
      pos++; ws(); if (text[pos] === end) { pos++; return; }
      for (;;) {
        ws();
        if (object) {
          if (text[pos] !== '"') invalid();
          const name = str(); if (names.has(name)) invalid(); names.add(name);
          ws(); if (text[pos++] !== ':') invalid();
        }
        value(depth + 1); ws();
        if (text[pos] === end) { pos++; return; }
        if (text[pos++] !== ',') invalid();
      }
    }
    const start = pos;
    while (pos < text.length && !/[\s,}\]]/.test(text[pos])) pos++;
    if (pos === start) invalid();
    JSON.parse(text.slice(start, pos));
  };
  value(); ws(); if (pos !== text.length) invalid();
  return JSON.parse(text);
}

function key(jwk, privateKey) {
  if (!jwk || typeof jwk !== 'object' || Array.isArray(jwk)) invalid();
  const allowed = ['kty', 'crv', 'x', 'y', 'kid', 'alg', 'use', ...(privateKey ? ['d'] : [])];
  if (Object.keys(jwk).some(k => !allowed.includes(k)) || jwk.kty !== 'EC'
      || jwk.crv !== 'P-256' || jwk.alg !== 'ES256' || !uuid.test(jwk.kid)
      || (jwk.use !== undefined && jwk.use !== 'sig')) invalid();
  for (const field of ['x', 'y', ...(privateKey ? ['d'] : [])]) {
    if (typeof jwk[field] !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(jwk[field])
        || Buffer.from(jwk[field], 'base64url').length !== 32
        || Buffer.from(jwk[field], 'base64url').toString('base64url') !== jwk[field]) invalid();
  }
  return privateKey ? createPrivateKey({ key: jwk, format: 'jwk' })
    : createPublicKey({ key: jwk, format: 'jwk' });
}

export function validateIdentity(root) {
  const directory = realpathSync(root);
  const load = name => {
    const file = resolve(directory, name), stat = lstatSync(file);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 65536
        || (process.platform !== 'win32' && (stat.mode & 0o077))) invalid();
    return readFileSync(file, 'utf8').trim();
  };
  const groups = [];
  for (const prefix of ['PEDIDOS360_ACTOR', 'USUARIOS_IDENTITY_PROOF']) {
    const kid = load(prefix + '_KEY_ID'); if (!uuid.test(kid)) invalid();
    const privateJwk = strictJson(load(prefix + '_PRIVATE_JWK'));
    const privateObject = key(privateJwk, true);
    if (privateJwk.kid !== kid) invalid();
    const publicSet = strictJson(load(prefix + '_PUBLIC_JWKS'));
    if (!publicSet || Object.keys(publicSet).join() !== 'keys' || !Array.isArray(publicSet.keys)
        || publicSet.keys.length < 1 || publicSet.keys.length > 16) invalid();
    const ids = new Set(), materials = new Set();
    for (const jwk of publicSet.keys) {
      key(jwk, false);
      const material = jwk.x + '.' + jwk.y;
      if (ids.has(jwk.kid) || materials.has(material)) invalid();
      ids.add(jwk.kid); materials.add(material);
    }
    const active = publicSet.keys.find(k => k.kid === kid);
    if (!active || active.x !== privateJwk.x || active.y !== privateJwk.y) invalid();
    const challenge = Buffer.from('EP2 offline configuration check, not an actor or identity proof');
    if (!verify('sha256', challenge, key(active, false), sign('sha256', challenge, privateObject))) invalid();
    groups.push({ ids, materials });
  }
  if ([...groups[0].ids].some(id => groups[1].ids.has(id))
      || [...groups[0].materials].some(m => groups[1].materials.has(m))) invalid();
  return true;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.length !== 3) invalid();
    validateIdentity(process.argv[2]);
    console.log('EP2 identity preflight OK: distinct ES256 issuers, public-only verification sets');
  } catch {
    console.error('BLOCKED: EP2 identity configuration invalid or incomplete; private material omitted');
    process.exitCode = 1;
  }
}
