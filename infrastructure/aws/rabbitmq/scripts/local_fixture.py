"""NO AWS / NO PRODUCTION. Generate test-only TLS and isolated local credentials."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import platform_source as p

ROOT = Path(__file__).resolve().parents[1]
TEST = ROOT / 'test-local'


def main():
    TEST.mkdir(exist_ok=True)
    tls = TEST / 'private/tls'
    secret_dir = TEST / 'private/secrets'
    data = TEST / 'data'
    for path in (tls, secret_dir, data):
        path.mkdir(parents=True, exist_ok=True)
    for key in {a[1] for a in p.accounts()} | {'ERLANG_COOKIE'}:
        file = secret_dir / key
        if not file.exists():
            file.write_text(secrets.token_hex(32), encoding='utf-8')
    subprocess.run(['node', str(ROOT / 'scripts/identity_fixture.mjs')], check=True)
    openssl = os.environ.get('EP2_TEST_OPENSSL', 'openssl')
    def openssl_run(*args):
        subprocess.run([openssl, *args], cwd=tls, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not (tls / 'server.pem').exists():
        openssl_run('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '14', '-keyout', 'ca-key.pem', '-out', 'ca.pem', '-subj', '/CN=NO-AWS-NO-PRODUCTION-EP2-TEST-CA', '-addext', 'basicConstraints=critical,CA:TRUE')
        openssl_run('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'server-key.pem', '-out', 'server.csr', '-subj', '/CN=p360-rabbitmq')
        (tls / 'server.ext').write_text('subjectAltName=DNS:p360-rabbitmq,IP:127.0.0.1\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n', encoding='utf-8')
        openssl_run('x509', '-req', '-in', 'server.csr', '-CA', 'ca.pem', '-CAkey', 'ca-key.pem', '-CAcreateserial', '-out', 'server.pem', '-days', '14', '-extfile', 'server.ext')
    values = dict(EP2_EXECUTION_SCOPE='LOCAL_TEST', EP2_BACKEND_NETWORK='pedidos360-ep2-aws-tests',
                  RABBITMQ_DATA_DIR=data.as_posix(), EP2_PRIVATE_DIR=(TEST / 'private').as_posix(),
                  EP2_MANAGEMENT_URL='https://127.0.0.1:15782/api/', EP2_CA_FILE=(tls / 'ca.pem').as_posix(),
                  EP2_SECRET_DIR=secret_dir.as_posix(), RABBITMQ_SIMPLE_RETRY_MS='1000',
                  PEDIDOS360_COORDINATION_MODE='HTTP', PEDIDOS360_RELIABILITY_PLATFORM_READY='false',
                  PEDIDOS360_RELAY_MODE='DISABLED', PEDIDOS360_DECLARE_TOPOLOGY='false')
    (TEST / '.env').write_text('\n'.join(f'{k}={v}' for k, v in values.items()) + '\n', encoding='utf-8')
    (TEST / 'NO-AWS-NO-PRODUCTION.txt').write_text('All certificates, private keys, credentials and data here are disposable LOCAL TEST fixtures. Never deploy to AWS.\n', encoding='utf-8')
    # Docker does not publish host ports on an internal-only network. This extra bridge
    # is test-only, in the isolated project; production Compose has no extra network/ports.
    (TEST / 'compose.test.yml').write_text('services:\n  rabbitmq:\n    networks:\n      backend: {}\n      test-host: {}\n    ports:\n      - "127.0.0.1:5782:5671"\n      - "127.0.0.1:15782:15671"\nnetworks:\n  test-host:\n    driver: bridge\n', encoding='utf-8')
    print('Local-only fixture ready. No AWS secrets/material generated.')


if __name__ == '__main__':
    main()
