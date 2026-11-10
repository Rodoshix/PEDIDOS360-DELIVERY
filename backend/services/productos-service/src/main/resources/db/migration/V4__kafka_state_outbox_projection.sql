CREATE TABLE productos.kafka_state (
 entity_id BIGINT PRIMARY KEY CHECK(entity_id>0), version BIGINT NOT NULL CHECK(version>=0), state VARCHAR(20) NOT NULL
);
CREATE TABLE productos.kafka_outbox (
 seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, event_id UUID NOT NULL UNIQUE,
 entity_id BIGINT NOT NULL, topic VARCHAR(128) NOT NULL, payload TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ,
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX kafka_outbox_pending ON productos.kafka_outbox(seq) WHERE sent_at IS NULL;
CREATE TABLE productos.kafka_projection (
 entity_id BIGINT PRIMARY KEY CHECK(entity_id>0),version BIGINT NOT NULL CHECK(version>=0),
 state VARCHAR(20) NOT NULL,event_id UUID NOT NULL,occurred_at TIMESTAMPTZ NOT NULL,received_at TIMESTAMPTZ NOT NULL
);
