"""Fixed local-only project. No AWS API, operational broker or destructive reset."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import ssl
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent
PRIVATE = ROOT / '.local/private'
PROJECT = 'p360-cluster73-demo'
NODES = {'rabbit@rabbit-demo-1', 'rabbit@rabbit-demo-2'}
IMAGE = 'rabbitmq:4.1.8-management-alpine@sha256:b586ea9784425774ac56f3d4919e4f723edb3287ef8a6c4c8f0df8fe569747c3'
TOPOLOGY = json.loads((ROOT / 'topology.json').read_text(encoding='utf-8'))


def run(*args):
    return subprocess.check_output(list(args), text=True).strip()


def compose(*args):
    return ['docker', 'compose', '-p', PROJECT, '-f', str(ROOT / 'compose.yml'), *args]


def secret(name):
    if name not in {'ADMIN_PASSWORD', 'PUBLISHER_PASSWORD', 'CONSUMER_PASSWORD', 'ERLANG_COOKIE'}:
        raise ValueError('Unknown demo secret')
    value = (PRIVATE / name).read_text(encoding='utf-8').strip()
    if len(value) < 32 or '\n' in value:
        raise ValueError('Demo credentials incomplete')
    return value


def initialized():
    local_docker()
    if (PRIVATE / 'LOCAL_SYNTHETIC_CLUSTER_73').read_text().strip() != 'LOCAL_SYNTHETIC_CLUSTER_73':
        raise ValueError('Not a synthetic local fixture')
    values = [secret(n) for n in ['ADMIN_PASSWORD', 'PUBLISHER_PASSWORD', 'CONSUMER_PASSWORD', 'ERLANG_COOKIE']]
    if len(set(values)) != 4:
        raise ValueError('Demo identities must be independent')
    context = ssl.create_default_context(cafile=str(PRIVATE / 'tls/ca.pem'))
    context.load_cert_chain(str(PRIVATE / 'tls/server.pem'), str(PRIVATE / 'tls/server-key.pem'))


def local_docker():
    if os.environ.get('DOCKER_HOST'):
        raise ValueError('DOCKER_HOST overrides prohibited')
    context = json.loads(run('docker', 'context', 'inspect'))[0]
    endpoint = context['Endpoints']['docker']['Host']
    if not endpoint.startswith(('npipe://', 'unix://')):
        raise ValueError('Local Docker socket required; remote contexts prohibited')


def init():
    if PRIVATE.exists():
        initialized()
        print('Existing local fixture validated; no secret overwritten')
        return
    openssl = os.environ.get('DEMO73_OPENSSL', 'openssl')
    # Fail before generating a partial fixture when tooling is absent.
    subprocess.run([openssl, 'version'], check=True, stdout=subprocess.DEVNULL)
    PRIVATE.mkdir(parents=True, mode=0o700)
    tls = PRIVATE / 'tls'; tls.mkdir(mode=0o700)
    for name in ['ADMIN_PASSWORD', 'PUBLISHER_PASSWORD', 'CONSUMER_PASSWORD', 'ERLANG_COOKIE']:
        file = PRIVATE / name
        file.write_text(secrets.token_hex(32), encoding='utf-8'); file.chmod(0o600)
    (PRIVATE / 'LOCAL_SYNTHETIC_CLUSTER_73').write_text('LOCAL_SYNTHETIC_CLUSTER_73')
    ext = tls / 'server.ext'
    ext.write_text('subjectAltName=DNS:rabbit-demo-1,DNS:rabbit-demo-2,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n')
    def cert(*args):
        subprocess.run([openssl, *args], cwd=tls, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    cert('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'ca-key.pem',
         '-out', 'ca.pem', '-days', '14', '-subj', '/CN=LOCAL-SYNTHETIC-73-CA')
    cert('req', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'server-key.pem',
         '-out', 'server.csr', '-subj', '/CN=rabbit-demo-1')
    cert('x509', '-req', '-in', 'server.csr', '-CA', 'ca.pem', '-CAkey', 'ca-key.pem',
         '-CAcreateserial', '-out', 'server.pem', '-days', '14', '-extfile', 'server.ext')
    for p in tls.iterdir(): p.chmod(0o600)
    initialized()
    print('Synthetic local credentials and 14-day CA created under ignored .local only')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Redirects prohibited')


class Api:
    def __init__(self, node=1, user='demo73-admin', password=None):
        if node not in (1, 2) or user not in ('demo73-admin', 'demo73-publisher', 'demo73-consumer'):
            raise ValueError('Fixed local targets only')
        password = password or secret(user.replace('demo73-', '').upper() + '_PASSWORD')
        self.auth = 'Basic ' + base64.b64encode((user + ':' + password).encode()).decode()
        self.base = f'https://127.0.0.1:{15782 + node}/api/'
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(PRIVATE / 'tls/ca.pem'))))

    def call(self, method, path, data=None):
        if method not in ('GET', 'PUT', 'POST') or '://' in path or path.startswith('/') or '..' in path:
            raise ValueError('Unsafe demo request')
        body = None if data is None else json.dumps(data).encode()
        request = urllib.request.Request(self.base + path, data=body, method=method,
            headers={'Authorization': self.auth, 'Content-Type': 'application/json'})
        with self.opener.open(request, timeout=8) as response:
            value = response.read()
            return json.loads(value) if value else None


def wait_for(check, timeout=90):
    end = time.monotonic() + timeout
    while True:
        try:
            result = check()
            if result: return result
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        if time.monotonic() >= end: raise TimeoutError('Demo condition not reached')
        time.sleep(1)


def members():
    nodes = Api().call('GET', 'nodes')
    if {n['name'] for n in nodes} != NODES:
        raise ValueError('Unexpected cluster membership')
    return {n['name'] for n in nodes if n.get('running')}


def isolation():
    model = json.loads(run(*compose('config', '--format', 'json')))
    if set(model['services']) != {'rabbit-demo-1', 'rabbit-demo-2'}:
        raise ValueError('Unexpected services')
    ids = run(*compose('ps', '-a', '-q')).splitlines()
    if len(ids) != 2: raise ValueError('Exactly two demo containers required')
    mounts = []
    for item in json.loads(run('docker', 'inspect', *ids)):
        if item['Config']['Labels'].get('com.docker.compose.project') != PROJECT:
            raise ValueError('Foreign container')
        if item['Config']['Image'] != IMAGE: raise ValueError('Unapproved image')
        if set(item['NetworkSettings']['Networks']) != {PROJECT, PROJECT + '-access'}: raise ValueError('Foreign network')
        for port in item['HostConfig']['PortBindings'].values():
            if any(v['HostIp'] != '127.0.0.1' for v in port): raise ValueError('Public port')
        volumes = [m for m in item['Mounts'] if m['Type'] == 'volume']
        if len(volumes) != 1 or volumes[0]['Name'] not in {PROJECT + '_node1', PROJECT + '_node2'}:
            raise ValueError('Foreign volume')
        mounts.append(volumes[0]['Name'])
    if len(set(mounts)) != 2: raise ValueError('Shared data volume')
    network = json.loads(run('docker', 'network', 'inspect', PROJECT))[0]
    if not network['Internal'] or network['Driver'] != 'bridge' or set(network['Containers']) != set(ids):
        raise ValueError('Network not exclusively demo')
    access = json.loads(run('docker', 'network', 'inspect', PROJECT + '-access'))[0]
    if access['Driver'] != 'bridge' or set(access['Containers']) != set(ids):
        raise ValueError('Access network not exclusively demo')
    return True


def provision():
    initialized(); isolation()
    if members() != NODES: raise ValueError('Provision only after complete membership')
    api = Api()
    if {v['name'] for v in api.call('GET', 'vhosts')} != {'demo73'}:
        raise ValueError('Unexpected vhost; refusing mutation')
    for user, configure, write, read in [
        ('demo73-publisher', '^$', '^demo73\\.(direct|topic)$', '^$'),
        ('demo73-consumer', '^$', '^$', '^demo73\\.(orders|payments|events)\\.(q|dlq)$')]:
        api.call('PUT', 'users/' + user, {'password': secret(user.split('-')[1].upper() + '_PASSWORD'), 'tags': ''})
        api.call('PUT', 'permissions/demo73/' + user, {'configure': configure, 'write': write, 'read': read})
    for x in TOPOLOGY['exchanges']:
        api.call('PUT', 'exchanges/demo73/' + x['name'], dict(type=x['type'], durable=True, auto_delete=False, internal=False, arguments={}))
    for q in TOPOLOGY['queues']:
        for name in (q['name'], q['dlq']):
            api.call('PUT', 'queues/demo73/' + name, dict(durable=True, auto_delete=False,
                arguments={'x-queue-type': 'quorum', 'x-quorum-initial-group-size': 2}))
        api.call('PUT', 'policies/demo73/' + q['name'], {
            'pattern': '^' + q['name'].replace('.', '\\.') + '$', 'apply-to': 'quorum_queues', 'priority': 1,
            'definition': {'dead-letter-exchange': 'demo73.dlx', 'dead-letter-routing-key': q['failed'],
                           'dead-letter-strategy': 'at-least-once', 'overflow': 'reject-publish'}})
        for exchange, queue, key in [(q['exchange'], q['name'], q['key']), ('demo73.dlx', q['dlq'], q['failed'])]:
            api.call('POST', f'bindings/demo73/e/{exchange}/q/{queue}', {'routing_key': key, 'arguments': {}})
    api.call('POST', 'bindings/demo73/e/demo73.topic/q/demo73.events.q',
             {'routing_key': TOPOLOGY['additional_topic_binding'], 'arguments': {}})
    wait_for(lambda: all(set(q.get('online', [])) == NODES for q in api.call('GET', 'queues/demo73')))
    verify()


def verify():
    initialized(); isolation()
    if members() != NODES: raise ValueError('Both nodes must be running')
    api = Api()
    expected = {name for q in TOPOLOGY['queues'] for name in (q['name'], q['dlq'])}
    queues = api.call('GET', 'queues/demo73')
    if {q['name'] for q in queues} != expected: raise ValueError('Unexpected queue inventory')
    if any(q['type'] != 'quorum' or set(q.get('members', [])) != NODES or set(q.get('online', [])) != NODES for q in queues):
        raise ValueError('Two online replicas required for every main queue and DLQ')
    for node in (1, 2):
        status = json.loads(run(*compose('exec', '-T', f'rabbit-demo-{node}', 'rabbitmqctl', 'cluster_status', '--formatter', 'json')))
        if set(status['running_nodes']) != NODES: raise ValueError('CLI cluster_status disagreement')
    print('VERIFIED: 2 nodes, 6 quorum queues each with 2 online members, private network and independent volumes')


def preflight():
    initialized()
    info = json.loads(run('docker', 'info', '--format', '{{json .}}'))
    if info['MemTotal'] < 4 * 1024**3 or info['NCPU'] < 2:
        raise ValueError('Demo requires >=4GiB Docker memory and >=2 CPUs')
    if shutil.disk_usage(ROOT).free < 6 * 1024**3:
        raise ValueError('Insufficient local disk headroom')
    # Refuse names previously occupied by another project; no volume deletion/reset.
    for suffix in ('node1', 'node2'):
        exists = subprocess.run(['docker', 'volume', 'inspect', PROJECT + '_' + suffix], capture_output=True, text=True)
        if exists.returncode == 0:
            labels = json.loads(exists.stdout)[0].get('Labels') or {}
            if labels.get('com.docker.compose.project') != PROJECT: raise ValueError('Foreign named volume')
    for name in [PROJECT, PROJECT + '-access']:
        network = subprocess.run(['docker', 'network', 'inspect', name], capture_output=True, text=True)
        if network.returncode == 0:
            obj = json.loads(network.stdout)[0]
            if (obj.get('Labels') or {}).get('com.docker.compose.project') != PROJECT:
                raise ValueError('Foreign network')
            if name == PROJECT and not obj['Internal']: raise ValueError('Demo peer network must be internal')
    print(json.dumps({'docker_memory_bytes': info['MemTotal'], 'docker_cpus': info['NCPU'],
                      'free_disk_bytes': shutil.disk_usage(ROOT).free, 'node_limit': '768MiB / 1CPU each'}))


def resources():
    initialized(); isolation()
    names = [PROJECT + '-rabbit-demo-' + str(n) + '-1' for n in (1, 2)]
    stats = [json.loads(line) for line in run('docker', 'stats', '--no-stream', '--format', '{{json .}}', *names).splitlines()]
    nodes = [{key: n.get(key) for key in ['name', 'running', 'mem_used', 'mem_limit', 'mem_alarm', 'disk_free', 'disk_free_alarm']}
             for n in Api().call('GET', 'nodes')]
    print(json.dumps({'docker_stats': stats, 'broker_metrics': nodes}, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['init', 'preflight', 'start', 'provision', 'verify', 'resources', 'stop'])
    action = parser.parse_args().action
    if action == 'init': init()
    elif action == 'preflight': preflight()
    elif action == 'start':
        preflight()
        # Existing peers must rejoin simultaneously after full stop; no healthy dependency cycle.
        subprocess.run(compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180',
                               'rabbit-demo-1', 'rabbit-demo-2'), check=True)
        wait_for(lambda: members() == NODES)
        isolation()
    elif action == 'provision': provision()
    elif action == 'verify': verify()
    elif action == 'resources': resources()
    else:
        initialized(); isolation()
        subprocess.run(compose('stop', '-t', '30'), check=True)
        print('Only demo stopped; both volumes and operational brokers preserved')


if __name__ == '__main__':
    try: main()
    except Exception:
        raise SystemExit('BLOCKED: local demo operation failed; no credentials disclosed; inspect demo-only status')
