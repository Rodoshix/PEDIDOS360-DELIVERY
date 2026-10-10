"""Read-only host capacity probe. No environment/credential/message contents."""
import json
import subprocess
import time
from pathlib import Path
import argparse

AWS = ['aws', '--profile', 'pedidos360-lab', '--region', 'us-east-1']
HOSTS = ['i-07d0adf18d62d564b', 'i-0cdcbd1090a2b8be2']
COMMAND = '''set -eu
date -u
uname -m
nproc
free -b
df -B1 / /var/lib/docker
findmnt -rn -o SOURCE,TARGET,FSTYPE
docker ps -a --format '{{.Names}} {{.Image}} {{.Status}}'
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}} {{.PIDs}}'
ids=$(docker ps -q)
if test -n "$ids"; then
docker inspect $ids --format '{{.Name}} limit={{.HostConfig.Memory}} cpu={{.HostConfig.NanoCpus}} networks={{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}} mounts={{range .Mounts}}{{.Source}}:{{.Destination}} {{end}}'
fi
if docker ps --format '{{.Names}}' | grep -qx pedidos360-ep2-broker-rabbitmq-1; then
docker inspect pedidos360-ep2-broker-rabbitmq-1 --format '{{json .NetworkSettings.Networks}}'
docker exec pedidos360-ep2-broker-rabbitmq-1 rabbitmqctl -q list_permissions -p pedidos360
docker exec pedidos360-ep2-broker-rabbitmq-1 rabbitmq-diagnostics -q listeners
fi
for base in /opt/pedidos360/private-ep2 /opt/pedidos360/ep2-private; do
for relative in tls/apps-truststore.p12 identity/PEDIDOS360_ACTOR_PRIVATE_JWK identity/PEDIDOS360_ACTOR_PUBLIC_JWKS identity/USUARIOS_IDENTITY_PROOF_PRIVATE_JWK identity/USUARIOS_IDENTITY_PROOF_PUBLIC_JWKS; do
if test -f "$base/$relative"; then stat -c '%n %s %a %u:%g' "$base/$relative"; else printf 'MISSING %s/%s\\n' "$base" "$relative"; fi
done
done
'''

def call(*args):
    r = subprocess.run(AWS + list(args), capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(r.stderr[:700])
    return json.loads(r.stdout)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = {'scope': 'metadata only; no service/data/config mutation', 'hosts': {}}
    for host in HOSTS:
        response = call('ssm', 'send-command', '--instance-ids', host,
            '--document-name', 'AWS-RunShellScript', '--parameters',
            json.dumps({'commands': [COMMAND], 'executionTimeout': ['60']}))
        command_id = response['Command']['CommandId']
        for _ in range(30):
            time.sleep(2)
            try:
                status = call('ssm', 'get-command-invocation', '--command-id', command_id, '--instance-id', host)
            except RuntimeError:
                continue
            if status['Status'] not in ('Pending', 'InProgress', 'Delayed'):
                result['hosts'][host] = {k: status.get(k) for k in
                    ('Status', 'ExecutionStartDateTime', 'ExecutionEndDateTime', 'StandardOutputContent', 'StandardErrorContent')}
                result['hosts'][host]['command_id'] = command_id
                break
        else:
            raise RuntimeError('Read-only SSM probe timeout')
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))

if __name__ == '__main__': main()
