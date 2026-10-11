"""Build exact develop source locally; never log in, push or call AWS."""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
BASE = 'f6a9540e5db9890a7716184b917c718d35a21de6'
SERVICES = ('bff', 'usuarios', 'restaurantes', 'productos', 'carrito', 'pedidos', 'pagos', 'frontend')
PUBLIC = {
    'VITE_API_BASE_URL': 'https://l7wtit9zmj.execute-api.us-east-1.amazonaws.com/api',
    'VITE_ENTRA_CLIENT_ID': '5388c832-53e4-45c8-a3be-6875b69e1a51',
    'VITE_ENTRA_TENANT_ID': 'a048ca4e-cd7f-4a01-a43e-cb4deccf1ff2',
    'VITE_ENTRA_REDIRECT_URI': 'https://l7wtit9zmj.execute-api.us-east-1.amazonaws.com',
    'VITE_ENTRA_API_SCOPE': 'api://13c0f63f-2007-41c4-8d9f-02640b8a1886/access_as_user',
}

def output(*args): return subprocess.check_output(list(args), text=True).strip()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=Path)
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY')):
        raise ValueError('Docker overrides forbidden')
    context = json.loads(output('docker', 'context', 'inspect'))[0]
    if not context['Endpoints']['docker']['Host'].startswith(('npipe://', 'unix://')):
        raise ValueError('Local Docker only')
    if output('git', '-C', str(ROOT), 'rev-parse', 'origin/develop') != BASE:
        raise ValueError('Develop changed: new review needed')
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    source = evidence / 'source'
    source.mkdir()
    archive = subprocess.check_output(['git', '-C', str(ROOT), 'archive', '--format=tar', BASE])
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        files.extractall(source, filter='data')
    result = {'source': BASE, 'publication': False, 'cache': 'BuildKit cache allowed; core comes from same git archive',
              'frontend_public_configuration': PUBLIC, 'images': []}
    items = [(s, 'HTTP') for s in SERVICES] + [('frontend', 'RABBITMQ')]
    for service, mode in items:
        rel = 'frontend' if service == 'frontend' else ('backend/bff' if service == 'bff' else f'backend/services/{service}-service')
        suffix = '-checkout-rabbit' if mode == 'RABBITMQ' else ''
        tag = f'p360-local/{service}:ep2-react-{BASE[:12]}{suffix}'
        command = ['docker', 'buildx', 'build', '--platform', 'linux/amd64', '--load', '--progress', 'plain',
                   '--label', f'org.opencontainers.image.revision={BASE}', '--tag', tag]
        if service != 'frontend':
            command += ['--build-context', 'messaging-core=' + str(source / 'backend/shared/p360-messaging-core')]
        else:
            for key, value in {**PUBLIC, 'VITE_PEDIDOS_CARRITO_MODE': mode}.items():
                command += ['--build-arg', key + '=' + value]
        command += [str(source / rel)]
        with (evidence / f'{service}{suffix}-build.log').open('w', encoding='utf-8') as log:
            status = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
        item = {'service': service, 'checkout': mode if service == 'frontend' else None, 'tag': tag, 'exit_code': status}
        if status == 0:
            image = json.loads(output('docker', 'image', 'inspect', tag))[0]
            if image['Architecture'] != 'amd64' or image['Os'] != 'linux' or image['Config']['User'] in ('', 'root', '0'):
                raise ValueError('Image platform/user regression')
            item.update(id=image['Id'], platform='linux/amd64', user=image['Config']['User'], size=image['Size'])
        result['images'].append(item)
        (evidence / 'builds.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(f'{service}{suffix}: {"BUILT" if status == 0 else "FAILED"}', flush=True)
        if status:
            raise SystemExit('Build failed; inspect local log, no publication attempted')

if __name__ == '__main__': main()
