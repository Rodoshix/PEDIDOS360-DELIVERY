"""Offline/read-only configuration guards, without touching running brokers."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import demo as d


class ConfigTests(unittest.TestCase):
    def test_fixed_local_compose_isolation_and_limits(self):
        model = json.loads(d.run(*d.compose('config', '--format', 'json')))
        self.assertEqual({'rabbit-demo-1', 'rabbit-demo-2'}, set(model['services']))
        self.assertTrue(model['networks']['demo']['internal'])
        self.assertEqual({'node1', 'node2'}, set(model['volumes']))
        for name, s in model['services'].items():
            self.assertEqual(d.IMAGE, s['image'])
            self.assertEqual('rabbit@' + name, s['environment']['RABBITMQ_NODENAME'])
            self.assertEqual({'demo', 'local-access'}, set(s['networks']))
            self.assertEqual(768 * 1024**2, int(s['mem_limit']))
            self.assertEqual(1, float(s['cpus']))
            self.assertNotIn('depends_on', s)  # Existing peers must restart together.
            for p in s['ports']:
                self.assertEqual('127.0.0.1', p['host_ip'])
                self.assertIn(p['target'], (5671, 15671))
            self.assertFalse(any('PASSWORD' in k or 'COOKIE' in k for k in s['environment']))
            self.assertEqual({'demo_admin', 'demo_cookie', 'demo_marker', 'demo_ca', 'demo_cert', 'demo_key'},
                             {v['source'] for v in s['secrets']})
        self.assertFalse(any('ca-key' in v['file'] or 'PUBLISHER_PASSWORD' in v['file']
                             or 'CONSUMER_PASSWORD' in v['file'] for v in model['secrets'].values()))

    def test_topology_only_synthetic_names(self):
        self.assertEqual('demo73', d.TOPOLOGY['vhost'])
        self.assertEqual({'direct', 'topic'}, {x['type'] for x in d.TOPOLOGY['exchanges']})
        self.assertEqual(3, len(d.TOPOLOGY['queues']))
        names = {name for q in d.TOPOLOGY['queues'] for name in (q['name'], q['dlq'])}
        self.assertEqual(6, len(names)); self.assertTrue(all(n.startswith('demo73.') for n in names))

    def test_remote_docker_override_refused_before_probe(self):
        with patch.dict(os.environ, {'DOCKER_HOST': 'tcp://remote.example:2376'}), patch.object(d, 'run') as command:
            with self.assertRaisesRegex(ValueError, 'overrides'): d.local_docker()
            command.assert_not_called()
        with patch.dict(os.environ, {}, clear=True), patch.object(d, 'run', return_value=json.dumps([
                {'Endpoints': {'docker': {'Host': 'ssh://remote.example'}}}])):
            with self.assertRaisesRegex(ValueError, 'Local Docker'): d.local_docker()

    def test_incomplete_config_and_reused_secrets_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix='p360-73-test-') as root:
            with patch.object(d, 'PRIVATE', Path(root)), patch.object(d, 'local_docker'), \
                 patch.object(d.ssl, 'create_default_context') as tls:
                with self.assertRaises(FileNotFoundError): d.initialized()
                (Path(root) / 'LOCAL_SYNTHETIC_CLUSTER_73').write_text('LOCAL_SYNTHETIC_CLUSTER_73')
                with self.assertRaises(FileNotFoundError): d.initialized()
                for name in ['ADMIN_PASSWORD', 'PUBLISHER_PASSWORD', 'CONSUMER_PASSWORD', 'ERLANG_COOKIE']:
                    (Path(root) / name).write_text('synthetic-test-only-value-' * 3)
                with self.assertRaisesRegex(ValueError, 'independent'): d.initialized()
                tls.assert_not_called()

    def test_management_redirect_and_destructive_calls_refused(self):
        with self.assertRaisesRegex(ValueError, 'Redirects'):
            d.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://remote.example')
        api = object.__new__(d.Api)
        for method, path in [('DELETE', 'queues/demo73/q'), ('GET', 'https://remote.example'), ('GET', '../other')]:
            with self.assertRaisesRegex(ValueError, 'Unsafe'): api.call(method, path)


if __name__ == '__main__': unittest.main(verbosity=2)
