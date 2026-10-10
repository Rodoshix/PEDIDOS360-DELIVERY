"""Inspect local images without starting applications or publishing artifacts.

Creates/removes only labeled inspection containers. Copies JARs into an owned
temporary directory, never reads operational secrets or Docker container env.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
import zipfile

SERVICES = ('bff', 'usuarios', 'restaurantes', 'productos', 'carrito', 'pedidos', 'pagos', 'rabbit-admin', 'frontend-angular')


def command(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def inspect():
    if os.environ.get('DOCKER_HOST') or os.environ.get('DOCKER_CONTEXT'):
        raise ValueError('Explicit remote/override Docker context refused')
    context = command('docker', 'context', 'show')
    if context not in ('default', 'desktop-linux'):
        raise ValueError('Only the local Docker Desktop context is permitted')
    endpoint = json.loads(command('docker', 'context', 'inspect', context))[0]['Endpoints']['docker']['Host']
    if not endpoint.startswith(('npipe://', 'unix:///')):
        raise ValueError('Remote Docker endpoint refused')
    result = []
    for service in SERVICES:
        tag = f'p360-local/{service}:ep2-academic'
        metadata = json.loads(command('docker', 'image', 'inspect', tag))[0]
        expected_user = '101:101' if service == 'frontend-angular' else '10001:10001'
        assert metadata['Architecture'] == 'amd64' and metadata['Os'] == 'linux'
        assert metadata['Config']['User'] == expected_user
        item = {'service': service, 'local_tag': tag, 'local_image_id': metadata['Id'], 'platform': 'linux/amd64', 'user': expected_user}
        name = 'p360-ep2-inspect-' + str(uuid.uuid4())
        container = command('docker', 'create', '--name', name, '--label', 'p360.inspection=ep2', metadata['Id'])
        try:
            with tempfile.TemporaryDirectory(prefix='p360-ep2-jar-') as directory:
                if service == 'frontend-angular':
                    config_file = Path(directory) / 'config.json'
                    command('docker', 'cp', container + ':/usr/share/nginx/html/config.json', str(config_file))
                    public = json.loads(config_file.read_text())
                    assert set(public) == {'apiBase', 'clientId', 'tenantId', 'redirectUri', 'scope', 'checkoutMode'}
                    assert public['checkoutMode'] == 'HTTP'
                    item['config_fields'] = sorted(public)
                    item['checkout_mode'] = public['checkoutMode']
                else:
                    jar = Path(directory) / 'app.jar'
                    command('docker', 'cp', container + ':/app/app.jar', str(jar))
                    with zipfile.ZipFile(jar) as archive:
                        libs = sorted(n.removeprefix('BOOT-INF/lib/') for n in archive.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'))
                        for required in ('amqp-client-5.37.0.jar', 'tomcat-embed-core-11.0.26.jar', 'jackson-core-3.1.7.jar', 'jackson-databind-3.1.7.jar'):
                            assert required in libs, (service, required)
                        # Private material is not a classpath resource of these builds.
                        assert not any(n.startswith('BOOT-INF/classes/') and n.endswith(('.p12', '.jks', '.key', '.pem')) for n in archive.namelist())
                        item['libraries'] = libs
                        item['private_classpath_material_found'] = False
        finally:
            owner = json.loads(command('docker', 'inspect', container))[0]
            assert owner['Name'] == '/' + name and owner['Config']['Labels']['p360.inspection'] == 'ep2'
            assert not owner['State']['Running']
            command('docker', 'rm', container)
        result.append(item)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    inspect()
