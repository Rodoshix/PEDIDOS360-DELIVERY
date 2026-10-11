#!/bin/sh
set -eu
# Do not enable tracing: secret content must not appear in logs or command arguments.
case "${EP2_EXECUTION_SCOPE:-PREPARED_ONLY}" in
  AWS_APPROVED)
    # Runs on EVERY container start, including Docker auto-restart after host reboot.
    # Host preflight validates UUID+EBS serial; filesystem id prevents root fallback here.
    actual_fs=$(stat -f -c '%i' /var/lib/rabbitmq)
    test "${EP2_EXPECTED_FS_ID:-}" = "$actual_fs" || {
      echo 'BLOCKED: dedicated filesystem absent or changed; refusing root fallback' >&2; exit 1;
    }
    ;;
  LOCAL_TEST) ;; # Fixtures only, never deploy this scope to EC2.
  *) echo 'BLOCKED: broker start not approved for this scope' >&2; exit 1 ;;
esac
uid=$(id -u rabbitmq)
gid=$(id -g rabbitmq)
test -s /run/ep2/secrets/BOOTSTRAP_PASSWORD
test -s /run/ep2/secrets/ERLANG_COOKIE
for f in ca.pem server.pem server-key.pem; do
  su-exec "$uid:$gid" test -r "/run/ep2/tls/$f"
done
# Never silently replace the identity of an existing persistent node.
if test -f /var/lib/rabbitmq/.erlang.cookie; then
  cmp -s /run/ep2/secrets/ERLANG_COOKIE /var/lib/rabbitmq/.erlang.cookie || {
    echo 'BLOCKED: persisted Erlang cookie differs from supplied secret' >&2; exit 1;
  }
else
  cp /run/ep2/secrets/ERLANG_COOKIE /var/lib/rabbitmq/.erlang.cookie
  chown "$uid:$gid" /var/lib/rabbitmq/.erlang.cookie
  chmod 600 /var/lib/rabbitmq/.erlang.cookie
fi
export RABBITMQ_DEFAULT_USER=p360-bootstrap
export RABBITMQ_DEFAULT_PASS="$(cat /run/ep2/secrets/BOOTSTRAP_PASSWORD)"
export RABBITMQ_DEFAULT_VHOST=pedidos360
# Ownership is verified by preflight, so avoid an expensive recursive chown on every boot.
exec su-exec "$uid:$gid" /usr/local/bin/docker-entrypoint.sh rabbitmq-server
