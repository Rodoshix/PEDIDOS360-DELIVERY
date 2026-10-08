"""Transport adapter of #69: HTTPS+CA and file secrets; no topology changes."""
import argparse
import base64
import json
import os
from pathlib import Path
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

import platform_source as p


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError('Management redirects prohibited; credentials remain on configured HTTPS endpoint')


class HttpsApi:
    def __init__(self, user='p360-bootstrap', password=None):
        self.base = os.environ['EP2_MANAGEMENT_URL']
        u = urllib.parse.urlparse(self.base)
        if u.scheme != 'https' or u.username or u.password or u.path != '/api/':
            raise ValueError('Require HTTPS Management URL ending /api/, without inline credentials')
        self.context = ssl.create_default_context(cafile=os.environ['EP2_CA_FILE'])
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2
        token = base64.b64encode(f'{user}:{password or os.environ["BOOTSTRAP_PASSWORD"]}'.encode()).decode()
        self.headers = {'Authorization': 'Basic ' + token, 'Content-Type': 'application/json'}
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                                  urllib.request.HTTPSHandler(context=self.context))

    def call(self, method, path, data=None, missing=False):
        # Source provisioner only uses GET, PUT and POST. No destructive API verbs.
        if method not in ('GET', 'PUT', 'POST') or 'contents' in path:
            raise ValueError('Destructive API operation prohibited')
        request = urllib.request.Request(self.base + path, method=method, headers=self.headers,
                                         data=None if data is None else json.dumps(data).encode())
        try:
            with self.opener.open(request, timeout=15) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as failure:
            if missing and failure.code == 404:
                return None
            raise RuntimeError(f'Management {method} {path}: HTTP {failure.code}') from None


def load_secrets():
    root = Path(os.environ['EP2_SECRET_DIR'])
    for _, variable, *_ in p.accounts():
        value = (root / variable).read_text(encoding='utf-8').strip()
        if len(value) < 24 or '\n' in value:
            raise ValueError(f'{variable}: invalid private file (minimum 24 chars, one line)')
        os.environ[variable] = value


def verify_exact():
    p.verify()
    api = HttpsApi()
    expected_users = {a[0]: a[-1] for a in p.accounts()}
    users = api.call('GET', 'users')
    if {u['name'] for u in users} != set(expected_users):
        raise AssertionError('Expected exactly 12 users; review extras without deleting')
    for u in users:
        tags = u['tags'] if isinstance(u['tags'], list) else u['tags'].split(',')
        if set(filter(None, tags)) != set(filter(None, expected_users[u['name']].split(','))):
            raise AssertionError('Unexpected user tags: ' + u['name'])
    if len(api.call('GET', 'permissions')) != 13:
        raise AssertionError('Expected exactly 13 permission entries')
    custom = [b for b in api.call('GET', 'bindings/pedidos360') if b['source'].startswith('p360.')]
    if len(custom) != 20:
        raise AssertionError('Expected exactly 20 custom bindings')
    if {x['name'] for x in api.call('GET', 'policies/pedidos360')} != {x['name'] for x in p.inventory()['policies']}:
        raise AssertionError('Policy inventory differs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inventory', 'preflight', 'provision', 'verify'])
    args = parser.parse_args()
    if args.action == 'inventory':
        print(json.dumps(p.inventory(), indent=2))
        return
    load_secrets()
    p.Api = HttpsApi
    if args.action == 'preflight':
        p.preflight(p.Api(), p.inventory())
        print('PREFLIGHT OK (read-only, HTTPS verified)')
    elif args.action == 'provision':
        if os.environ.get('EP2_EXECUTION_SCOPE') not in ('LOCAL_TEST', 'AWS_APPROVED'):
            raise ValueError('Provisioning disabled in PREPARED_ONLY scope')
        p.provision()
        verify_exact()
    else:
        verify_exact()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print remote response bodies, headers, passwords or environment.
        # Known local validation/preflight errors only contain public resource/file names.
        detail = str(error) if type(error) in (ValueError, RuntimeError, AssertionError) else 'operation failed; inspect public diagnostics'
        print(type(error).__name__ + ': ' + detail, file=sys.stderr)
        sys.exit(1)
