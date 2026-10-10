"""Bounded child-process availability probe: read only, never ACK or publish."""
import ssl
import pika
import demo

try:
    params = pika.ConnectionParameters('127.0.0.1', 5783, 'demo73',
        pika.PlainCredentials('demo73-consumer', demo.secret('CONSUMER_PASSWORD')),
        ssl_options=pika.SSLOptions(ssl.create_default_context(cafile=str(demo.PRIVATE / 'tls/ca.pem')), 'rabbit-demo-1'),
        socket_timeout=3, blocked_connection_timeout=3, stack_timeout=5, connection_attempts=1)
    with pika.BlockingConnection(params) as conn:
        message, _, _ = conn.channel().basic_get('demo73.orders.q', auto_ack=False)
        if message: raise SystemExit(10)  # A delivery would violate the expected unavailable state.
    raise SystemExit(11)  # Empty successful read also counts as unexpected availability.
except Exception:
    raise SystemExit(2)  # Transport/refusal; details intentionally omitted.
