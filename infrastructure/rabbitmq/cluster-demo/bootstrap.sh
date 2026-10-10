#!/bin/sh
set -eu
# No tracing: values stay in files and process memory, never CLI arguments/logs.
test "$(cat /demo/private/LOCAL_SYNTHETIC_CLUSTER_73)" = "LOCAL_SYNTHETIC_CLUSTER_73"
case "$RABBITMQ_NODENAME" in rabbit@rabbit-demo-1|rabbit@rabbit-demo-2) ;; *) exit 1 ;; esac
for f in ADMIN_PASSWORD ERLANG_COOKIE; do
  test -s "/demo/private/$f"
done
if test -f /var/lib/rabbitmq/.erlang.cookie; then
  cmp -s /demo/private/ERLANG_COOKIE /var/lib/rabbitmq/.erlang.cookie || {
    echo 'BLOCKED: demo cookie differs from persistent identity' >&2; exit 1;
  }
else
  cp /demo/private/ERLANG_COOKIE /var/lib/rabbitmq/.erlang.cookie
fi
chown rabbitmq:rabbitmq /var/lib/rabbitmq /var/lib/rabbitmq/.erlang.cookie
chmod 600 /var/lib/rabbitmq/.erlang.cookie
mkdir -p /etc/rabbitmq/demo-tls
for f in ca.pem server.pem server-key.pem; do
  cp "/demo/private/tls/$f" "/etc/rabbitmq/demo-tls/$f"
  chown rabbitmq:rabbitmq "/etc/rabbitmq/demo-tls/$f"
  chmod 600 "/etc/rabbitmq/demo-tls/$f"
done
export RABBITMQ_DEFAULT_USER=demo73-admin
export RABBITMQ_DEFAULT_PASS="$(cat /demo/private/ADMIN_PASSWORD)"
export RABBITMQ_DEFAULT_VHOST=demo73
exec /usr/local/bin/docker-entrypoint.sh rabbitmq-server
