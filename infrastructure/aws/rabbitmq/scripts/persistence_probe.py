"""Infrastructure probe only: demo sandbox, persistent message, no application commands."""
import argparse
import json
import os
from pathlib import Path
import ssl
import time
import pika

QUEUE = 'demo.ep2.persistence.quorum.q'
EXCHANGE = 'demo.ep2.persistence'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['publish', 'consume'])
    parser.add_argument('message_id')
    args = parser.parse_args()
    if os.environ.get('EP2_EXECUTION_SCOPE') not in ('LOCAL_TEST', 'AWS_APPROVED'):
        raise ValueError('Probe disabled')
    context = ssl.create_default_context(cafile=os.environ['EP2_CA_FILE'])
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    password = (Path(os.environ['EP2_SECRET_DIR']) / 'BOOTSTRAP_PASSWORD').read_text().strip()
    parameters = pika.ConnectionParameters(os.environ.get('EP2_AMQP_HOST', 'p360-rabbitmq'),
                  int(os.environ.get('EP2_AMQP_PORT', '5671')), 'pedidos360-admin-demo',
                  pika.PlainCredentials('p360-bootstrap', password),
                  ssl_options=pika.SSLOptions(context, 'p360-rabbitmq'), socket_timeout=10,
                  blocked_connection_timeout=10)
    with pika.BlockingConnection(parameters) as conn:
        channel = conn.channel()
        if args.action == 'publish':
            channel.exchange_declare(EXCHANGE, exchange_type='direct', durable=True)
            declared = channel.queue_declare(QUEUE, durable=True, arguments={'x-queue-type': 'quorum'})
            if declared.method.message_count or declared.method.consumer_count:
                raise ValueError('Probe queue not empty/idle; inspect previous evidence, never purge')
            channel.confirm_delivery()
            channel.queue_bind(QUEUE, EXCHANGE, 'probe.quorum')
            channel.basic_publish(EXCHANGE, 'probe.quorum', b'NO AWS APPLICATION: EP2 persistence probe', mandatory=True,
                properties=pika.BasicProperties(delivery_mode=2, message_id=args.message_id))
            print(json.dumps({'result': 'broker-confirmed', 'message_id': args.message_id, 'queue': QUEUE}))
        else:
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                method, properties, body = channel.basic_get(QUEUE, auto_ack=False)
                if method:
                    if properties.message_id != args.message_id or properties.delivery_mode != 2:
                        raise ValueError('Unexpected message remains unsettled; inspect, never purge')
                    channel.basic_ack(method.delivery_tag)
                    # Passive RPC fences the ACK on the same ordered channel.
                    if channel.queue_declare(QUEUE, passive=True).method.message_count:
                        raise ValueError('Probe queue still contains messages; inspect without purging')
                    print(json.dumps({'result': 'persistent-message-recovered-and-acked', 'message_id': args.message_id}))
                    return
                conn.process_data_events(time_limit=0.1)
            raise ValueError('Probe absent; stop and investigate')


if __name__ == '__main__':
    main()
