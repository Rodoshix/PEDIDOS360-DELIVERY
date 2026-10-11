"""Real migration-runner rehearsal on a fresh internal, temporary PostgreSQL.

No AWS, production URLs, host Docker overrides or persistent Docker volumes.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid
import argparse

def run(*args): return subprocess.run(list(args), capture_output=True, text=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=Path)
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY')):
        raise ValueError('Local Docker only')
    context = json.loads(run('docker','context','inspect').stdout)[0]
    if not context['Endpoints']['docker']['Host'].startswith(('npipe://','unix://')):
        raise ValueError('Local Docker only')
    prefix = 'p360-minimal-migration-' + uuid.uuid4().hex
    results = []
    with tempfile.TemporaryDirectory(prefix=prefix) as root:
        private = Path(root)
        password = secrets.token_hex(32)
        (private/'password').write_text(password)
        network = run('docker','network','create','--internal','--label','p360.fixture='+prefix,prefix)
        if network.returncode: raise RuntimeError('Fixture network failed')
        container = None
        try:
            start = run('docker','run','-d','--name',prefix,'--network',prefix,
                '--label','p360.fixture='+prefix,'--tmpfs','/var/lib/postgresql/data:rw,size=256m',
                '--mount',f'type=bind,src={private},dst=/run/private,readonly',
                '-e','POSTGRES_PASSWORD_FILE=/run/private/password','postgres:17-alpine')
            if start.returncode: raise RuntimeError('Temporary PostgreSQL failed')
            container = start.stdout.strip()
            for _ in range(60):
                if run('docker','exec',container,'pg_isready','-U','postgres').returncode == 0: break
                time.sleep(1)
            else: raise RuntimeError('Temporary PostgreSQL timeout')
            for service in ('carrito','pedidos','pagos'):
                text = f'flyway.url=jdbc:postgresql://{prefix}:5432/postgres\nflyway.user=postgres\nflyway.password={password}\nflyway.schemas={service}\nflyway.defaultSchema={service}\nflyway.cleanDisabled=true\nflyway.locations=filesystem:/build/src/main/resources/db/migration\n'
                (private/f'{service}.conf').write_text(text)
                # Establish the same prior schema versions observed in RDS;
                # validate must fail on a nonexistent schema, not be weakened.
                prior = '2' if service == 'pagos' else '1'
                for action in ('bootstrap-prior','prevalidate','migrate','postvalidate'):
                    goal = 'migrate' if action == 'bootstrap-prior' else ('validate' if action.endswith('validate') else action)
                    result = run('docker','run','--rm','--network',prefix,'--cpus','1','--memory','512m',
                        '--mount',f'type=bind,src={private},dst=/run/private,readonly',
                        f'p360-local/{service}-migrator-ready:ep2-f6a9540e5db9','sh','mvnw','-o','-B','-ntp',
                        'org.flywaydb:flyway-maven-plugin:12.4.0:'+goal,
                        '-Dflyway.configFiles=/run/private/'+service+'.conf',
                        *(['-Dflyway.target='+prior] if action == 'bootstrap-prior' else []),
                        *(['-Dflyway.ignoreMigrationPatterns=*:pending'] if action == 'prevalidate' else []),
                        *(['-Dflyway.ignoreMigrationPatterns='] if action == 'postvalidate' else []))
                    # Synthetic credentials are still not written to logs.
                    (args.evidence/f'{service}-runner-{action}-{len(results)}.log').write_text(
                        (result.stdout+result.stderr).replace(password,'[omitted]'),encoding='utf-8')
                    results.append({'service':service,'action':action,'exit_code':result.returncode})
                    print(json.dumps(results[-1]),flush=True)
                    if result.returncode: raise RuntimeError('Runner rehearsal failed; inspect sanitized log')
            (args.evidence/'migration-runners.json').write_text(json.dumps(results,indent=2))
        finally:
            if container:
                # Exact newly-created ID only. tmpfs DB, no persistent volume cleanup.
                inspect = json.loads(run('docker','inspect',container).stdout)[0]
                if inspect['Config']['Labels'].get('p360.fixture') == prefix:
                    run('docker','rm','-f',container)
            run('docker','network','rm',prefix)

if __name__ == '__main__': main()
