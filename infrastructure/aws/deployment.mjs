import fs from 'node:fs'
import path from 'node:path'
import { randomBytes, X509Certificate } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

export const services = ['bff', 'usuarios', 'restaurantes', 'productos', 'carrito', 'pedidos', 'pagos']
const here = path.dirname(fileURLToPath(import.meta.url))
const fail = message => { throw new Error(message) }
function run(command, args, env = process.env) {
  const r = spawnSync(command, args, { env, encoding: 'utf8', windowsHide: true })
  // Never echo subprocess output: keytool/config tools may include supplied data.
  if (r.status !== 0) fail(`Fallo ${path.basename(command)}; revisar instalacion, archivos o permisos (salida omitida).`)
  return r.stdout
}
function protect(dir) {
  if (process.platform === 'win32') {
    const sid = run('powershell.exe', ['-NoProfile', '-Command', '[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value']).trim()
    if (!/^S-1-[\d-]+$/.test(sid)) fail('No se pudo resolver identidad Windows.')
    run('icacls.exe', [dir, '/inheritance:r', '/grant:r', `*${sid}:(OI)(CI)F`, '*S-1-5-18:(OI)(CI)F'])
  } else fs.chmodSync(dir, 0o700)
}
function regular(file) {
  const stat = fs.lstatSync(file)
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size === 0) fail(`Archivo ausente, vacio o no regular: ${path.basename(file)}`)
  return stat
}
function secret(file) {
  const stat = regular(file)
  if (process.platform !== 'win32' && (stat.mode & 0o077)) fail(`Permisos demasiado abiertos: ${path.basename(file)}`)
  const value = fs.readFileSync(file, 'utf8')
  if (value.length < 12 || /[\r\n\0]/.test(value) || value.trim() !== value) fail(`Secreto invalido: ${path.basename(file)}`)
  return value
}
function toolEnv(password) { return { ...process.env, P360_TLS_PASSWORD: password } }
function certificate(tool, file, alias, env, password = 'P360_TLS_PASSWORD') {
  return new X509Certificate(run(tool, ['-exportcert', '-rfc', '-alias', alias, '-keystore', file, '-storepass:env', password], env))
}
function validDate(cert, days = 7) {
  if (Date.parse(cert.validFrom) > Date.now() || Date.parse(cert.validTo) < Date.now() + days * 86400000) fail('Certificado no vigente o vence en menos de siete dias.')
}

function databaseOnlyMaterial(root, worker) {
  const secrets = path.join(root, 'secrets')
  for (const dir of [root, secrets]) {
    const stat = fs.lstatSync(dir)
    if (!stat.isDirectory() || stat.isSymbolicLink()) fail('Directorio privado no regular.')
    if (process.platform !== 'win32' && (stat.mode & 0o077)) fail('Directorio privado demasiado abierto.')
  }
  const expected = services.slice(1).map(name => `${name}_db_password`).sort()
  if (fs.readdirSync(root).join() !== 'secrets' ||
      JSON.stringify(fs.readdirSync(secrets).sort()) !== JSON.stringify(expected)) {
    fail('complete requiere solamente los seis secretos DB; no sobrescribe material completo ni parcial.')
  }
  const values = expected.map(name => secret(path.join(secrets, name)))
  if (values.some(value => !/^[0-9a-f]{64}$/.test(value))) fail('Se requieren secretos DB hexadecimales de 64 caracteres.')
  if (new Set([...values, worker]).size !== 7) fail('No reutilizar secretos entre servicios.')
}

export function prepare(root, { worker = process.env.PAGOS_WORKER_CLIENT_SECRET, keytool = 'keytool', cacerts = process.env.P360_CACERTS, reuseDatabaseSecrets = false } = {}) {
  root = path.resolve(root)
  if (!worker || worker.length < 12 || /[\r\n\0]/.test(worker) || worker.trim() !== worker) fail('Define PAGOS_WORKER_CLIENT_SECRET en el entorno privado, no por argumento.')
  if (!cacerts) fail('Define P360_CACERTS con la ruta lib/security/cacerts de tu JDK 21.')
  regular(cacerts)
  if (reuseDatabaseSecrets) databaseOnlyMaterial(root, worker)
  else {
    if (fs.existsSync(root)) fail('El directorio de salida ya existe; no se sobrescribe ni rota automaticamente.')
    fs.mkdirSync(root, { mode: 0o700 })
  }
  protect(root)
  for (const dir of ['tls', 'secrets']) {
    if (!(reuseDatabaseSecrets && dir === 'secrets')) fs.mkdirSync(path.join(root, dir), { mode: 0o700 })
    protect(path.join(root, dir))
  }
  const tls = path.join(root, 'tls')
  const secrets = path.join(root, 'secrets')
  const password = randomBytes(32).toString('hex')
  const env = { ...toolEnv(password), P360_TRUST_PASSWORD: 'changeit' }
  const write = (name, value) => fs.writeFileSync(path.join(secrets, name), value, { flag: 'wx', mode: 0o400 })
  write('tls_password', password)
  write('worker_secret', worker)
  if (!reuseDatabaseSecrets) {
    for (const name of services.slice(1)) write(`${name}_db_password`, randomBytes(32).toString('hex'))
  }
  const trust = path.join(tls, 'truststore.p12')
  run(keytool, ['-importkeystore', '-noprompt', '-srckeystore', cacerts, '-srcstorepass', 'changeit', '-destkeystore', trust, '-deststoretype', 'PKCS12', '-deststorepass', 'changeit'], env)
  for (const name of services) {
    const file = path.join(tls, `${name}.p12`)
    run(keytool, ['-genkeypair', '-alias', name, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '30', '-dname', `CN=${name}`, '-ext', `SAN=dns:${name},dns:localhost,ip:127.0.0.1`, '-ext', 'EKU=serverAuth', '-storetype', 'PKCS12', '-keystore', file, '-storepass:env', 'P360_TLS_PASSWORD', '-keypass:env', 'P360_TLS_PASSWORD', '-noprompt'], env)
    const crt = path.join(tls, `${name}.crt`)
    fs.writeFileSync(crt, run(keytool, ['-exportcert', '-rfc', '-alias', name, '-keystore', file, '-storepass:env', 'P360_TLS_PASSWORD'], env), { flag: 'wx', mode: 0o400 })
    run(keytool, ['-importcert', '-noprompt', '-alias', `pedidos360-${name}`, '-file', crt, '-keystore', trust, '-storepass', 'changeit'], env)
    fs.chmodSync(file, 0o400)
  }
  fs.chmodSync(trust, 0o400)
  return root
}

export function validateMaterial(root, keytool = 'keytool') {
  const tls = path.join(root, 'tls')
  const secrets = path.join(root, 'secrets')
  for (const dir of [root, tls, secrets]) {
    const stat = fs.lstatSync(dir)
    if (!stat.isDirectory() || stat.isSymbolicLink()) fail('Directorio privado no regular.')
    if (process.platform !== 'win32' && (stat.mode & 0o077)) fail('Directorio privado demasiado abierto.')
  }
  const password = secret(path.join(secrets, 'tls_password'))
  const values = [password, secret(path.join(secrets, 'worker_secret'))]
  for (const name of services.slice(1)) values.push(secret(path.join(secrets, `${name}_db_password`)))
  if (new Set(values).size !== values.length) fail('No reutilizar secretos entre servicios.')
  const env = { ...toolEnv(password), P360_TRUST_PASSWORD: 'changeit' }
  regular(path.join(tls, 'truststore.p12'))
  for (const name of services) {
    const file = path.join(tls, `${name}.p12`)
    regular(file)
    if (process.platform !== 'win32' && (fs.statSync(file).mode & 0o077)) fail('Clave privada con permisos abiertos.')
    const c = certificate(keytool, file, name, env)
    validDate(c)
    if (!c.checkHost(name) || !c.checkHost('localhost') || !c.checkIP('127.0.0.1')) fail('SAN incorrecto.')
    if (!c.keyUsage?.includes('1.3.6.1.5.5.7.3.1')) fail('Falta uso serverAuth.')
    regular(path.join(tls, `${name}.crt`))
    const exported = new X509Certificate(fs.readFileSync(path.join(tls, `${name}.crt`)))
    const trusted = certificate(keytool, path.join(tls, 'truststore.p12'), `pedidos360-${name}`, env, 'P360_TRUST_PASSWORD')
    if (c.fingerprint256 !== trusted.fingerprint256 || c.fingerprint256 !== exported.fingerprint256) fail('Certificado y truststore no coinciden.')
  }
}

export function readConfig(file) {
  regular(file)
  const values = {}
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue
    const match = /^([A-Z_]+)=([^\r\n]*)$/.exec(line)
    if (!match || Object.hasOwn(values, match[1])) fail('Formato o clave duplicada en configuracion; usar CLAVE=valor sin comillas.')
    values[match[1]] = match[2]
  }
  const keys = ['IMAGE_REGISTRY', 'IMAGE_TAG', 'FRONTEND_ORIGIN', 'PUBLIC_API_BASE_URL', 'ENTRA_TENANT_ID', 'ENTRA_API_CLIENT_ID', 'ENTRA_FRONTEND_CLIENT_ID', 'PAGOS_WORKER_CLIENT_ID', 'RDS_HOST', 'AWS_TLS_DIR', 'AWS_SECRETS_DIR']
  if (Object.keys(values).some(k => !keys.includes(k))) fail('Variable inesperada en configuracion publica.')
  for (const key of keys) if (!values[key]) fail(`Falta ${key}.`)
  for (const key of ['ENTRA_TENANT_ID', 'ENTRA_API_CLIENT_ID', 'ENTRA_FRONTEND_CLIENT_ID', 'PAGOS_WORKER_CLIENT_ID']) {
    if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(values[key])) fail(`UUID invalido: ${key}`)
  }
  if (!/^[0-9a-f]{40}$/.test(values.IMAGE_TAG)) fail('IMAGE_TAG debe ser SHA completo.')
  if (!/^\d{12}\.dkr\.ecr\.us-east-1\.amazonaws\.com$/.test(values.IMAGE_REGISTRY)) fail('Se requiere registry ECR privado de us-east-1.')
  if (!/^[a-z0-9.-]+\.us-east-1\.rds\.amazonaws\.com$/.test(values.RDS_HOST)) fail('Usar endpoint RDS de us-east-1.')
  for (const key of ['FRONTEND_ORIGIN', 'PUBLIC_API_BASE_URL']) {
    let u
    try { u = new URL(values[key]) } catch { fail(`URL invalida: ${key}`) }
    if (u.protocol !== 'https:' || u.username || u.password || u.search || u.hash || u.hostname === 'localhost' || u.hostname.endsWith('.test') || u.hostname.endsWith('.example')) fail(`URL HTTPS publica requerida: ${key}`)
    if (key === 'FRONTEND_ORIGIN' && values[key] !== u.origin) fail('FRONTEND_ORIGIN sin ruta ni barra final.')
  }
  return values
}

export function preflight(file, keytool = 'keytool', linux = false) {
  const c = readConfig(file)
  const base = path.dirname(path.resolve(file))
  const tls = path.resolve(base, c.AWS_TLS_DIR)
  const secrets = path.resolve(base, c.AWS_SECRETS_DIR)
  if (path.basename(tls) !== 'tls' || path.basename(secrets) !== 'secrets' || path.dirname(tls) !== path.dirname(secrets)) fail('tls y secrets deben ser hermanos dentro del directorio privado.')
  validateMaterial(path.dirname(tls), keytool)
  regular(path.join(tls, 'rds-ca.pem'))
  const pem = fs.readFileSync(path.join(tls, 'rds-ca.pem'), 'utf8')
  const certs = pem.match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g)
  if (!certs?.length) fail('Bundle RDS invalido.')
  for (const value of certs) {
    const cert = new X509Certificate(value)
    validDate(cert)
    if (!cert.ca) fail('Bundle RDS contiene un certificado no CA.')
  }
  if (linux) {
    if (process.platform !== 'linux' || process.getuid() !== 0) fail('Validacion EC2 requiere Linux con sudo.')
    for (const dir of [path.dirname(tls), tls, secrets]) if (fs.statSync(dir).uid !== 0) fail('Directorios privados EC2 requieren propietario root.')
    for (const dir of [secrets, tls]) for (const name of fs.readdirSync(dir)) {
      const stat = regular(path.join(dir, name))
      if (stat.uid !== 10001 || !(stat.mode & 0o400) || (stat.mode & 0o077)) fail('Archivos privados requieren propietario 10001 y modo 0400/0600.')
    }
  }
  run('docker', ['compose', '--env-file', path.resolve(file), '-f', path.join(here, 'compose.yml'), 'config', '--quiet'], { ...process.env, ...c, AWS_TLS_DIR: tls, AWS_SECRETS_DIR: secrets })
  return c
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [action, target] = process.argv.slice(2)
    if (!target) fail('Uso: node deployment.mjs prepare <directorio-nuevo> | complete <directorio-con-secretos-DB> | check <.env.deploy> | check-linux <.env.deploy> | up <.env.deploy>')
    if (action === 'prepare') prepare(target)
    else if (action === 'complete') prepare(target, { reuseDatabaseSecrets: true })
    else if (action === 'check' || action === 'check-linux' || action === 'up') {
      const c = preflight(target, 'keytool', action !== 'check')
      if (action === 'up') {
        const base = path.dirname(path.resolve(target))
        run('docker', ['compose', '--env-file', path.resolve(target), '-f', path.join(here, 'compose.yml'), 'up', '-d', '--no-build'], { ...process.env, ...c, AWS_TLS_DIR: path.resolve(base, c.AWS_TLS_DIR), AWS_SECRETS_DIR: path.resolve(base, c.AWS_SECRETS_DIR) })
      }
    }
    else fail('Accion desconocida.')
    console.log('OK. Sin mostrar secretos. Esto no valida conectividad RDS/Entra ni despliega recursos.')
  } catch (e) {
    // Filesystem errors can contain paths; keep only controlled validation messages.
    console.error(e.code ? 'Fallo de archivo o permisos; comprobar rutas privadas.' : e.message)
    process.exitCode = 1
  }
}
