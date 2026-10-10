"""Offline safety/transport tests. No Docker or AWS operations."""
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import platform_source as p
import provision
import tools


class GuardsTests(unittest.TestCase):
    def test_invalid_identity_blocks_before_startup_probes(self):
        env = dict(tools.SAFE, EP2_EXECUTION_SCOPE='LOCAL_TEST',
                   EP2_BACKEND_NETWORK='isolated', RABBITMQ_DATA_DIR='/temporary/data',
                   EP2_PRIVATE_DIR='/temporary/private')
        def command(args, **kwargs):
            if args[:3] == ['docker', 'network', 'inspect']:
                return '[{"Internal":true,"Driver":"bridge"}]'
            raise AssertionError('Invalid identity must prevent startup probes')
        with patch.dict(os.environ, env, clear=True), patch.object(tools, 'load_env'), \
             patch.object(Path, 'is_dir', return_value=True), \
             patch.object(Path, 'is_file', return_value=True), \
             patch.object(Path, 'stat', return_value=SimpleNamespace(st_size=32)), \
             patch.object(Path, 'read_text', return_value='x' * 32), \
             patch.object(tools.ssl, 'create_default_context'), \
             patch.object(tools, 'run', side_effect=command), \
             patch.object(tools.subprocess, 'run', return_value=SimpleNamespace(returncode=1)) as checked:
            with self.assertRaisesRegex(ValueError, 'ES256/IdentityProof'):
                tools.preflight()
            self.assertEqual(1, checked.call_count)
            self.assertEqual('node', checked.call_args.args[0][0])
            self.assertTrue(checked.call_args.kwargs['capture_output'])

    def test_inventory_import_is_source_of_truth(self):
        self.assertEqual(Path(p.__file__).resolve(),
                         tools.ROOT.parents[1] / 'rabbitmq/platform_control.py')
        inventory = p.inventory()
        self.assertEqual(21, len(inventory['queues']))
        self.assertEqual(7, len(inventory['exchanges']))
        self.assertEqual(20, len(inventory['bindings']))
        self.assertEqual(21, len(inventory['policies']))

    def test_prepared_example_blocks_operations(self):
        with patch.dict(os.environ, {'EP2_ENV_FILE': str(tools.ROOT / '.env.example')}, clear=True):
            with self.assertRaisesRegex(ValueError, 'PREPARED_ONLY'):
                tools.load_env()

    def test_unsafe_coordination_flag_blocks_operations(self):
        with patch.dict(os.environ, {'EP2_ENV_FILE': str(tools.ROOT / '.env.example'),
                                    'PEDIDOS360_COORDINATION_MODE': 'RABBITMQ'}, clear=True):
            with self.assertRaisesRegex(ValueError, 'Unsafe flag'):
                tools.load_env()

    def test_local_paths_cannot_point_to_production(self):
        env = dict(tools.SAFE, EP2_ENV_FILE=str(tools.ROOT / '.env.example'),
                   EP2_EXECUTION_SCOPE='LOCAL_TEST', RABBITMQ_DATA_DIR='/opt/production')
        with patch.dict(os.environ, env, clear=True):
            with self.assertRaisesRegex(ValueError, 'inside test-local'):
                tools.load_env()

    def test_https_required(self):
        with patch.dict(os.environ, {'EP2_MANAGEMENT_URL': 'http://localhost:15672/api/'}):
            with self.assertRaisesRegex(ValueError, 'HTTPS'):
                provision.HttpsApi()

    def test_inline_credentials_refused(self):
        with patch.dict(os.environ, {'EP2_MANAGEMENT_URL': 'https://user:placeholder@localhost/api/'}):
            with self.assertRaisesRegex(ValueError, 'inline credentials'):
                provision.HttpsApi()

    def test_no_destructive_management_verbs(self):
        api = object.__new__(provision.HttpsApi)
        for verb, path in [('DELETE', 'queues/pedidos360/q'), ('PUT', 'queues/pedidos360/q/contents')]:
            with self.subTest(verb=verb), self.assertRaisesRegex(ValueError, 'Destructive'):
                api.call(verb, path)

    def test_no_https_redirects(self):
        with self.assertRaisesRegex(RuntimeError, 'redirects prohibited'):
            provision.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://elsewhere/')

    def test_missing_dedicated_mount_blocks_before_docker_probe(self):
        with patch.object(tools, 'load_env'), patch.object(tools.sys, 'platform', 'linux'), \
             patch.dict(os.environ, {'EP2_EXECUTION_SCOPE': 'AWS_APPROVED',
                 'RABBITMQ_DATA_DIR': '/opt/test', 'EP2_BACKEND_NETWORK': 'private',
                 'EP2_EXPECTED_FS_UUID': 'expected'}), patch.object(Path, 'is_dir', return_value=True):
            def command(args, **kwargs):
                if args[:3] == ['docker', 'network', 'inspect']:
                    return '[{"Internal":true,"Driver":"bridge"}]'
                if args[0] == 'findmnt':
                    return '{"filesystems":[{"uuid":"root","fstype":"ext4"}]}'
                raise AssertionError('Must refuse before any Docker probe/start')
            with patch.object(tools, 'run', side_effect=command), \
                 self.assertRaisesRegex(ValueError, 'UUID/type mismatch'):
                tools.preflight()

    def test_root_filesystem_blocks_before_docker_probe(self):
        with patch.object(tools, 'load_env'), patch.object(tools.sys, 'platform', 'linux'), \
             patch.dict(os.environ, {'EP2_EXECUTION_SCOPE': 'AWS_APPROVED',
                 'RABBITMQ_DATA_DIR': '/opt/test', 'EP2_BACKEND_NETWORK': 'private',
                 'EP2_EXPECTED_FS_UUID': 'same'}), patch.object(Path, 'is_dir', return_value=True):
            def command(args, **kwargs):
                if args[:3] == ['docker', 'network', 'inspect']:
                    return '[{"Internal":true,"Driver":"bridge"}]'
                if args[0] == 'findmnt':
                    return '{"filesystems":[{"uuid":"same","fstype":"ext4"}]}'
                raise AssertionError('Must refuse before any Docker probe/start')
            with patch.object(tools, 'run', side_effect=command), \
                 self.assertRaisesRegex(ValueError, 'Root filesystem'):
                tools.preflight()


if __name__ == '__main__':
    unittest.main(verbosity=2)
