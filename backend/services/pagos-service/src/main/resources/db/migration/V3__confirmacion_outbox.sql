ALTER TABLE pagos.pagos ADD COLUMN coordinacion varchar(10) NOT NULL DEFAULT 'HTTP'
    CHECK (coordinacion IN ('HTTP', 'RABBITMQ'));

CREATE TABLE pagos.confirmacion_outbox (
    message_id uuid PRIMARY KEY,
    pago_id bigint NOT NULL UNIQUE REFERENCES pagos.pagos(id),
    payload text NOT NULL,
    estado varchar(10) NOT NULL CHECK (estado IN ('PENDING','IN_FLIGHT','PUBLISHED','BLOCKED')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_until timestamptz,
    lease_token uuid,
    published_at timestamptz,
    last_error varchar(80)
);
CREATE INDEX idx_outbox_dispatch ON pagos.confirmacion_outbox(next_attempt_at)
    WHERE estado IN ('PENDING','IN_FLIGHT');
