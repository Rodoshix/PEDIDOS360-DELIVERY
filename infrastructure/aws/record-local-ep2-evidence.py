"""Record this LOCAL run's results/hashes; never upload raw logs or PDF sources."""
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASE = 'f6a9540e5db9890a7716184b917c718d35a21de6'
IMPLEMENTATION = '1a3bb0df2c4e28ee55133b74cb0f21937a9d1628'
MODULES = ('backend/shared/p360-messaging-core', 'backend/bff', *(f'backend/services/{name}-service' for name in ('usuarios','restaurantes','productos','carrito','pedidos','pagos','rabbit-admin')))
EVIDENCE = Path.home() / 'Documents/ChatGPT/Pedidos360 Delivery/operational-evidence/EP2-LOCAL-ACADEMIC-2026-10-10'
LOCAL = EVIDENCE


def sha(data): return hashlib.sha256(data).hexdigest()


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    suites = []
    for module in MODULES:
        files = sorted((ROOT / module / 'target/surefire-reports').glob('TEST-*.xml'))
        if not files: raise ValueError('Missing executed test evidence: ' + module)
        cases = []
        for file in files:
            suite = ET.parse(file).getroot()
            cases.append({'class': suite.attrib['name'], **{key: int(suite.attrib[key]) for key in ('tests','failures','errors','skipped')}, 'report_sha256': sha(file.read_bytes())})
        totals = {key: sum(c[key] for c in cases) for key in ('tests','failures','errors','skipped')}
        assert not totals['failures'] and not totals['errors']
        suites.append({'module': module, **totals, 'passed': totals['tests'] - totals['skipped'], 'classes': cases})
    logs = []
    paths = sorted(set(LOCAL.glob('p360-ep2-*-tests.log')) | set(LOCAL.glob('p360-admin-local-tests.log')) | set(LOCAL.glob('p360-admin-package.log')) | set(LOCAL.glob('p360-angular-*.log')) | set(LOCAL.glob('p360-react-*.log')) | set(LOCAL.glob('p360-image-*.log')) | set(LOCAL.glob('p360-cluster-*.log')) | set(LOCAL.glob('p360-angular-*-audit.json')))
    for file in paths:
        target = EVIDENCE / file.name
        if file.resolve() != target.resolve(): shutil.copyfile(file, target)
        logs.append({'file': file.name, 'sha256': sha(target.read_bytes()), 'bytes': target.stat().st_size, 'location': 'external local evidence, not versioned'})
    images_file = LOCAL / 'p360-image-inspection.json'
    images = json.loads(images_file.read_text(encoding='utf-8-sig'))
    assert len(images) == 9
    if images_file.parent.resolve() != EVIDENCE.resolve(): shutil.copyfile(images_file, EVIDENCE / images_file.name)
    source_names = subprocess.check_output(['git','diff','--name-only',BASE,IMPLEMENTATION], cwd=ROOT, text=True).splitlines()
    sources = [{'file': name, 'git_blob_sha256': sha(subprocess.check_output(['git','show',IMPLEMENTATION+':'+name], cwd=ROOT))} for name in source_names]
    audit = json.loads((LOCAL / 'p360-angular-full-audit.json').read_text(encoding='utf-8-sig'))
    assert audit['metadata']['vulnerabilities']['total'] == 0
    result = {'status': 'LOCAL EP2 PARTIAL', 'generated_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'base': BASE, 'implementation_commit': IMPLEMENTATION,
        'backend_suites': suites, 'backend_passed': sum(s['passed'] for s in suites), 'backend_skipped': sum(s['skipped'] for s in suites),
        'angular': {'passed': 29, 'runner': 'Angular TestBed / Vitest 4.1.11 / jsdom', 'HTTP_build': 'PASS', 'RABBITMQ_build': 'PASS', 'typecheck': 'PASS', 'live_Entra': 'NOT EXECUTED', 'cloud_API_Manager': 'NOT EXECUTED', 'npm_audit': audit['metadata']['vulnerabilities']},
        'react': {'passed': 253, 'source_changed': False, 'oxlint': 'PASS', 'build': 'PASS', 'warning': 'chunk >500kB'},
        'configuration_tests': {'multihost': 12, 'local_compose_transition': 1, 'demo_config': 5},
        'cluster': {'real_suite_total': 11, 'passed': 9, 'errors': 2, 'failure': 'full restart: demo node 2 node_not_running; following TLS probe failed', 'separate_recovery': 'same-volume start; majority verified; three persisted synthetic messages recovered and ACKed; AMQP1.0 probe passed', 'final': 'demo stopped, volumes preserved', 'reset_or_purge': False},
        'official_pdf_source_hashes': {'EP2_DSY1107_Estudiante_encargo.pdf': '53eef199c2a19d6ee2dad8a6355c89013d84519ec979cffd4044ca8504b3ae0c', 'EP2_DSY1107_Estudiante_presentación.pdf': 'ee37641722b4d5dda832e2e6adf029cf8eb2d4f5316aa4fc5885af637fe19a41'},
        'local_images': images, 'logs': logs, 'sources': sources,
        'limitations': ['No AWS operations or publication', 'No live Angular Entra login or authenticated browser E2E', 'No cloud evidence', 'Disposable exclusive queues lose synthetic contents when owner connection closes', 'No reliable unattended full demo restart demonstrated', 'Image library/classpath inspection is not a full layer vulnerability or secret scan']}
    path = ROOT / 'docs/ep2/LOCAL-ACADEMIC-EP2-tests.json'
    path.write_text(json.dumps(result, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    print(json.dumps({'manifest': str(path), 'backend_passed': result['backend_passed'], 'backend_skipped': result['backend_skipped'], 'images': len(images), 'source_files': len(sources)}))


if __name__ == '__main__': main()
