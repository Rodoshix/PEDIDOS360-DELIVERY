"""Explicit, isolated EP2 host operations. No AWS mutating API calls."""
import argparse
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'rabbitmq:4.1.8-management-alpine@sha256:b586ea9784425774ac56f3d4919e4f723edb3287ef8a6c4c8f0df8fe569747c3'
SAFE = {'PEDIDOS360_COORDINATION_MODE': 'HTTP', 'PEDIDOS360_RELIABILITY_PLATFORM_READY': 'false',
        'PEDIDOS360_RELAY_MODE': 'DISABLED', 'PEDIDOS360_DECLARE_TOPOLOGY': 'false'}


def run(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def load_env():
    file = Path(os.environ.get('EP2_ENV_FILE', ROOT / '.env'))
    if not file.is_file():
        raise ValueError('Prepare .env from .env.example; no defaults permit deployment')
    for line in file.read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            os.environ.setdefault(key.strip(), value.strip())
    for key, value in SAFE.items():
        if os.environ.get(key) != value:
            raise ValueError('Unsafe flag: ' + key)
    scope = os.environ.get('EP2_EXECUTION_SCOPE')
    if scope == 'LOCAL_TEST':
        for key in ('RABBITMQ_DATA_DIR', 'EP2_PRIVATE_DIR'):
            if not Path(os.environ[key]).resolve().is_relative_to((ROOT / 'test-local').resolve()):
                raise ValueError('Local test paths must remain inside test-local')
        if os.environ['EP2_BACKEND_NETWORK'] != 'pedidos360-ep2-aws-tests':
            raise ValueError('Local test must use its isolated network')
    elif scope != 'AWS_APPROVED':
        raise ValueError('PREPARED_ONLY: start/operations blocked until explicit later approval')
    return file


def compose(*args):
    env_file = load_env()
    project = 'pedidos360-ep2-aws-tests' if os.environ['EP2_EXECUTION_SCOPE'] == 'LOCAL_TEST' else 'pedidos360-ep2-broker'
    command = ['docker', 'compose', '-p', project, '--env-file', str(env_file), '-f', str(ROOT / 'compose.rabbitmq.yml')]
    if os.environ['EP2_EXECUTION_SCOPE'] == 'LOCAL_TEST':
        command += ['-f', str(ROOT / 'test-local/compose.test.yml')]
    return command + list(args)


def container():
    value = run(compose('ps', '-a', '-q', 'rabbitmq'))
    if not value or '\n' in value:
        raise ValueError('Expected exactly one EP2 rabbitmq container')
    return value


def preflight():
    load_env()
    net = json.loads(run(['docker', 'network', 'inspect', os.environ['EP2_BACKEND_NETWORK']]))[0]
    if not net['Internal'] or net['Driver'] != 'bridge':
        raise ValueError('Require existing internal bridge backend network')
    data = Path(os.environ['RABBITMQ_DATA_DIR'])
    if not data.is_dir():
        raise ValueError('Data mount absent; refusing root fallback/create directory')
    if os.environ['EP2_EXECUTION_SCOPE'] == 'AWS_APPROVED':
        if sys.platform != 'linux':
            raise ValueError('AWS host preflight requires Linux')
        mounted = json.loads(run(['findmnt', '-J', '-M', str(data), '-o', 'TARGET,SOURCE,UUID,FSTYPE,SIZE']))['filesystems'][0]
        if mounted['uuid'] != os.environ.get('EP2_EXPECTED_FS_UUID') or mounted['fstype'] != 'ext4':
            raise ValueError('Dedicated mounted filesystem UUID/type mismatch')
        root = json.loads(run(['findmnt', '-J', '-M', '/', '-o', 'UUID']))['filesystems'][0]
        if mounted['uuid'] == root['uuid']:
            raise ValueError('Root filesystem cannot be the persistent broker disk')
        fs_id = run(['stat', '-f', '-c', '%i', str(data)])
        if fs_id != os.environ.get('EP2_EXPECTED_FS_ID'):
            raise ValueError('Dedicated filesystem id mismatch (container boot guard)')
        serial = run(['lsblk', '-ndo', 'SERIAL', mounted['source']])
        if not serial:
            # source may be a partition: resolve physical parent before comparing EBS serial.
            parent = run(['lsblk', '-ndo', 'PKNAME', mounted['source']])
            serial = run(['lsblk', '-ndo', 'SERIAL', '/dev/' + parent])
        expected = os.environ.get('EP2_EXPECTED_VOLUME_ID', '').replace('-', '')
        if not expected.startswith('vol') or serial.replace('-', '') != expected:
            raise ValueError('Dedicated EBS volume identity mismatch')
        memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
        available = int(memory['MemAvailable'].split()[0]) * 1024
        if available < (768 + 2048) * 1024**2:
            raise ValueError('Insufficient current MemAvailable for broker + 2GiB headroom')
        print(json.dumps({'load_average': os.getloadavg(), 'ram_available_bytes': available,
                          'warning': 'Review CPU credits and load before approving start; no CPU capacity guarantee'}))
    tls = Path(os.environ['EP2_PRIVATE_DIR']) / 'tls'
    for f in ('ca.pem', 'server.pem', 'server-key.pem'):
        if not (tls / f).is_file():
            raise ValueError('TLS file absent: ' + f)
    if os.environ['EP2_EXECUTION_SCOPE'] == 'AWS_APPROVED' and (tls / 'server-key.pem').stat().st_mode & 0o007:
        raise ValueError('TLS private key cannot have world permissions')
    # CA syntax and trust configuration; no verification bypass.
    ssl.create_default_context(cafile=str(tls / 'ca.pem'))
    import platform_source as p
    secrets = Path(os.environ['EP2_PRIVATE_DIR']) / 'secrets'
    for f in {a[1] for a in p.accounts()} | {'ERLANG_COOKIE', 'PEDIDOS360_ACTOR_SECRET', 'PEDIDOS360_ACTOR_KEY_ID'}:
        if not (secrets / f).is_file() or not (secrets / f).stat().st_size:
            raise ValueError('Private file absent: ' + f)
        value = (secrets / f).read_text(encoding='utf-8').strip()
        minimum = 1 if f == 'PEDIDOS360_ACTOR_KEY_ID' else 32 if f == 'PEDIDOS360_ACTOR_SECRET' else 24
        if len(value) < minimum or '\n' in value:
            raise ValueError('Invalid private file: ' + f)
        if os.environ['EP2_EXECUTION_SCOPE'] == 'AWS_APPROVED' and (secrets / f).stat().st_mode & 0o007:
            raise ValueError('Private file cannot have world permissions: ' + f)
    identity = run(['docker', 'run', '--rm', '--network', 'none', '--entrypoint', 'sh', IMAGE,
                    '-c', 'printf "%s:%s" "$(id -u rabbitmq)" "$(id -g rabbitmq)"'])
    # Image is inspected, not guessed. Run as that identity to test host mounts.
    run(['docker', 'run', '--rm', '--network', 'none', '--user', identity, '--entrypoint', 'sh',
         '--mount', f'type=bind,source={ROOT / "rabbitmq.conf"},target=/broker.conf,readonly',
         '--mount', f'type=bind,source={ROOT / "enabled_plugins"},target=/enabled_plugins,readonly',
         '--mount', f'type=bind,source={data.resolve()},target=/data',
         '--mount', f'type=bind,source={tls.resolve()},target=/tls,readonly', IMAGE,
         '-c', 'test -r /broker.conf && test -r /enabled_plugins && test -w /data && test -r /tls/server-key.pem && openssl verify -purpose sslserver -CAfile /tls/ca.pem /tls/server.pem >/dev/null && openssl x509 -in /tls/server.pem -noout -checkend 604800 >/dev/null && openssl x509 -in /tls/server.pem -noout -checkhost p360-rabbitmq >/dev/null && test "$(openssl x509 -in /tls/server.pem -pubkey -noout | openssl sha256)" = "$(openssl pkey -in /tls/server-key.pem -pubout | openssl sha256)"'])
    if shutil.disk_usage(data).free < 3 * 1024**3:
        raise ValueError('Data disk requires at least 3GiB available (2GiB alarm + headroom)')
    run(compose('config', '--quiet'))
    print('HOST PREFLIGHT OK; files/mounts/network/flags checked; no deployment performed')


def management(action):
    load_env()
    if os.environ['EP2_EXECUTION_SCOPE'] == 'LOCAL_TEST':
        subprocess.run([sys.executable, str(ROOT / 'scripts/provision.py'), action], check=True)
    else:
        # Private backend has no host ports. Tools join it temporarily, TLS verifies Docker DNS.
        image = os.environ.get('EP2_AMQP_TOOLS_IMAGE')
        if not image or image.startswith('REPLACE'):
            raise ValueError('Require the approved tools image id; no unpinned fallback')
        subprocess.run(['docker', 'run', '--rm', '--network', os.environ['EP2_BACKEND_NETWORK'],
                        '--mount', f'type=bind,source={ROOT / "scripts"},target=/tool,readonly',
                        '--mount', f'type=bind,source={Path(__file__).resolve().parents[3] / "rabbitmq/platform_control.py"},target=/platform-source/platform_control.py,readonly',
                        '-e', 'EP2_PLATFORM_SOURCE=/platform-source/platform_control.py',
                        '--mount', f'type=bind,source={os.environ["EP2_PRIVATE_DIR"]},target=/run/private,readonly',
                        '-e', 'EP2_EXECUTION_SCOPE=AWS_APPROVED',
                        '-e', 'EP2_MANAGEMENT_URL=https://p360-rabbitmq:15671/api/',
                        '-e', 'EP2_CA_FILE=/run/private/tls/ca.pem',
                        '-e', 'EP2_SECRET_DIR=/run/private/secrets',
                        '-e', 'RABBITMQ_SIMPLE_RETRY_MS=' + os.environ['RABBITMQ_SIMPLE_RETRY_MS'],
                        image, 'python', '-B', '/tool/provision.py', action], check=True)


def verify():
    info = json.loads(run(['docker', 'inspect', container()]))[0]
    ports = info['NetworkSettings']['Ports']
    for bindings in ports.values():
        for binding in bindings or []:
            if os.environ['EP2_EXECUTION_SCOPE'] != 'LOCAL_TEST' or binding['HostIp'] != '127.0.0.1':
                raise ValueError('Unexpected host-published port')
    listeners = run(compose('exec', '-T', 'rabbitmq', 'rabbitmq-diagnostics', '-q', 'listeners'))
    if 'protocol: http,' in listeners or 'protocol: http/' in listeners or 'port: 5672,' in listeners or 'port: 15672,' in listeners or 'port: 5671,' not in listeners or 'port: 15671,' not in listeners:
        raise ValueError('Listener set must contain AMQPS/HTTPS and no plaintext AMQP/HTTP')
    if info['State'].get('Health', {}).get('Status') != 'healthy':
        raise ValueError('RabbitMQ not healthy')
    management('verify')
    print('VERIFIED: TLS listeners, health, bindings and no public/production host ports')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['preflight', 'start', 'stop', 'status', 'measure', 'provision', 'verify', 'rollback'])
    args = parser.parse_args()
    if args.action == 'preflight':
        preflight()
    elif args.action == 'start':
        preflight()
        subprocess.run(compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '150', 'rabbitmq'), check=True)
    elif args.action in ('stop', 'rollback'):
        # Even rollback uses STOP, not DOWN: no orphan cleanup, networks or data deletion.
        subprocess.run(compose('stop', '-t', '90', 'rabbitmq'), check=True)
        print('EP2 broker stopped only. Entrega1 untouched; EBS/mount/container preserved.')
    elif args.action == 'provision':
        management('preflight')
        management('provision')
    elif args.action == 'verify':
        verify()
    elif args.action == 'status':
        print(run(compose('ps', '-a', 'rabbitmq')))
        print(run(compose('exec', '-T', 'rabbitmq', 'rabbitmq-diagnostics', '-q', 'listeners')))
        print(run(compose('exec', '-T', 'rabbitmq', 'df', '-h', '/var/lib/rabbitmq')))
        management('verify')
    else:
        load_env()
        subprocess.run([sys.executable, str(ROOT / 'scripts/measure.py')], check=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
