// Scoped Academy operations. No credentials or private keys are read locally.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const apiId = '65bce807i0'
const instance = 'i-0c1b0042eb32cf54e'
const account = '694507973514'
const appSg = 'sg-07fe92ded7a106177'
const vpc = 'vpc-025a41e372f550088'
const origin = `https://${apiId}.execute-api.us-east-1.amazonaws.com`
const fn = 'pedidos360-edge'
const awsArgs = ['--profile', 'pedidos360-lab', '--region', 'us-east-1', '--no-cli-pager', '--output', 'json']
function aws(args) {
  try {
    const output = execFileSync('aws', [...args, ...awsArgs], { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], timeout: 60000 })
    return output.trim() ? JSON.parse(output) : {}
  } catch (error) {
    const code = /\((\w+)\) when calling/.exec(error.stderr?.toString() ?? '')?.[1] ?? 'CLIError'
    throw new Error(`${args[0]} ${args[1]}: ${code}`)
  }
}
async function remote(command) {
  const result = aws(['ssm', 'send-command', '--instance-ids', instance, '--document-name', 'AWS-RunShellScript', '--parameters', JSON.stringify({ commands: [command], executionTimeout: ['900'] })])
  const id = result.Command.CommandId
  console.log('SSM:', id)
  for (let i = 0; i < 300; i++) {
    await new Promise(resolve => setTimeout(resolve, 3000))
    let item
    try { item = aws(['ssm', 'get-command-invocation', '--command-id', id, '--instance-id', instance]) }
    catch (e) { if (i < 3) continue; throw e }
    if (['Pending', 'InProgress', 'Delayed'].includes(item.Status)) continue
    if (item.Status !== 'Success') throw new Error(`SSM ${item.Status}: ${id}; inspect sanitized output before retry`)
    return item.StandardOutputContent
  }
  throw new Error(`SSM wait expired; do not repeat: ${id}`)
}
function nodeRemote(source) { return remote("/usr/local/bin/node --input-type=module <<'P360_NODE'\n" + source + '\nP360_NODE') }

async function main() {
  if (aws(['sts', 'get-caller-identity']).Account !== account) throw new Error('Wrong account')
  const ec2 = aws(['ec2', 'describe-instances', '--instance-ids', instance]).Reservations[0].Instances[0]
  if (ec2.State.Name !== 'running' || ec2.PrivateIpAddress !== '172.31.91.221' || ec2.VpcId !== vpc || !ec2.SecurityGroups.some(g => g.GroupId === appSg)) throw new Error('EC2 configuration changed')
  const action = process.argv[2]
  if (action === 'create-lambda' || action === 'resume-lambda') {
    // Stop if a prior creation exists; never overwrite an unknown deployment.
    const existing = aws(['lambda', 'list-functions']).Functions.find(f => f.FunctionName === fn)
    if (existing) throw new Error('Function already exists; inspect instead of recreating')
    const cert = (await nodeRemote("import fs from 'node:fs'; console.log(fs.readFileSync('/opt/pedidos360/private/tls/bff.crt', 'utf8'));" )).trim()
    if (!/^-----BEGIN CERTIFICATE-----[\s\S]+-----END CERTIFICATE-----$/.test(cert)) throw new Error('Invalid public certificate')
    const groups = aws(['ec2', 'describe-security-groups', '--filters', `Name=vpc-id,Values=${vpc}`, 'Name=group-name,Values=pedidos360-edge-sg']).SecurityGroups
    if (action === 'create-lambda' && groups.length) throw new Error('Edge SG already exists; inspect partial deployment')
    if (action === 'resume-lambda' && (groups.length !== 1 || groups[0].GroupId !== 'sg-04ffbc3689a1d808e')) throw new Error('Unexpected resume SG')
    const sg = action === 'resume-lambda' ? groups[0].GroupId : aws(['ec2', 'create-security-group', '--group-name', 'pedidos360-edge-sg', '--description', 'Pedidos360 Lambda private access to EC2 only', '--vpc-id', vpc]).GroupId
    console.log('Edge SG:', sg)
    if (action === 'create-lambda') {
    aws(['ec2', 'revoke-security-group-egress', '--group-id', sg, '--ip-permissions', JSON.stringify([{ IpProtocol: '-1', IpRanges: [{ CidrIp: '0.0.0.0/0' }] }])])
    for (const port of [8080, 8443]) {
      aws(['ec2', 'authorize-security-group-egress', '--group-id', sg, '--ip-permissions', JSON.stringify([{ IpProtocol: 'tcp', FromPort: port, ToPort: port, UserIdGroupPairs: [{ GroupId: appSg }] }])])
      aws(['ec2', 'authorize-security-group-ingress', '--group-id', appSg, '--ip-permissions', JSON.stringify([{ IpProtocol: 'tcp', FromPort: port, ToPort: port, UserIdGroupPairs: [{ GroupId: sg, Description: 'Pedidos360 private Lambda only' }] }])])
    }
    }
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pedidos360-edge-'))
    fs.copyFileSync(path.join(here, 'index.mjs'), path.join(dir, 'index.mjs'))
    fs.writeFileSync(path.join(dir, 'bff.crt'), cert, { flag: 'wx' })
    // Generated deployment artifact contains only source and PUBLIC certificate.
    execFileSync('jar', ['--create', '--no-manifest', '--file', path.join(dir, 'function.zip'), '-C', dir, 'index.mjs', '-C', dir, 'bff.crt'], { windowsHide: true })
    const result = aws(['lambda', 'create-function', '--function-name', fn, '--runtime', 'nodejs22.x', '--handler', 'index.handler', '--role', `arn:aws:iam::${account}:role/LabRole`, '--timeout', '25', '--memory-size', '256', '--zip-file', 'fileb://' + path.join(dir, 'function.zip'), '--vpc-config', JSON.stringify({ SubnetIds: [ec2.SubnetId], SecurityGroupIds: [sg] }), '--environment', JSON.stringify({ Variables: { EC2_PRIVATE_IP: ec2.PrivateIpAddress, API_ID: apiId, PUBLIC_ORIGIN: origin } }), '--tags', 'Project=Pedidos360'])
    console.log(JSON.stringify({ function: result.FunctionName, state: result.State, codeHash: result.CodeSha256 }))
  } else if (action === 'configure-api') {
    const gateway = aws(['apigatewayv2', 'get-api', '--api-id', apiId])
    if (gateway.Name !== 'pedidos360-public' || gateway.ProtocolType !== 'HTTP') throw new Error('Unexpected API')
    for (const type of ['routes', 'integrations', 'authorizers', 'stages']) {
      if (aws(['apigatewayv2', 'get-' + type, '--api-id', apiId]).Items?.length) throw new Error('API already configured; inspect instead of duplicating')
    }
    const lambda = aws(['lambda', 'get-function-configuration', '--function-name', fn])
    if (lambda.State !== 'Active') throw new Error('Lambda not active yet')
    const authorizer = aws(['apigatewayv2', 'create-authorizer', '--api-id', apiId, '--name', 'pedidos360-entra', '--authorizer-type', 'JWT', '--identity-source', '$request.header.Authorization', '--jwt-configuration', JSON.stringify({ Audience: ['13c0f63f-2007-41c4-8d9f-02640b8a1886'], Issuer: 'https://login.microsoftonline.com/a048ca4e-cd7f-4a01-a43e-cb4deccf1ff2/v2.0' })]).AuthorizerId
    const integration = aws(['apigatewayv2', 'create-integration', '--api-id', apiId, '--integration-type', 'AWS_PROXY', '--integration-uri', lambda.FunctionArn, '--payload-format-version', '2.0', '--timeout-in-millis', '28000']).IntegrationId
    for (const route of ['$default', 'ANY /api', 'ANY /api/{proxy+}']) {
      aws(['apigatewayv2', 'create-route', '--api-id', apiId, '--route-key', route, '--target', 'integrations/' + integration, ...(route === '$default' ? ['--authorization-type', 'NONE'] : ['--authorization-type', 'JWT', '--authorizer-id', authorizer, '--authorization-scopes', 'access_as_user'])])
    }
    aws(['lambda', 'add-permission', '--function-name', fn, '--statement-id', 'pedidos360-http-api', '--action', 'lambda:InvokeFunction', '--principal', 'apigateway.amazonaws.com', '--source-account', account, '--source-arn', `arn:aws:execute-api:us-east-1:${account}:${apiId}/*`])
    aws(['apigatewayv2', 'create-stage', '--api-id', apiId, '--stage-name', '$default', '--auto-deploy', '--default-route-settings', JSON.stringify({ ThrottlingBurstLimit: 10, ThrottlingRateLimit: 5 })])
    console.log('API routes and Entra JWT configured: ' + origin)
  } else if (action === 'start-ec2') {
    console.log(await remote(`set -eu
cd /opt/pedidos360/deploy-edge
/usr/local/bin/node deployment.mjs check-linux .env.deploy
docker compose --env-file .env.deploy -f compose.yml -f compose.edge.yml up -d --no-build --pull never >/dev/null 2>&1
docker compose --env-file .env.deploy -f compose.yml -f compose.edge.yml ps --format '{{.Service}} {{.State}} {{.Health}}'
`))
  } else if (action === 'refresh-overlay') {
    const overlay = fs.readFileSync(path.join(here, '..', 'compose.edge.yml')).toString('base64')
    console.log(await nodeRemote(`
import fs from 'node:fs'; import {execFileSync} from 'node:child_process';
const root='/opt/pedidos360/deploy-edge';
const target=root+'/compose.edge.yml'; const stat=fs.lstatSync(target);
if (!stat.isFile() || stat.isSymbolicLink() || stat.uid!==0) throw new Error('Unsafe overlay');
fs.writeFileSync(target,Buffer.from('${overlay}','base64'),{mode:0o600});
try {
 execFileSync('/usr/local/bin/node',[root+'/deployment.mjs','check-linux',root+'/.env.deploy'],{stdio:'pipe'});
 execFileSync('docker',['compose','--env-file',root+'/.env.deploy','-f',root+'/compose.yml','-f',target,'up','-d','--no-deps','--no-build','--pull','never','frontend'],{stdio:'pipe'});
 console.log('OK: frontend private bridge applied');
} catch {console.error('Overlay apply failed; inspect state');process.exitCode=1;}
`))
  } else if (action === 'inspect-network') {
    console.log(await remote(`set -eu
docker port pedidos360-aws-frontend-1
curl --connect-timeout 3 --max-time 5 -s -o /dev/null -w 'frontend HTTP %{http_code}\\n' http://172.31.91.221:8080/ || true
docker inspect pedidos360-aws-frontend-1 --format '{{json .NetworkSettings.Ports}}'
docker network inspect pedidos360-aws_front --format '{{.Internal}}'
`))
  } else if (action === 'verify-ec2') {
    const source = fs.readFileSync(path.join(here, 'index.mjs')).toString('base64')
    console.log(await nodeRemote(`
import fs from 'node:fs';
const { createHandler } = await import('data:text/javascript;base64,${source}');
const config = { host: '172.31.91.221', apiId: '${apiId}', origin: '${origin}', ca: fs.readFileSync('/opt/pedidos360/private/tls/bff.crt', 'utf8') };
const handler = createHandler(config);
const event = { version: '2.0', rawPath: '/', rawQueryString: '', headers: {}, requestContext: {apiId: '${apiId}', http: {method: 'GET'}} };
const web = await handler(event);
if (web.statusCode !== 200 || !Buffer.from(web.body,'base64').toString().includes('<html')) throw new Error('Static frontend not ready: ' + web.statusCode);
event.rawPath = '/api/usuarios/me';
event.headers = {authorization: 'Bearer intentionally-invalid'};
event.requestContext.authorizer = {jwt: {claims: {sub: 'invalid-probe'}}};
const api = await handler(event);
if (api.statusCode !== 401) throw new Error('BFF TLS/auth probe: ' + api.statusCode);
const untrusted = createHandler({...config, ca: fs.readFileSync('/opt/pedidos360/private/tls/rds-ca.pem', 'utf8')});
if ((await untrusted(event)).statusCode !== 502) throw new Error('Untrusted certificate was not rejected');
console.log('OK: static 200, BFF verified TLS and invalid JWT 401, wrong CA rejected');
`))
  } else if (action === 'probe-lambda') {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pedidos360-probes-'))
    const event = {version:'2.0',rawPath:'/api/usuarios/me',rawQueryString:'',headers:{authorization:'Bearer intentionally-invalid'},requestContext:{apiId,http:{method:'GET'},authorizer:{jwt:{claims:{sub:'invalid-probe'}}}}}
    const resultFile=path.join(dir,'result.json')
    const result=aws(['lambda','invoke','--function-name',fn,'--cli-binary-format','raw-in-base64-out','--payload',JSON.stringify(event),resultFile])
    const response=JSON.parse(fs.readFileSync(resultFile,'utf8'))
    if(result.FunctionError || response.statusCode!==401) throw new Error('Lambda to BFF TLS/auth probe failed: '+response.statusCode)
    console.log('OK: real Lambda private connection with verified BFF TLS; invalid bearer rejected 401')
  } else if (action === 'audit-databases') {
    console.log(await remote(`python3 - <<'P360_PY'
import psycopg2, pathlib, json, urllib.request, urllib.parse, base64
from psycopg2 import sql
root=pathlib.Path('/opt/pedidos360/private')
try:
    for name in ['usuarios','restaurantes','productos','carrito','pedidos','pagos']:
        with psycopg2.connect(host='pedidos360-db.cslnvffbzaw9.us-east-1.rds.amazonaws.com',port=5432,dbname='pedidos360_'+name,user='pedidos360_'+name,password=(root/'secrets'/(name+'_db_password')).read_text(),sslmode='verify-full',sslrootcert=str(root/'tls'/'rds-ca.pem'),connect_timeout=10) as conn:
            conn.set_session(readonly=True)
            with conn.cursor() as cursor:
                cursor.execute(sql.SQL('SELECT count(*), bool_and(success) FROM {}.flyway_schema_history').format(sql.Identifier(name)))
                count, success=cursor.fetchone()
                if not success or count<1: raise RuntimeError('Migrations')
                cursor.execute('SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()')
                if cursor.fetchone()[0] is not True: raise RuntimeError('TLS')
                print('OK migrations/TLS: '+name+' ('+str(count)+')',flush=True)
    form=urllib.parse.urlencode({'grant_type':'client_credentials','client_id':'5edfad3c-8147-4a6f-bb7d-fabd3c4ad8f6','client_secret':(root/'secrets'/'worker_secret').read_text(),'scope':'api://13c0f63f-2007-41c4-8d9f-02640b8a1886/.default'}).encode()
    req=urllib.request.Request('https://login.microsoftonline.com/a048ca4e-cd7f-4a01-a43e-cb4deccf1ff2/oauth2/v2.0/token',data=form)
    with urllib.request.urlopen(req,timeout=20) as response: token=json.load(response)['access_token']
    payload=token.split('.')[1]
    claims=json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))
    if claims.get('aud')!='13c0f63f-2007-41c4-8d9f-02640b8a1886' or 'Pedidos.Confirmar' not in claims.get('roles',[]): raise RuntimeError('Worker claims')
    print('OK: Entra issued worker token for API with Pedidos.Confirmar; token not logged')
except Exception as e:
    print('Audit failed: '+type(e).__name__+'; private details omitted')
    raise SystemExit(1)
P360_PY`))
  } else if (action === 'inspect-ec2') {
    console.log(await remote('set -eu\ncommand -v aws || true\ndocker ps --format "{{.Names}} {{.Status}}"\ndf -h /\nfree -m\n'))
  } else if (action === 'pull-ec2') {
    console.log(await remote(`set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq python3-boto3 >/dev/null
python3 - <<'P360_PY'
import boto3, base64, subprocess, tempfile, json, os
try:
    sts = boto3.client('sts', region_name='us-east-1')
    if sts.get_caller_identity()['Account'] != '${account}': raise RuntimeError()
    entry = boto3.client('ecr', region_name='us-east-1').get_authorization_token(registryIds=['${account}'])['authorizationData'][0]
    user, password = base64.b64decode(entry['authorizationToken']).decode().split(':', 1)
    registry = '${account}.dkr.ecr.us-east-1.amazonaws.com'
    if user != 'AWS' or entry['proxyEndpoint'] != 'https://' + registry: raise RuntimeError()
    with tempfile.TemporaryDirectory(prefix='pedidos360-ecr-') as directory:
        env = dict(os.environ, DOCKER_CONFIG=directory)
        subprocess.run(['docker','login','--username','AWS','--password-stdin',registry],input=password.encode(),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,env=env)
        password = None
        for name in ['usuarios','restaurantes','productos','carrito','pedidos','pagos','bff','frontend']:
            image = registry + '/pedidos360-' + name + ':5d4601905c870efa2e2ca9b3e850d6ef262f4a02'
            subprocess.run(['docker','pull',image],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,env=env)
            config = json.loads(subprocess.check_output(['docker','image','inspect',image],env=env))[0]
            expected = '101' if name == 'frontend' else '10001:10001'
            if config['Architecture'] != 'amd64' or not config['Config']['User'].startswith(expected): raise RuntimeError()
            print('OK image: ' + name, flush=True)
except Exception as e:
    print('Pull failed: ' + type(e).__name__ + '; no credentials logged')
    raise SystemExit(1)
P360_PY`))
  } else if (action === 'stage-ec2') {
    const config = {
      IMAGE_REGISTRY: `${account}.dkr.ecr.us-east-1.amazonaws.com`, IMAGE_TAG: '5d4601905c870efa2e2ca9b3e850d6ef262f4a02',
      FRONTEND_ORIGIN: origin, PUBLIC_API_BASE_URL: origin + '/api',
      ENTRA_TENANT_ID: 'a048ca4e-cd7f-4a01-a43e-cb4deccf1ff2', ENTRA_API_CLIENT_ID: '13c0f63f-2007-41c4-8d9f-02640b8a1886',
      ENTRA_FRONTEND_CLIENT_ID: '5388c832-53e4-45c8-a3be-6875b69e1a51', PAGOS_WORKER_CLIENT_ID: '5edfad3c-8147-4a6f-bb7d-fabd3c4ad8f6',
      RDS_HOST: 'pedidos360-db.cslnvffbzaw9.us-east-1.rds.amazonaws.com',
      AWS_TLS_DIR: '/opt/pedidos360/private/tls', AWS_SECRETS_DIR: '/opt/pedidos360/private/secrets', EC2_PRIVATE_IP: ec2.PrivateIpAddress,
    }
    const files = Object.fromEntries(['deployment.mjs', 'compose.yml', 'compose.edge.yml'].map(name => [name, fs.readFileSync(path.join(here, '..', name)).toString('base64')]))
    files['.env.deploy'] = Buffer.from(Object.entries(config).map(([k, v]) => `${k}=${v}`).join('\n') + '\n').toString('base64')
    console.log(await nodeRemote(`
import fs from 'node:fs'; import { pathToFileURL } from 'node:url';
const root = '/opt/pedidos360/deploy-edge';
if (fs.existsSync(root)) throw new Error('Deployment directory exists; inspect before replacing');
const parent = fs.lstatSync('/opt/pedidos360');
if (!parent.isDirectory() || parent.isSymbolicLink() || parent.uid !== 0 || (parent.mode & 0o022)) throw new Error('Unsafe parent');
fs.mkdirSync(root, {mode: 0o700});
for (const [name, value] of Object.entries(${JSON.stringify(files)})) fs.writeFileSync(root + '/' + name, Buffer.from(value, 'base64'), {flag: 'wx', mode: 0o600});
try {
 const {preflight} = await import(pathToFileURL(root + '/deployment.mjs').href);
 preflight(root + '/.env.deploy', 'keytool', true);
 console.log('OK: Linux material and private Compose preflight');
} catch(e) {console.error('Preflight failed: ' + (e.code || e.message)); process.exitCode = 1;}
`))
  } else throw new Error('Unknown action')
}
main().catch(e => { console.error(e.message); process.exitCode = 1 })
