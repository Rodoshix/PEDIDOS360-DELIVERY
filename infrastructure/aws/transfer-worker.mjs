// One-shot Academy handoff. Never put the worker value in SSM command parameters.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import { createHash, publicEncrypt, constants } from 'node:crypto'

const here = path.dirname(fileURLToPath(import.meta.url))
const instance = 'i-0c1b0042eb32cf54e'
const awsArgs = ['--profile', 'pedidos360-lab', '--region', 'us-east-1', '--no-cli-pager', '--output', 'json', '--cli-connect-timeout', '10', '--cli-read-timeout', '20']
function aws(args) {
  try {
    return JSON.parse(execFileSync('aws', [...args, ...awsArgs], { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], timeout: 45000 }))
  } catch { throw new Error('AWS CLI fallo; salida omitida. Revisar perfil o comando por su ID.') }
}
async function remote(source) {
  const command = "/usr/local/bin/node --input-type=module <<'P360_NODE'\n" + source + "\nP360_NODE"
  const result = aws(['ssm', 'send-command', '--instance-ids', instance, '--document-name', 'AWS-RunShellScript', '--parameters', JSON.stringify({ commands: [command], executionTimeout: ['300'] })])
  const id = result.Command.CommandId
  console.log('SSM:', id)
  for (let i = 0; i < 100; i++) {
    await new Promise(resolve => setTimeout(resolve, 3000))
    let item
    try { item = aws(['ssm', 'get-command-invocation', '--command-id', id, '--instance-id', instance]) }
    catch { if (i < 3) continue; throw new Error('No se pudo consultar SSM; no repetir a ciegas: ' + id) }
    if (['Pending', 'InProgress', 'Delayed'].includes(item.Status)) continue
    if (item.Status !== 'Success') throw new Error('Comando remoto fallo; conservar material y revisar ID: ' + id)
    return JSON.parse(item.StandardOutputContent)
  }
  throw new Error('Espera agotada; el comando puede continuar. No repetir: ' + id)
}

export function parseWorker(text) {
  const rows = text.split(/\r?\n/).filter(line => /^\s*(?:export\s+)?PAGOS_WORKER_CLIENT_SECRET\s*=/.test(line))
  if (rows.length !== 1) throw new Error('Se requiere una unica variable worker en el archivo local.')
  let value = rows[0].slice(rows[0].indexOf('=') + 1).trim()
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1)
  if (value.length < 12 || /\s|\0/.test(value) || Buffer.byteLength(value) > 446) throw new Error('Formato worker no compatible; no mostrar contenido.')
  return value
}

async function main() {
  const identity = aws(['sts', 'get-caller-identity'])
  if (identity.Account !== '694507973514') throw new Error('Cuenta distinta del laboratorio; se detuvo.')
  const sourceFile = path.resolve(here, '../../backend/services/pagos-service/.env.worker.local')
  const worker = parseWorker(fs.readFileSync(sourceFile, 'utf8'))
  const deployment = fs.readFileSync(path.join(here, 'deployment.mjs'))
  const digest = createHash('sha256').update(deployment).digest('hex')
  const handoff = await remote(`
import fs from 'node:fs';
import { generateKeyPairSync } from 'node:crypto';
try {
  if (process.getuid() !== 0) throw new Error();
  for (const dir of ['/opt/pedidos360', '/opt/pedidos360/private', '/opt/pedidos360/private/secrets']) {
    const s = fs.lstatSync(dir);
    if (!s.isDirectory() || s.isSymbolicLink() || s.uid !== 0 || (s.mode & 0o022)) throw new Error();
  }
  if (fs.readdirSync('/opt/pedidos360/private').join() !== 'secrets') throw new Error();
  fs.accessSync('/usr/bin/keytool');
  fs.accessSync('/usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts');
  const dir = fs.mkdtempSync('/opt/pedidos360/handoff-');
  fs.chmodSync(dir, 0o700);
  const keys = generateKeyPairSync('rsa', { modulusLength: 4096, publicKeyEncoding: { type: 'spki', format: 'pem' }, privateKeyEncoding: { type: 'pkcs8', format: 'pem' } });
  fs.writeFileSync(dir + '/key.pem', keys.privateKey, { flag: 'wx', mode: 0o600 });
  console.log(JSON.stringify({ dir, publicKey: keys.publicKey }));
} catch { console.error('Prevalidacion remota fallida; no se muestran detalles privados.'); process.exitCode = 1; }
`)
  if (!/^\/opt\/pedidos360\/handoff-[A-Za-z0-9]+$/.test(handoff.dir)) throw new Error('Ruta de handoff inesperada.')
  const encrypted = publicEncrypt({ key: handoff.publicKey, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(worker)).toString('base64')
  console.log('Transferencia cifrada preparada; el secreto no se incluye en comandos ni salidas.')
  const outcome = await remote(`
import fs from 'node:fs';
import { createHash, privateDecrypt, constants } from 'node:crypto';
import { pathToFileURL } from 'node:url';
const handoff = ${JSON.stringify(handoff.dir)};
try {
  const keyPath = handoff + '/key.pem';
  const keyStat = fs.lstatSync(keyPath);
  if (!keyStat.isFile() || keyStat.isSymbolicLink() || keyStat.uid !== 0 || (keyStat.mode & 0o077)) throw new Error();
  const worker = privateDecrypt({ key: fs.readFileSync(keyPath), padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(${JSON.stringify(encrypted)}, 'base64')).toString('utf8');
  const source = Buffer.from(${JSON.stringify(deployment.toString('base64'))}, 'base64');
  const digest = ${JSON.stringify(digest)};
  if (createHash('sha256').update(source).digest('hex') !== digest) throw new Error();
  const script = handoff + '/deployment.mjs';
  fs.writeFileSync(script, source, { flag: 'wx', mode: 0o600 });
  const { prepare, validateMaterial } = await import(pathToFileURL(script).href);
  const root = '/opt/pedidos360/private';
  const names = ['usuarios', 'restaurantes', 'productos', 'carrito', 'pedidos', 'pagos'];
  const snapshot = () => names.map(name => createHash('sha256').update(fs.readFileSync(root + '/secrets/' + name + '_db_password')).digest('hex'));
  const before = snapshot();
  prepare(root, { worker, cacerts: fs.realpathSync('/usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts'), reuseDatabaseSecrets: true });
  validateMaterial(root);
  if (JSON.stringify(before) !== JSON.stringify(snapshot())) throw new Error();
  // Remove only the two exact files created by this one-shot handoff.
  fs.unlinkSync(keyPath);
  fs.unlinkSync(script);
  fs.rmdirSync(handoff);
  console.log(JSON.stringify({ tlsValid: true, dbSecretsPreserved: true, workerInstalled: true, handoffRemoved: true }));
} catch { console.error('Preparacion fallida; conservar material privado. No repetir ni regenerar DB.'); process.exitCode = 1; }
`)
  console.log(JSON.stringify(outcome))
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.code ? 'Fallo local de archivos; salida omitida.' : error.message); process.exitCode = 1 })
}
