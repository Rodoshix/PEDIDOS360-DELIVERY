import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const cwd = fileURLToPath(new URL('.', import.meta.url));
const env = { ...process.env, IMAGE_REGISTRY: 'registry.example.test/team', IMAGE_TAG: 'a'.repeat(40),
  FRONTEND_ORIGIN: 'https://app.example.test', ENTRA_TENANT_ID: 'a048ca4e-cd7f-4a01-a43e-cb4deccf1ff2',
  ENTRA_API_CLIENT_ID: '13c0f63f-2007-41c4-8d9f-02640b8a1886', ENTRA_FRONTEND_CLIENT_ID: '5388c832-53e4-45c8-a3be-6875b69e1a51',
  PAGOS_WORKER_CLIENT_ID: '44444444-4444-4444-8444-444444444444', RDS_HOST: 'rds.example.test',
  EP2_PRIVATE_DIR: './fixture-private', AWS_TLS_DIR: './fixture-tls', AWS_SECRETS_DIR: './fixture-secrets',
  CARRITO_RUNTIME_ROLE: 'carrito_runtime', PEDIDOS_RUNTIME_ROLE: 'pedidos_runtime', PAGOS_RUNTIME_ROLE: 'pagos_runtime',
  FRONTEND_RABBIT_IMAGE: 'registry.example.test/team/pedidos360-frontend@sha256:' + 'b'.repeat(64) };
function model(...overlays) {
  const files = ['compose.yml', 'compose.ep2-prepared.yml', 'compose.ep2-runtime-roles.yml', ...overlays];
  const r = spawnSync('docker', ['compose', '--env-file', '.env.example', ...files.flatMap(f => ['-f', f]), 'config', '--format', 'json'], { cwd, env, encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr); return JSON.parse(r.stdout);
}
test('monohost HTTP baseline preserves internal URLs, TLS, no broker recreation', () => {
  const c = model();
  assert.equal(c.services.rabbitmq, undefined);
  for (const s of ['usuarios','restaurantes','productos','carrito','pedidos','pagos']) {
    assert.equal(c.services[s].ports, undefined);
    assert.equal(c.services[s].environment.SERVER_SSL_ENABLED, 'true');
  }
  assert.equal(c.services.bff.environment.BFF_QUERY_PAGOS_MODE, 'HTTP');
  assert.equal(c.services.bff.environment.PAGOS_SERVICE_URL, 'https://pagos:8086');
  for (const s of ['carrito','pedidos','pagos']) {
    assert.equal(c.services[s].environment.SPRING_FLYWAY_ENABLED, 'false');
    const db = c.services[s].secrets.filter(x => x.target === 'spring.datasource.password');
    assert.deepEqual(db.map(x => x.source), [s + '_runtime_password']);
  }
});
test('query cut does not start purchase dispatchers or cart coordination', () => {
  const c = model('compose.ep2-query-candidate.yml');
  assert.equal(c.services.bff.environment.PEDIDOS360_RELAY_MODE, 'ACTIVE');
  for (const s of ['USUARIOS','RESTAURANTES','PRODUCTOS','PAGOS']) assert.equal(c.services.bff.environment['BFF_QUERY_'+s+'_MODE'], 'RABBITMQ');
  assert.equal(c.services.pagos.environment.PEDIDOS360_COORDINATION_MODE, 'HTTP');
  assert.equal(c.services.pedidos.environment.PEDIDOS_CARRITO_MODE, 'HTTP');
  for (const [s, privateKey] of [['bff','ep2_actor_private'], ['usuarios','ep2_identity_private']])
    assert.equal(Object.values(c.services).filter(x => x.secrets?.some(y => y.source===privateKey)).length, 1);
});
test('purchase cut disables HTTP worker, cart cut explicitly changes only React image', () => {
  const c = model('compose.ep2-query-candidate.yml','compose.ep2-pago-candidate.yml','compose.ep2-carrito-candidate.yml');
  assert.equal(c.services.pagos.environment.PAGOS_WORKER_ENABLED, 'false');
  assert.equal(c.services.pagos.environment.PEDIDOS360_COORDINATION_MODE, 'RABBITMQ');
  assert.equal(c.services.pedidos.environment.PEDIDOS_CARRITO_MODE, 'RABBITMQ');
  assert.equal(c.services.carrito.environment.CARRITO_PEDIDOS_MODE, 'RABBITMQ');
  assert.equal(c.services.frontend.image, env.FRONTEND_RABBIT_IMAGE);
  assert.equal(c.services.pagos.environment.PAGOS_QUERY_RABBITMQ_USERNAME, 'p360-pagos-consumer');
  for (const s of ['bff','usuarios','restaurantes','productos','carrito','pedidos','pagos']) {
    assert.equal(c.services[s].environment.PEDIDOS360_DECLARE_TOPOLOGY, 'false');
    assert.equal(c.services[s].environment.LOCAL_IDENTITY_ENABLED, 'false');
  }
});
