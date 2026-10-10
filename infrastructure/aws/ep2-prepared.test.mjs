import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
const cwd = fileURLToPath(new URL('.', import.meta.url));
const env = { ...process.env, IMAGE_REGISTRY: 'registry.example.test/team', IMAGE_TAG: 'a'.repeat(40),
  FRONTEND_ORIGIN: 'https://app.example.test', PUBLIC_API_BASE_URL: 'https://api.example.test/api',
  ENTRA_TENANT_ID: '11111111-1111-4111-8111-111111111111',
  ENTRA_API_CLIENT_ID: '22222222-2222-4222-8222-222222222222',
  ENTRA_FRONTEND_CLIENT_ID: '33333333-3333-4333-8333-333333333333',
  PAGOS_WORKER_CLIENT_ID: '44444444-4444-4444-8444-444444444444',
  RDS_HOST: 'database.example.test', AWS_TLS_DIR: './test-fixture-tls',
  AWS_SECRETS_DIR: './test-fixture-secrets', EP2_PRIVATE_DIR: './rabbitmq/test-local/private' };
function model(build = false, override = {}) {
  // Include the optional profile in the read-only model, without starting it.
  const args = ['compose', '--profile', 'ep2-admin-prepared', '--env-file', '.env.example', '-f', 'compose.yml', '-f', 'compose.ep2-prepared.yml'];
  if (build) args.push('-f', 'compose.build.yml', '-f', 'compose.ep2-build.yml');
  args.push('config', '--format', 'json');
  const result = spawnSync('docker', args, { cwd, env: { ...env, ...override }, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}
test('HTTP defaults fixed despite activation values in caller environment', () => {
  const c = model(false, { PEDIDOS360_RELAY_MODE: 'ACTIVE', PEDIDOS_CARRITO_MODE: 'RABBITMQ',
    BFF_QUERY_PAGOS_MODE: 'RABBITMQ', PEDIDOS360_COORDINATION_MODE: 'RABBITMQ' });
  for (const service of ['bff', 'usuarios', 'restaurantes', 'productos', 'pedidos', 'pagos', 'carrito']) {
    const e = c.services[service].environment;
    assert.equal(e.PEDIDOS360_RELAY_MODE, 'DISABLED');
    assert.equal(e.PEDIDOS360_IDENTITY_PROOF_ENABLED, 'false');
    assert.equal(e.PEDIDOS360_DECLARE_TOPOLOGY, 'false');
    assert.equal(e.SPRING_RABBITMQ_DYNAMIC, 'false');
    assert.equal(e.SPRING_RABBITMQ_PORT, '5671');
    for (const name of ['ENABLED', 'VERIFYHOSTNAME', 'VALIDATESERVERCERTIFICATE'])
      assert.equal(e['SPRING_RABBITMQ_SSL_' + name], 'true');
  }
  for (const d of ['USUARIOS', 'RESTAURANTES', 'PRODUCTOS', 'PAGOS']) assert.equal(c.services.bff.environment['BFF_QUERY_' + d + '_MODE'], 'HTTP');
  for (const s of ['pedidos', 'pagos']) assert.equal(c.services[s].environment.PEDIDOS360_COORDINATION_MODE, 'HTTP');
  assert.equal(c.services.pedidos.environment.PEDIDOS_CARRITO_MODE, 'HTTP');
  assert.equal(c.services.carrito.environment.CARRITO_PEDIDOS_MODE, 'HTTP');
  assert.equal(c.services.pedidos.environment.PEDIDOS_CARRITO_PLATFORM_READY, 'false');
  assert.equal(c.services.carrito.environment.CARRITO_PEDIDOS_PLATFORM_READY, 'false');
});
test('only BFF and Usuarios receive their own private signing material', () => {
  const c = model();
  for (const [name, s] of Object.entries(c.services)) {
    const sources = (s.secrets || []).map(v => v.source);
    assert.equal(sources.includes('ep2_actor_private'), name === 'bff');
    assert.equal(sources.includes('ep2_identity_private'), name === 'usuarios');
    assert.equal(JSON.stringify(s.environment || {}).includes('PRIVATE_JWK'), false);
  }
  assert.equal(Object.keys(c.secrets).some(v => v.includes('ACTOR_SECRET')), false);
});
test('dedicated connections retain publisher identities', () => {
  const c = model(), e = c.services.pagos.environment;
  assert.equal(e.SPRING_RABBITMQ_USERNAME, 'p360-pagos-publisher');
  assert.equal(e.PAGOS_QUERY_RABBITMQ_USERNAME, 'p360-pagos-consumer');
  for (const flag of ['ENABLED', 'VERIFYHOSTNAME', 'VALIDATESERVERCERTIFICATE']) {
    assert.equal(e['PAGOS_CONSULTAS_RABBITMQ_SSL_' + flag], 'true');
    for (const name of ['pedidos', 'carrito']) assert.equal(c.services[name].environment['PEDIDOS360_MESSAGING_CARRITO_SSL_' + flag], 'true');
  }
  assert.equal(c.services.pedidos.environment.SPRING_RABBITMQ_USERNAME, 'p360-pedidos-consumer');
  assert.equal(c.services.pedidos.environment.PEDIDOS360_MESSAGING_CARRITO_USERNAME, 'p360-pedidos-carrito-publisher');
});
test('private Admin is opt-in, no broker/ports/networks added to public edge', () => {
  const c = model(), a = c.services['rabbit-admin'];
  assert.deepEqual(a.profiles, ['ep2-admin-prepared']);
  assert.equal(a.ports, undefined); assert.deepEqual(Object.keys(a.networks), ['services']);
  assert.equal(c.networks.services.internal, true);
  assert.equal(a.environment.RABBIT_ADMIN_TLS, 'true');
  assert.equal(a.environment.SERVER_SSL_ENABLED, 'true');
  assert.equal(c.services.rabbitmq, undefined);
  for (const name of ['usuarios', 'restaurantes', 'productos', 'pedidos', 'pagos', 'carrito']) assert.equal(c.services[name].ports, undefined);
  for (const name of ['frontend', 'bff']) for (const p of c.services[name].ports) assert.equal(p.host_ip, '127.0.0.1');
});
test('existing HTTP URLs, DB secrets and frozen frontend build mode retained', () => {
  const c = model(true, { VITE_PEDIDOS_CARRITO_MODE: 'RABBITMQ' });
  assert.equal(c.services.frontend.build.args.VITE_PEDIDOS_CARRITO_MODE, 'HTTP');
  assert.equal(c.services.bff.environment.PAGOS_SERVICE_URL, 'https://pagos:8086');
  assert.equal(c.services.bff.environment.CARRITO_SERVICE_URL, 'https://carrito:8084');
  for (const name of ['usuarios', 'restaurantes', 'productos', 'pedidos', 'pagos', 'carrito'])
    assert.ok(c.services[name].secrets.some(v => v.source === name + '_db_password'));
  assert.equal(c.services['rabbit-admin'].build.context.endsWith('rabbit-admin-service'), true);
  assert.match(readFileSync(new URL('rabbitmq/Dockerfile.rabbit-admin', import.meta.url), 'utf8'), /USER 10001:10001/);
});
