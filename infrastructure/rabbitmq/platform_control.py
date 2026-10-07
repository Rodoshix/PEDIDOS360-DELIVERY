"""Local, additive platform provisioner. No queue deletion, purge or data migration."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent
BUSINESS = 'pedidos360'
SANDBOX = 'pedidos360-admin-demo'
DENY = '^$'
DOMAINS = [
    ('usuarios', 'consultas', 'usuario.consultar-actual', 'p360.queries'),
    ('restaurantes', 'consultas', 'restaurante.listar', 'p360.queries'),
    ('productos', 'consultas', 'producto.listar-disponibles', 'p360.queries'),
    ('carrito', 'vaciado', 'carrito.vaciar-por-pedido', 'p360.commands'),
    ('pagos', 'consultas', 'pago.consultar', 'p360.queries'),
]


def load_env():
    for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            os.environ.setdefault(key, value.strip())


def exact(*names):
    return '^(' + '|'.join(re.escape(n) for n in names) + ')$'


def inventory():
    exchanges = ['p360.pedidos.commands', 'p360.pedidos.retry', 'p360.pedidos.dlx',
                 'p360.commands', 'p360.queries', 'p360.retry', 'p360.dlx']
    queues, bindings, policies = [], [], []

    def queue(name, exchange, key, policy, arguments=None):
        queues.append(dict(name=name, vhost=BUSINESS, durable=True, auto_delete=False,
                           arguments={'x-queue-type': 'quorum', **(arguments or {})}))
        if exchange:
            bindings.append(dict(source=exchange, destination=name, destination_type='queue',
                                 routing_key=key, arguments={}))
        policies.append(dict(name='ep2-' + name, pattern=exact(name), priority=10,
                             **{'apply-to': 'quorum_queues'}, definition=policy))

    def source_policy(dlx, key, limit=5):
        return {'dead-letter-exchange': dlx, 'dead-letter-routing-key': key,
                'dead-letter-strategy': 'at-least-once', 'overflow': 'reject-publish',
                'delivery-limit': limit}

    queue('p360.pedidos.confirmacion.q', exchanges[0], 'pedido.confirmar.v1',
          source_policy(exchanges[2], 'pedido.confirmar.failed'))
    for seconds in (5, 30, 120):
        queue(f'p360.pedidos.confirmacion.retry.{seconds}s.q', exchanges[1],
              f'pedido.confirmar.retry.{seconds}s',
              {'dead-letter-strategy': 'at-least-once', 'overflow': 'reject-publish', 'delivery-limit': -1},
              {'x-message-ttl': seconds * 1000, 'x-dead-letter-exchange': exchanges[0],
               'x-dead-letter-routing-key': 'pedido.confirmar.v1'})
    retention = {'delivery-limit': -1, 'overflow': 'reject-publish'}
    queue('p360.pedidos.confirmacion.dlq', exchanges[2], 'pedido.confirmar.failed', retention)
    ttl = int(os.environ.get('RABBITMQ_SIMPLE_RETRY_MS', '1000'))
    if not 100 <= ttl <= 30000:
        raise ValueError('Simple retry TTL must be 100..30000 ms; coordinate operational changes.')
    for domain, operation, key, exchange in DOMAINS:
        prefix = f'p360.{domain}.{operation}'
        queue(prefix + '.q', exchange, key + '.v1', source_policy('p360.dlx', key + '.failed'))
        retry = source_policy(exchange, key + '.v1', -1)
        retry['message-ttl'] = ttl
        queue(prefix + '.retry.1s.q', 'p360.retry', key + '.retry.1s', retry)
        queue(prefix + '.dlq', 'p360.dlx', key + '.failed', retention)
    # Broker bound; #77 must set response expiration to remaining request budget, never renew it.
    queue('p360.bff.consultas.respuestas.q', None, None,
          {'message-ttl': 60000, 'max-length': 1000, 'overflow': 'reject-publish', 'delivery-limit': 5})
    return dict(exchanges=exchanges, queues=queues, bindings=bindings, policies=policies)


def accounts():
    # No configure permission for applications: pre-provision; Spring dynamic=false.
    result = [('p360-bootstrap', 'BOOTSTRAP_PASSWORD', BUSINESS, '^p360\\.', '^p360\\.', '^p360\\.', 'administrator'),
              ('p360-bootstrap', 'BOOTSTRAP_PASSWORD', SANDBOX, '^demo\\.', '^demo\\.', '^demo\\.', 'administrator'),
              ('p360-pagos-publisher', 'PAGOS_PUBLISHER_PASSWORD', BUSINESS, DENY, exact('p360.pedidos.commands'), DENY, ''),
              ('p360-pedidos-consumer', 'PEDIDOS_CONSUMER_PASSWORD', BUSINESS, DENY,
               exact('p360.pedidos.retry'), exact('p360.pedidos.confirmacion.q'), ''),
              ('p360-bff', 'BFF_PASSWORD', BUSINESS, DENY, exact('p360.queries'), exact('p360.bff.consultas.respuestas.q'), ''),
              ('p360-pedidos-carrito-publisher', 'PEDIDOS_CARRITO_PUBLISHER_PASSWORD', BUSINESS, DENY,
               exact('p360.commands'), DENY, ''),
              ('p360-replay', 'REPLAY_PASSWORD', BUSINESS, DENY,
               exact('p360.pedidos.commands', 'p360.commands'), exact(*[q['name'] for q in inventory()['queues'] if q['name'].endswith('.dlq')]), ''),
              ('p360-admin-demo', 'ADMIN_DEMO_PASSWORD', SANDBOX, '^demo\\.', '^demo\\.', '^demo\\.', 'management')]
    for domain, operation, _, _ in DOMAINS:
        writes = ['p360.retry'] + ([] if domain == 'carrito' else ['amq.default'])
        result.append((f'p360-{domain}-consumer', domain.upper() + '_CONSUMER_PASSWORD', BUSINESS,
                       DENY, exact(*writes), exact(f'p360.{domain}.{operation}.q'), ''))
    return result


class Api:
    def __init__(self, user='p360-bootstrap', password=None):
        self.base = f'http://127.0.0.1:{os.environ.get("RABBITMQ_MANAGEMENT_PORT", "15679")}/api/'
        token = base64.b64encode(f'{user}:{password or os.environ["BOOTSTRAP_PASSWORD"]}'.encode()).decode()
        self.headers = {'Authorization': 'Basic ' + token, 'Content-Type': 'application/json'}

    def call(self, method, path, data=None, missing=False):
        request = urllib.request.Request(self.base + path, method=method, headers=self.headers,
                                         data=None if data is None else json.dumps(data).encode())
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as failure:
            if missing and failure.code == 404:
                return None
            # Do not print credentials/request headers.
            raise RuntimeError(f'Management {method} {path}: HTTP {failure.code}') from None


def encoded(value):
    return urllib.parse.quote(value, safe='')


def preflight(api, expected):
    """Complete read-only inventory before first mutation. Refuse incompatible existing resources."""
    errors = []
    vh = api.call('GET', f'vhosts/{BUSINESS}', missing=True)
    if vh and vh.get('default_queue_type') != 'quorum':
        errors.append(f'{BUSINESS}: existing default queue type is not quorum')
    sandbox = api.call('GET', f'vhosts/{SANDBOX}', missing=True)
    if sandbox and sandbox.get('default_queue_type') != 'classic':
        errors.append(f'{SANDBOX}: existing default queue type is not classic')
    existing = {q['name']: q for q in api.call('GET', f'queues/{BUSINESS}', missing=True) or []}
    for q in expected['queues']:
        old = existing.get(q['name'])
        if old and (old['type'] != 'quorum' or old['arguments'] != q['arguments'] or
                    old['durable'] is not True or old['auto_delete'] is not False or old.get('exclusive')):
            errors.append(f'{q["name"]}: existing type/arguments/flags incompatible; inspect and plan manual migration')
    if set(existing) - {q['name'] for q in expected['queues']}:
        errors.append('Unexpected business queues: inventory and obtain approval before provisioning')
    for exchange in api.call('GET', f'exchanges/{BUSINESS}', missing=True) or []:
        if exchange['name'] in expected['exchanges'] and (exchange['type'] != 'direct' or not exchange['durable'] or
                                                         exchange['auto_delete'] or exchange['internal'] or exchange['arguments']):
            errors.append(f'{exchange["name"]}: incompatible exchange')
        elif exchange['name'].startswith('p360.') and exchange['name'] not in expected['exchanges']:
            errors.append(f'{exchange["name"]}: unexpected custom exchange')
    owned = {p['name'] for p in expected['policies']}
    operator_policies = api.call('GET', f'operator-policies/{BUSINESS}', missing=True) or []
    for p in (api.call('GET', f'policies/{BUSINESS}', missing=True) or []) + operator_policies:
        # Operator policies are never owned; even an identical name must be reviewed.
        is_operator = p in operator_policies
        if (p['name'] not in owned or is_operator) and any(re.search(p['pattern'], q['name']) for q in expected['queues']):
            errors.append(f'{p["name"]}: unmanaged policy affects expected queues')
    for binding in api.call('GET', f'bindings/{BUSINESS}', missing=True) or []:
        if binding['source'].startswith('p360.'):
            keys = ('source', 'destination', 'destination_type', 'routing_key', 'arguments')
            if not any(all(binding[k] == b[k] for k in keys) for b in expected['bindings']):
                errors.append('Unexpected custom binding: ' + binding['source'] + ' -> ' + binding['destination'])
    if errors:
        raise RuntimeError('PREFLIGHT BLOCKED; no changes made:\n' + '\n'.join(errors))


def provision():
    api, expected = Api(), inventory()
    for _, variable, *_ in accounts():
        if len(os.environ.get(variable, '')) < 24:
            raise ValueError(f'{variable}: provide a generated password (minimum 24 characters) in private .env')
    preflight(api, expected)
    api.call('PUT', f'vhosts/{BUSINESS}', {'default_queue_type': 'quorum', 'description': 'EP2 business'})
    api.call('PUT', f'vhosts/{SANDBOX}', {'default_queue_type': 'classic', 'description': 'RabbitAdmin isolated demo'})
    for user, variable, vhost, configure, write, read, tags in accounts():
        # Bootstrap account created by Docker on blank volume; never silently rotate it here.
        if user != 'p360-bootstrap':
            api.call('PUT', f'users/{user}', {'password': os.environ[variable], 'tags': tags})
        api.call('PUT', f'permissions/{vhost}/{user}', dict(configure=configure, write=write, read=read))
    # Targets/routes exist before source policies enable DLX. Consumers remain off until verification.
    for name in expected['exchanges']:
        api.call('PUT', f'exchanges/{BUSINESS}/{name}', dict(type='direct', durable=True, auto_delete=False, internal=False, arguments={}))
    for q in expected['queues']:
        api.call('PUT', f'queues/{BUSINESS}/{q["name"]}', {k: q[k] for k in ('durable', 'auto_delete', 'arguments')})
    for b in expected['bindings']:
        api.call('POST', f'bindings/{BUSINESS}/e/{b["source"]}/q/{b["destination"]}',
                 {k: b[k] for k in ('routing_key', 'arguments')})
    for p in expected['policies']:
        api.call('PUT', f'policies/{BUSINESS}/{p["name"]}', {k: v for k, v in p.items() if k != 'name'})
    verify()


def verify():
    # Management statistics are eventually refreshed after restart/policy update.
    for attempt in range(30):
        try:
            _verify()
            return
        except AssertionError:
            if attempt == 29:
                raise
            time.sleep(1)


def _verify():
    def require(condition, detail):
        if not condition:
            raise AssertionError(detail)
    api, expected = Api(), inventory()
    preflight(api, expected)
    actual = api.call('GET', f'queues/{BUSINESS}')
    require(len(actual) == 21, f'Queue count: {len(actual)}')
    require({e['name'] for e in api.call('GET', f'exchanges/{BUSINESS}') if e['name'].startswith('p360.')} == set(expected['exchanges']), 'Custom exchanges differ')
    for q in actual:
        p = next(p for p in expected['policies'] if re.fullmatch(p['pattern'], q['name']))
        require(q.get('policy') == p['name'], q['name'])
        require(q['effective_policy_definition'] == p['definition'], (q['name'], q['effective_policy_definition']))
    actual_bindings = api.call('GET', f'bindings/{BUSINESS}')
    for b in expected['bindings']:
        require(any(all(old[k] == b[k] for k in b) for old in actual_bindings), b)
    for user, _, vhost, configure, write, read, _ in accounts():
        permissions = api.call('GET', f'users/{user}/permissions')
        require(len(permissions) == (2 if user == 'p360-bootstrap' else 1), user)
        permission = next(p for p in permissions if p['vhost'] == vhost)
        require(all(permission[key] == value for key, value in dict(configure=configure, write=write, read=read).items()), user)
    require(api.call('GET', f'vhosts/{SANDBOX}')['default_queue_type'] == 'classic', 'Sandbox default queue type')
    print('VERIFIED: 21 quorum queues, 7 direct exchanges, 20 custom bindings, exact policies, 2 vhosts, 12 users, 13 permission entries')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['preflight', 'provision', 'verify', 'inventory'])
    args = parser.parse_args()
    try:
        load_env()
        if args.action == 'inventory':
            print(json.dumps(inventory(), indent=2))
        elif args.action == 'preflight':
            preflight(Api(), inventory())
            print('PREFLIGHT OK (read-only)')
        else:
            globals()[args.action]()
    except Exception as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
