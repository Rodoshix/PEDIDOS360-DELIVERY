"""Reuse #69's 13 real broker tests over TLS, on a NEW isolated local stack only."""
import importlib.util
import os
from pathlib import Path
import ssl
import subprocess
import unittest

import pika
import platform_source as p
import provision
import tools

tools.load_env()
if os.environ['EP2_EXECUTION_SCOPE'] != 'LOCAL_TEST' or os.environ.get('EP2_PLATFORM_TESTS') != '1':
    raise RuntimeError('Tests require the explicitly enabled isolated LOCAL_TEST fixture')
provision.load_secrets()
p.Api = provision.HttpsApi
p.load_env = tools.load_env
p.ROOT = tools.ROOT  # Only this test process: keep test evidence in the isolated package.
spec = importlib.util.spec_from_file_location('ep2_platform_tests',
                                             Path(p.__file__).with_name('test_platform.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
base.COMPOSE = tools.compose()


def connection(user='p360-bootstrap', vhost=p.BUSINESS):
    account = next(a for a in p.accounts() if a[0] == user)
    context = ssl.create_default_context(cafile=os.environ['EP2_CA_FILE'])
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return pika.BlockingConnection(pika.ConnectionParameters(
        '127.0.0.1', 5782, vhost, pika.PlainCredentials(user, os.environ[account[1]]),
        ssl_options=pika.SSLOptions(context, 'p360-rabbitmq'),
        socket_timeout=5, blocked_connection_timeout=5, connection_attempts=1))


base.connection = connection
original_resources = base.resources


def resources(label):
    result = original_resources(label)
    result.pop('cpus', None)  # +S 2:2 and shares are not a hard CPU limit.
    result['cpu_shares'] = 512
    return result


base.resources = resources


class TlsPlatformTests(base.PlatformTests):
    def test_11_persistent_message_survives_container_stop_start(self):
        identity = base.publish('p360.pedidos.dlx', 'pedido.confirmar.failed')
        subprocess.run(tools.compose('stop', '-t', '30', 'rabbitmq'), check=True)
        subprocess.run(tools.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '150', 'rabbitmq'), check=True)
        p.verify()
        _, properties, _ = base.receive(base.DLQ, identity)
        self.assertEqual(2, properties.delivery_mode)

    def test_14_amqps_untrusted_ca_rejected(self):
        parameters = pika.ConnectionParameters('127.0.0.1', 5782,
            ssl_options=pika.SSLOptions(ssl.create_default_context(), 'p360-rabbitmq'), socket_timeout=5)
        with self.assertRaises(ssl.SSLCertVerificationError) as rejected:
            pika.BlockingConnection(parameters)
        self.assertIn('self-signed', rejected.exception.verify_message)

    def test_15_amqps_wrong_san_rejected(self):
        context = ssl.create_default_context(cafile=os.environ['EP2_CA_FILE'])
        parameters = pika.ConnectionParameters('127.0.0.1', 5782,
            ssl_options=pika.SSLOptions(context, 'wrong.example.test'), socket_timeout=5)
        with self.assertRaises(ssl.SSLCertVerificationError) as rejected:
            pika.BlockingConnection(parameters)
        self.assertIn('Hostname mismatch', rejected.exception.verify_message)

    def test_16_tls_listeners_health_loopback_and_inventory(self):
        tools.verify()

    def test_17_https_untrusted_ca_rejected(self):
        api = provision.HttpsApi()
        import urllib.request
        api.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), provision.NoRedirect(),
                       urllib.request.HTTPSHandler(context=ssl.create_default_context()))
        import urllib.error
        with self.assertRaises(urllib.error.URLError):
            api.call('GET', 'overview')


if __name__ == '__main__':
    unittest.main(verbosity=2)
