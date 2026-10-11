"""Exact account contract. Offline; never changes broker permissions."""
import re
import unittest
import platform_control as p

class AccountTests(unittest.TestCase):
    def test_consumer_write_sets_and_denied_configure(self):
        for domain, operation, _, _ in p.DOMAINS:
            a = next(a for a in p.accounts() if a[0] == f'p360-{domain}-consumer')
            expected = {'p360.retry', 'p360.dlx'} | (set() if domain == 'carrito' else {'amq.default'})
            self.assertEqual(p.DENY, a[3])
            self.assertEqual(p.exact(*(['p360.retry'] + ([] if domain == 'carrito' else ['amq.default']) + ['p360.dlx'])), a[4])
            self.assertEqual(p.exact(f'p360.{domain}.{operation}.q'), a[5])
            for exchange in expected:
                self.assertTrue(re.fullmatch(a[4], exchange))
            for exchange in ('p360.commands', 'p360.queries', 'p360.pedidos.commands', 'amq.topic', 'other'):
                self.assertFalse(re.fullmatch(a[4], exchange))

    def test_publisher_identities_remain_distinct(self):
        for name, exchange in [('p360-pagos-publisher', 'p360.pedidos.commands'),
                               ('p360-pedidos-carrito-publisher', 'p360.commands')]:
            a = next(a for a in p.accounts() if a[0] == name)
            self.assertEqual((p.DENY, p.exact(exchange), p.DENY), a[3:6])

    def test_admin_sandbox_contract_and_legacy_bootstrap(self):
        admin = [a for a in p.accounts() if a[0] == 'p360-admin-demo']
        self.assertEqual(1, len(admin))
        a = admin[0]; self.assertEqual(p.SANDBOX, a[2]); self.assertEqual('management', a[6])
        for pattern in a[3:6]:
            self.assertTrue(re.fullmatch(pattern, 'p360.demo.allowed-1'))
            for name in ('demo.old', 'p360.queries', 'amq.topic', '', 'p360.demo.', 'p360.demo.-bad', 'p360.demo.bad@name'):
                self.assertFalse(re.fullmatch(pattern, name))
        bootstrap = next(a for a in p.accounts() if a[0] == 'p360-bootstrap' and a[2] == p.SANDBOX)
        for pattern in bootstrap[3:6]:
            self.assertTrue(re.search(pattern, 'demo.ep2.persistence.quorum.q'))
            self.assertTrue(re.search(pattern, 'p360.demo.platform.q'))
        self.assertEqual((21, 7, 20, 21), tuple(len(p.inventory()[k]) for k in ('queues', 'exchanges', 'bindings', 'policies')))

if __name__ == '__main__':
    unittest.main(verbosity=2)
