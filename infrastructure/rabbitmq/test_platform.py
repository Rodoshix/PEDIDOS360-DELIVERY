"""Real broker probes on the dedicated, idle local EP2 stack. Never purge/delete queues."""
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid

import pika
import platform_control as p

COMPOSE = ['docker', 'compose', '-f', str(p.ROOT / 'compose.yml'), '--env-file', str(p.ROOT / '.env')]
MAIN = 'p360.pedidos.confirmacion.q'
DLQ = 'p360.pedidos.confirmacion.dlq'


def connection(user='p360-bootstrap', vhost=p.BUSINESS):
    account = next(a for a in p.accounts() if a[0] == user)
    return pika.BlockingConnection(pika.ConnectionParameters(
        '127.0.0.1', int(os.environ.get('RABBITMQ_AMQP_PORT', 5679)), vhost,
        pika.PlainCredentials(user, os.environ[account[1]]), socket_timeout=5,
        blocked_connection_timeout=5, connection_attempts=1))


def publish(exchange, key, payload=None, user='p360-bootstrap', vhost=p.BUSINESS):
    identity = str(uuid.uuid4())
    with connection(user, vhost) as conn:
        channel = conn.channel()
        channel.confirm_delivery()
        channel.basic_publish(exchange, key, payload or identity.encode(), mandatory=True,
                              properties=pika.BasicProperties(delivery_mode=2, message_id=identity,
                                                              correlation_id=identity, content_type='application/json'))
    return identity


def receive(queue, expected, timeout=10, settle=True, user='p360-bootstrap'):
    with connection(user) as conn:
        channel = conn.channel()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            method, props, body = channel.basic_get(queue, auto_ack=False)
            if method:
                if props.message_id != expected:
                    raise AssertionError('Unexpected message; left unsettled, connection will close. Stop tests.')
                if settle:
                    channel.basic_ack(method.delivery_tag)
                return method, props, body
            conn.process_data_events(time_limit=0.1)
    raise AssertionError(f'Message absent in {queue} after {timeout}s')


def resources(label):
    container = subprocess.check_output(COMPOSE + ['ps', '-q', 'rabbitmq'], text=True).strip()
    stats = json.loads(subprocess.check_output(['docker', 'stats', '--no-stream', '--format', '{{json .}}', container], text=True))
    disk = subprocess.check_output(['docker', 'exec', container, 'du', '-sk', '/var/lib/rabbitmq/mnesia'], text=True).split()[0]
    for _ in range(30):
        overview = p.Api().call('GET', 'nodes')[0]
        if 'mem_used' in overview and 'disk_free' in overview:
            break
        time.sleep(1)
    return dict(label=label, container_memory=stats['MemUsage'], cpu_percent=stats['CPUPerc'],
                mnesia_kib=int(disk), erlang_memory_bytes=overview['mem_used'],
                disk_free_bytes=overview['disk_free'], container_limit='768MiB', cpus=2)


class PlatformTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        p.load_env()
        if os.environ.get('EP2_PLATFORM_TESTS') != '1':
            raise RuntimeError('Set EP2_PLATFORM_TESTS=1 only for the dedicated, idle local test stack.')
        p.verify()
        with connection() as conn:
            for q in p.inventory()['queues']:
                assert conn.channel().queue_declare(q['name'], passive=True).method.message_count == 0, 'Stop: business queue not empty'
        assert not p.Api().call('GET', f'consumers/{p.BUSINESS}'), 'Stop: application consumers must be stopped for infrastructure probes'

    def test_01_inventory_types_policies_bindings_permissions(self):
        p.verify()

    def test_02_health_management(self):
        self.assertTrue(p.Api().call('GET', 'nodes')[0]['running'])
        subprocess.run(COMPOSE + ['exec', '-T', 'rabbitmq', 'rabbitmq-diagnostics', '-q', 'check_local_alarms'], check=True)
        self.assertEqual('4.1.8', p.Api().call('GET', 'overview')['rabbitmq_version'])
        health = subprocess.check_output(['docker', 'inspect', '--format', '{{.State.Health.Status}}',
                                         subprocess.check_output(COMPOSE + ['ps', '-q', 'rabbitmq'], text=True).strip()], text=True).strip()
        self.assertEqual('healthy', health)

    def test_03_each_binding_and_definitive_nack_to_domain_dlq(self):
        for domain, operation, key, exchange in p.DOMAINS + [('pedidos', 'confirmacion', 'pedido.confirmar', 'p360.pedidos.commands')]:
            with self.subTest(domain=domain):
                queue = f'p360.{domain}.{operation}.q'
                identity = publish(exchange, key + '.v1')
                with connection() as conn:
                    channel = conn.channel()
                    deadline = time.monotonic() + 5
                    method, props, body = (None, None, None)
                    while method is None and time.monotonic() < deadline:
                        method, props, body = channel.basic_get(queue, auto_ack=False)
                        conn.process_data_events(time_limit=0.05)
                    self.assertIsNotNone(method)
                    self.assertEqual(identity, props.message_id)
                    channel.basic_nack(method.delivery_tag, requeue=False)
                _, props, _ = receive(queue[:-2] + '.dlq', identity)
                self.assertEqual('rejected', props.headers['x-death'][0]['reason'])

    def test_04_retry_all_eight_queues_real_ttl_no_consumer(self):
        expected = p.inventory()
        policies = {policy['name']: policy['definition'] for policy in expected['policies']}
        for q in expected['queues']:
            if '.retry.' not in q['name']:
                continue
            with self.subTest(queue=q['name']):
                policy = policies['ep2-' + q['name']]
                args = q['arguments']
                ttl = args.get('x-message-ttl', policy.get('message-ttl'))
                destination = args.get('x-dead-letter-exchange', policy.get('dead-letter-exchange'))
                key = args.get('x-dead-letter-routing-key', policy.get('dead-letter-routing-key'))
                b = next(b for b in expected['bindings'] if b['destination'] == q['name'])
                self.assertEqual(0, p.Api().call('GET', f'queues/{p.BUSINESS}/{q["name"]}')['consumers'])
                started = time.monotonic()
                identity = publish(b['source'], b['routing_key'])
                main = next(b['destination'] for b in expected['bindings'] if b['source'] == destination and b['routing_key'] == key)
                _, props, body = receive(main, identity, timeout=ttl / 1000 + 15)
                self.assertGreaterEqual(time.monotonic() - started, ttl / 1000 - 0.1)
                self.assertEqual('expired', props.headers['x-death'][0]['reason'])
                self.assertEqual(q['name'], props.headers['x-death'][0]['queue'])
                self.assertEqual(identity.encode(), body)

    def test_05_publisher_scope_and_confirm_return(self):
        identity = publish('p360.pedidos.commands', 'pedido.confirmar.v1', user='p360-pagos-publisher')
        receive(MAIN, identity)
        with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
            publish('p360.commands', 'carrito.vaciar-por-pedido.v1', user='p360-pagos-publisher')
        self.assertEqual(403, denied.exception.reply_code)
        with self.assertRaises(pika.exceptions.UnroutableError):
            publish('p360.pedidos.commands', 'no.binding', user='p360-pagos-publisher')
        with connection('p360-pagos-publisher') as conn:
            with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                conn.channel().queue_declare('p360.forbidden', durable=True)
            self.assertEqual(403, denied.exception.reply_code)

    def test_06_each_consumer_reads_own_queue_only(self):
        for domain, operation, key, exchange in p.DOMAINS + [('pedidos', 'confirmacion', 'pedido.confirmar', 'p360.pedidos.commands')]:
            with self.subTest(domain=domain):
                user = f'p360-{domain}-consumer'
                identity = publish(exchange, key + '.v1')
                receive(f'p360.{domain}.{operation}.q', identity, user=user)
                other = MAIN if domain != 'pedidos' else 'p360.usuarios.consultas.q'
                with connection(user) as conn:
                    with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                        conn.channel().basic_get(other)
                    self.assertEqual(403, denied.exception.reply_code)

    def test_07_consumers_publish_retry_and_query_replies(self):
        for domain, operation, key, _ in p.DOMAINS:
            with self.subTest(domain=domain):
                user = f'p360-{domain}-consumer'
                identity = publish('p360.retry', key + '.retry.1s', user=user)
                receive(f'p360.{domain}.{operation}.q', identity, timeout=15)
                if domain != 'carrito':
                    identity = publish('', 'p360.bff.consultas.respuestas.q', user=user)
                    receive('p360.bff.consultas.respuestas.q', identity, user='p360-bff')
                if domain in ('usuarios', 'productos'):
                    identity = publish('p360.dlx', key + '.failed', user=user)
                    receive(f'p360.{domain}.{operation}.dlq', identity)
                    with connection(user) as conn:
                        with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                            conn.channel().queue_declare('p360.forbidden', durable=True)
                        self.assertEqual(403, denied.exception.reply_code)
                else:
                    with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                        publish('p360.dlx', key + '.failed', user=user)
                    self.assertEqual(403, denied.exception.reply_code)

    def test_08_bff_query_and_response_permissions(self):
        identity = publish('p360.queries', 'usuario.consultar-actual.v1', user='p360-bff')
        receive('p360.usuarios.consultas.q', identity, user='p360-usuarios-consumer')
        with self.assertRaises(pika.exceptions.ChannelClosedByBroker):
            publish('p360.commands', 'carrito.vaciar-por-pedido.v1', user='p360-bff')

    def test_09_admin_demo_isolation(self):
        with self.assertRaises(pika.exceptions.ProbableAccessDeniedError):
            connection('p360-admin-demo', p.BUSINESS)
        with connection('p360-admin-demo', p.SANDBOX) as conn:
            channel = conn.channel()
            channel.exchange_declare('demo.platform', exchange_type='direct', durable=True)
            channel.queue_declare('demo.platform.q', durable=True, arguments={'x-queue-type': 'classic'})
            channel.queue_bind('demo.platform.q', 'demo.platform', 'demo')
            with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                conn.channel().queue_declare(MAIN, durable=True)
            self.assertEqual(403, denied.exception.reply_code)
        admin = p.Api('p360-admin-demo', os.environ['ADMIN_DEMO_PASSWORD'])
        with self.assertRaises(RuntimeError):
            admin.call('GET', f'queues/{p.BUSINESS}/{MAIN}')

    def test_10_main_delivery_limit_5_and_dlq_retains_after_25_closes(self):
        identity = publish('p360.pedidos.commands', 'pedido.confirmar.v1')
        counts = []
        for _ in range(8):
            try:
                _, props, _ = receive(MAIN, identity, timeout=0.5, settle=False)
                counts.append((props.headers or {}).get('x-delivery-count', 0))
            except AssertionError:
                break
        self.assertEqual(5, max(counts))
        for _ in range(25):
            receive(DLQ, identity, settle=False)
        _, props, _ = receive(DLQ, identity)
        self.assertEqual('delivery_limit', props.headers['x-death'][0]['reason'])
        self.assertGreaterEqual(props.headers['x-delivery-count'], 25)

    def test_11_persistent_message_survives_container_stop_start(self):
        identity = publish('p360.pedidos.dlx', 'pedido.confirmar.failed')
        subprocess.run(COMPOSE + ['stop', '-t', '30', 'rabbitmq'], check=True)
        subprocess.run(COMPOSE + ['start', '--wait', 'rabbitmq'], check=True)
        p.verify()
        _, props, _ = receive(DLQ, identity)
        self.assertEqual(2, props.delivery_mode)

    def test_12_resources_idle_and_controlled_backlog(self):
        idle = resources('idle_after_tests')
        messages = []
        for queue, exchange, key in [(DLQ, 'p360.pedidos.dlx', 'pedido.confirmar.failed'),
                                      ('p360.carrito.vaciado.q', 'p360.commands', 'carrito.vaciar-por-pedido.v1')]:
            with connection() as conn:
                channel = conn.channel()
                channel.confirm_delivery()
                for _ in range(500):
                    identity = str(uuid.uuid4())
                    channel.basic_publish(exchange, key, b'x' * 1024, mandatory=True,
                                          properties=pika.BasicProperties(delivery_mode=2, message_id=identity))
                    messages.append((queue, identity))
        backlog = resources('1000_persistent_messages_1KiB_in_2_queues')
        with connection() as conn:
            channel = conn.channel()
            for queue, identity in messages:
                method, props, _ = channel.basic_get(queue, auto_ack=False)
                self.assertIsNotNone(method)
                self.assertEqual(identity, props.message_id)
                channel.basic_ack(method.delivery_tag)
        p.ROOT.joinpath('evidence').mkdir(exist_ok=True)
        p.ROOT.joinpath('evidence/resources.json').write_text(json.dumps([idle, backlog, resources('after_drain')], indent=2))
        print(json.dumps([idle, backlog], indent=2))

    def test_13_preflight_refuses_real_classic_without_mutation(self):
        # Query the real sandbox classic queue through a read-only adapter. No deletion/conversion.
        api = p.Api()
        class SandboxView:
            def call(self, method, path, **kwargs):
                assert method == 'GET', 'Preflight must never mutate'
                if path == 'vhosts/pedidos360':
                    return {'default_queue_type': 'quorum'}
                if path == 'queues/pedidos360':
                    return api.call('GET', 'queues/pedidos360-admin-demo')
                return []
        expected = dict(queues=[dict(name='demo.platform.q', durable=True, auto_delete=False,
                                    arguments={'x-queue-type': 'quorum'})], exchanges=[], bindings=[], policies=[])
        with self.assertRaisesRegex(RuntimeError, 'PREFLIGHT BLOCKED'):
            p.preflight(SandboxView(), expected)
        self.assertEqual('classic', api.call('GET', 'queues/pedidos360-admin-demo/demo.platform.q')['type'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
