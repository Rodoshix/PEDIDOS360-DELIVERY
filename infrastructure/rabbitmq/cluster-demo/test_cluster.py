"""Real TLS/AMQP tests, synthetic queues only. Includes SIGKILL of demo node 2."""
import json
import os
import ssl
import socket
import subprocess
import sys
import time
import unittest
import urllib.error
from uuid import uuid4
import pika
import demo as d


def connection(node=1, user='demo73-publisher', password=None, hostname=None, trusted=True):
    if node not in (1, 2): raise ValueError('Fixed demo node only')
    ctx = ssl.create_default_context(cafile=str(d.PRIVATE / 'tls/ca.pem') if trusted else None)
    return pika.BlockingConnection(pika.ConnectionParameters('127.0.0.1', 5782 + node, 'demo73',
        pika.PlainCredentials(user, password or d.secret(user.split('-')[1].upper() + '_PASSWORD')),
        ssl_options=pika.SSLOptions(ctx, hostname or f'rabbit-demo-{node}'), socket_timeout=5,
        stack_timeout=8, blocked_connection_timeout=5, connection_attempts=1))


def publish(exchange='demo73.direct', key='order.created', node=1):
    identity = str(uuid4())
    with connection(node) as conn:
        ch = conn.channel(); ch.confirm_delivery()
        ch.basic_publish(exchange, key, json.dumps({'synthetic': True, 'id': identity}).encode(),
                         pika.BasicProperties(message_id=identity, delivery_mode=2, content_type='application/json'), mandatory=True)
    return identity


def receive(queue, identity, node=2, reject=False):
    with connection(node, 'demo73-consumer') as conn:
        ch = conn.channel(); end = time.monotonic() + 12
        while time.monotonic() < end:
            method, props, body = ch.basic_get(queue, auto_ack=False)
            if method:
                if props.message_id != identity: raise AssertionError('Unexpected synthetic message; left unacked')
                if json.loads(body)['id'] != identity: raise AssertionError('Payload mismatch')
                if reject: ch.basic_nack(method.delivery_tag, requeue=False)
                else: ch.basic_ack(method.delivery_tag)
                return method, props
            time.sleep(.2)
        raise AssertionError('Confirmed synthetic message not received')


class ClusterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get('DEMO73_REAL_TESTS') != '1':
            raise unittest.SkipTest('Explicit DEMO73_REAL_TESTS=1 required')
        d.verify()
        # Tests never purge data. Refuse a dirty or shared demo environment.
        for q in d.Api().call('GET', 'queues/demo73'):
            if q.get('messages', 0) or q.get('consumers', 0): raise ValueError('Synthetic test queues must be idle/empty')

    def test_01_membership_replication_and_isolation(self):
        self.assertTrue(d.isolation()); self.assertEqual(d.NODES, d.members())
        api = d.Api()
        exchanges = {x['name']: x['type'] for x in api.call('GET', 'exchanges/demo73') if x['name'].startswith('demo73.')}
        self.assertEqual({x['name']: x['type'] for x in d.TOPOLOGY['exchanges']}, exchanges)
        expected = {(q['exchange'], q['name'], q['key']) for q in d.TOPOLOGY['queues']}
        expected |= {('demo73.dlx', q['dlq'], q['failed']) for q in d.TOPOLOGY['queues']}
        expected.add(('demo73.topic', 'demo73.events.q', 'audit.#'))
        actual = {(b['source'], b['destination'], b['routing_key']) for b in api.call('GET', 'bindings/demo73')
                  if b['source'].startswith('demo73.')}
        self.assertEqual(expected, actual)
        policies = api.call('GET', 'policies/demo73')
        self.assertEqual(3, len(policies))
        self.assertEqual({q['name'] for q in d.TOPOLOGY['queues']}, {p['name'] for p in policies})
        for policy in policies:
            self.assertEqual('quorum_queues', policy['apply-to'])
            self.assertEqual('at-least-once', policy['definition']['dead-letter-strategy'])
            self.assertEqual('reject-publish', policy['definition']['overflow'])
        permissions = {p['user']: p for p in api.call('GET', 'vhosts/demo73/permissions')}
        self.assertEqual(('^$', '^demo73\\.(direct|topic)$', '^$'),
                         tuple(permissions['demo73-publisher'][k] for k in ['configure', 'write', 'read']))
        self.assertEqual(('^$', '^$', '^demo73\\.(orders|payments|events)\\.(q|dlq)$'),
                         tuple(permissions['demo73-consumer'][k] for k in ['configure', 'write', 'read']))
        for node in (1, 2):
            api = d.Api(node)
            self.assertEqual({'demo73'}, {v['name'] for v in api.call('GET', 'vhosts')})
            self.assertEqual({'demo73-admin', 'demo73-publisher', 'demo73-consumer'},
                             {u['name'] for u in api.call('GET', 'users')})
            for q in api.call('GET', 'queues/demo73'):
                self.assertEqual('quorum', q['type']); self.assertEqual(d.NODES, set(q['members']))
                self.assertEqual(d.NODES, set(q['online']))
        # Real replication by Raft, not a statement about mirrored classic queues.
        for q in d.TOPOLOGY['queues']:
            status = d.run(*d.compose('exec', '-T', 'rabbit-demo-1', 'rabbitmq-queues', 'quorum_status', '--vhost', 'demo73', q['name']))
            self.assertIn('rabbit@rabbit-demo-1', status); self.assertIn('rabbit@rabbit-demo-2', status)

    def test_02_direct_confirm_and_cross_node_ack(self):
        for key, queue in [('order.created', 'demo73.orders.q'), ('payment.created', 'demo73.payments.q')]:
            identity = publish(key=key); _, props = receive(queue, identity)
            self.assertEqual(2, props.delivery_mode)

    def test_03_topic_star_hash_and_non_matches(self):
        for key in ['demo.order.created', 'demo.payment.created', 'audit', 'audit.created.any.depth']:
            receive('demo73.events.q', publish('demo73.topic', key))
        for key in ['demo.order.updated', 'demo.order.extra.created', 'other.order.created']:
            with self.assertRaises(pika.exceptions.UnroutableError): publish('demo73.topic', key)

    def test_04_direct_mandatory_return(self):
        with self.assertRaises(pika.exceptions.UnroutableError): publish(key='order.unknown')

    def test_05_nack_to_three_corresponding_dlqs(self):
        for q in d.TOPOLOGY['queues']:
            key = 'demo.order.created' if q['exchange'].endswith('topic') else q['key']
            identity = publish(q['exchange'], key)
            receive(q['name'], identity, reject=True)
            _, props = receive(q['dlq'], identity)
            death = props.headers['x-death'][0]
            self.assertEqual(q['name'], death['queue']); self.assertEqual('rejected', death['reason'])

    def test_06_permissions_and_invalid_credentials(self):
        with self.assertRaises(pika.exceptions.ProbableAuthenticationError):
            connection(password='invalid-synthetic-password')
        for user in ['demo73-publisher', 'demo73-consumer']:
            with connection(user=user) as conn:
                with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                    conn.channel().queue_declare('demo73.forbidden', durable=True)
                self.assertEqual(403, denied.exception.reply_code)
        with connection() as conn:
            with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                conn.channel().basic_get('demo73.orders.q')
            self.assertEqual(403, denied.exception.reply_code)
        with connection(user='demo73-consumer') as conn:
            ch = conn.channel(); ch.confirm_delivery()
            with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                ch.basic_publish('demo73.direct', 'order.created', b'synthetic', mandatory=True)
            self.assertEqual(403, denied.exception.reply_code)
        with connection() as conn:
            ch = conn.channel(); ch.confirm_delivery()
            with self.assertRaises(pika.exceptions.ChannelClosedByBroker) as denied:
                ch.basic_publish('amq.topic', 'forbidden', b'synthetic', mandatory=True)
            self.assertEqual(403, denied.exception.reply_code)

    def test_07_tls_authentication_and_no_verification_bypass(self):
        with self.assertRaises(ssl.SSLCertVerificationError): connection(trusted=False)
        with self.assertRaises(ssl.SSLCertVerificationError): connection(hostname='incorrect.synthetic.test')
        with self.assertRaises(urllib.error.HTTPError) as denied:
            d.Api(password='invalid-synthetic-password').call('GET', 'overview')
        self.assertEqual(401, denied.exception.code)

    def test_08_unacked_redelivery_no_duplicate_effect(self):
        identity = publish()
        with connection(2, 'demo73-consumer') as conn:
            method, props, _ = conn.channel().basic_get('demo73.orders.q', auto_ack=False)
            self.assertIsNotNone(method); self.assertEqual(identity, props.message_id)
            # Closing without ACK intentionally causes redelivery.
        method, _ = receive('demo73.orders.q', identity)
        self.assertTrue(method.redelivered)
        with connection(2, 'demo73-consumer') as conn:
            self.assertIsNone(conn.channel().basic_get('demo73.orders.q', auto_ack=False)[0])
        # Synthetic one-effect ACK assertion; not a production dedupe guarantee.

    def test_09_kill_node_quorum_loss_and_recovery(self):
        identity = publish()
        observations = {'failure': 'SIGKILL demo node 2', 'confirmed_before_failure': identity}
        try:
            subprocess.run(d.compose('kill', '-s', 'SIGKILL', 'rabbit-demo-2'), check=True)
            time.sleep(5)
            try:
                result = subprocess.run([sys.executable, str(d.ROOT / 'probe.py')], timeout=10, capture_output=True)
                self.assertEqual(2, result.returncode, 'Unexpected queue availability without majority')
                observations['read_probe'] = 'transport/refusal, no successful delivery'
            except subprocess.TimeoutExpired:
                observations['read_probe'] = 'timeout; child client terminated, no ACK or publish'
            status = json.loads(d.run('docker', 'inspect', 'p360-cluster73-demo-rabbit-demo-2-1'))[0]['State']
            self.assertFalse(status['Running']); observations['node2_exit_code'] = status['ExitCode']
        finally:
            subprocess.run(d.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '150', 'rabbit-demo-2'), check=True)
            d.wait_for(lambda: d.members() == d.NODES, timeout=120)
            d.wait_for(lambda: all(set(q.get('online', [])) == d.NODES for q in d.Api().call('GET', 'queues/demo73')), timeout=120)
        receive('demo73.orders.q', identity)
        d.verify(); observations['previous_confirmed_message'] = 'recovered and ACKed after majority restored'
        (d.ROOT / 'evidence/failure.json').write_text(json.dumps(observations, indent=2))

    def test_10_full_restart_persistent_messages(self):
        identities = []
        for q in d.TOPOLOGY['queues']:
            key = 'demo.order.created' if q['exchange'].endswith('topic') else q['key']
            identities.append((q['name'], publish(q['exchange'], key)))
        subprocess.run(d.compose('stop', '-t', '30'), check=True)
        # Start both together on existing data: no circular healthy dependency on rejoin.
        subprocess.run(d.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180',
                                'rabbit-demo-1', 'rabbit-demo-2'), check=True)
        d.wait_for(lambda: d.members() == d.NODES, timeout=120)
        d.wait_for(lambda: all(set(q.get('online', [])) == d.NODES for q in d.Api().call('GET', 'queues/demo73')), timeout=120)
        for queue, identity in identities: receive(queue, identity)
        d.verify()

    def test_11_amqp10_protocol_is_not_claimed_disabled(self):
        # Only a SASL protocol header, no credentials, transfer frames or exploit.
        ctx = ssl.create_default_context(cafile=str(d.PRIVATE / 'tls/ca.pem'))
        with socket.create_connection(('127.0.0.1', 5783), timeout=5) as raw:
            with ctx.wrap_socket(raw, server_hostname='rabbit-demo-1') as conn:
                header = b'AMQP\x03\x01\x00\x00'
                conn.sendall(header)
                received = b''
                while len(received) < 8:
                    part = conn.recv(8 - len(received))
                    if not part: break
                    received += part
                self.assertEqual(header, received)
        (d.ROOT / 'evidence/protocol.json').write_text(json.dumps({
            'AMQP_1_0_SASL_header': 'accepted; protocol is exposed on TLS listener',
            'clients': 'pika 1.3.2 uses AMQP 0-9-1',
            'GHSA_exploit_tested': False, 'user_id_used_as_authority': False}, indent=2))


if __name__ == '__main__': unittest.main(verbosity=2)
