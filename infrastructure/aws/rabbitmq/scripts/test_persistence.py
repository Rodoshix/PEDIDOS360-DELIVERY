"""Guarded broker restart test. No deletion/purge; identity preserved in local evidence."""
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
import tools


def main():
    tools.load_env()
    if os.environ.get('EP2_ALLOW_PERSISTENCE_RESTART') != '1':
        raise ValueError('Set EP2_ALLOW_PERSISTENCE_RESTART=1 only after explicit restart authorization')
    tools.verify()
    identity = str(uuid.uuid4())
    scope = os.environ['EP2_EXECUTION_SCOPE']
    witness = tools.ROOT / 'evidence' / ('persistence-' + identity + '.json')
    record = {'scope': scope, 'message_id': identity, 'phase': 'before-publish', 'queue': 'demo.ep2.persistence.quorum.q'}
    witness.parent.mkdir(exist_ok=True)
    witness.write_text(json.dumps(record, indent=2))
    def probe(action):
        if scope == 'LOCAL_TEST':
            env = dict(os.environ, EP2_AMQP_HOST='127.0.0.1', EP2_AMQP_PORT='5782')
            subprocess.run([sys.executable, str(tools.ROOT / 'scripts/persistence_probe.py'), action, identity], env=env, check=True)
        else:
            image = os.environ.get('EP2_AMQP_TOOLS_IMAGE')
            if not image or image.startswith('REPLACE'):
                raise ValueError('Build/approve private-network tools image first; no runtime pip installs')
            subprocess.run(['docker', 'run', '--rm', '--network', os.environ['EP2_BACKEND_NETWORK'],
              '--mount', f'type=bind,source={tools.ROOT / "scripts"},target=/tool,readonly',
              '--mount', f'type=bind,source={os.environ["EP2_PRIVATE_DIR"]},target=/run/private,readonly',
              '-e', 'EP2_EXECUTION_SCOPE=AWS_APPROVED', '-e', 'EP2_CA_FILE=/run/private/tls/ca.pem',
              '-e', 'EP2_SECRET_DIR=/run/private/secrets', image, 'python', '-B', '/tool/persistence_probe.py', action, identity], check=True)
    probe('publish')
    record['phase'] = 'publish-confirmed-before-restart'
    witness.write_text(json.dumps(record, indent=2))
    subprocess.run(tools.compose('stop', '-t', '90', 'rabbitmq'), check=True)
    subprocess.run(tools.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '150', 'rabbitmq'), check=True)
    tools.verify()
    probe('consume')
    record['phase'] = 'PASS: recovered-after-stop-start-and-acked'
    witness.write_text(json.dumps(record, indent=2))
    print('PASS: dedicated broker restart persistence, no application service touched')


if __name__ == '__main__':
    main()
