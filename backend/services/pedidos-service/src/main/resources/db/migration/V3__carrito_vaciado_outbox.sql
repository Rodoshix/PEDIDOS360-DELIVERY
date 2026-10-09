CREATE TABLE carrito_vaciado_outbox (
 message_id UUID PRIMARY KEY,
 pedido_id BIGINT NOT NULL UNIQUE REFERENCES pedidos(id),
 tenant_id UUID NOT NULL, entra_object_id UUID NOT NULL,
 carrito_id BIGINT NOT NULL CHECK(carrito_id>0), expected_version BIGINT NOT NULL CHECK(expected_version>=0),
 payload TEXT NOT NULL,
 estado VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(estado IN ('PENDING','IN_FLIGHT','PUBLISHED','BLOCKED')),
 lease_token UUID, lease_until TIMESTAMPTZ, attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 published_at TIMESTAMPTZ, last_error VARCHAR(40),
 CHECK((estado='IN_FLIGHT')=(lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX carrito_outbox_pending ON carrito_vaciado_outbox(next_attempt_at) WHERE estado IN ('PENDING','IN_FLIGHT');
CREATE FUNCTION guard_carrito_outbox() RETURNS trigger LANGUAGE plpgsql SET search_path=pedidos,pg_temp AS $$
DECLARE j JSONB;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Cart intent retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF (NEW.message_id,NEW.pedido_id,NEW.tenant_id,NEW.entra_object_id,NEW.carrito_id,NEW.expected_version,NEW.payload)
     IS DISTINCT FROM (OLD.message_id,OLD.pedido_id,OLD.tenant_id,OLD.entra_object_id,OLD.carrito_id,OLD.expected_version,OLD.payload)
     OR (OLD.estado IN ('PUBLISHED','BLOCKED') AND NEW IS DISTINCT FROM OLD) THEN
    RAISE EXCEPTION 'Cart intent immutable' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.estado<>'PENDING' OR NEW.attempts<>0 OR NOT EXISTS(SELECT 1 FROM pedidos p WHERE p.id=NEW.pedido_id
       AND p.tenant_id=NEW.tenant_id AND p.tenant_origin='AUTHENTICATED_NEW') THEN
   RAISE EXCEPTION 'Authenticated order required' USING ERRCODE='23514'; END IF;
 END IF;
 j=NEW.payload::jsonb;
 IF jsonb_typeof(j)<>'object' OR (SELECT count(*) FROM jsonb_object_keys(j))<>8
    OR j->>'messageId' IS DISTINCT FROM NEW.message_id::text OR j->>'type' IS DISTINCT FROM 'VaciarCarritoPorPedido'
    OR j->>'version' IS DISTINCT FROM '1' OR j->>'pedidoId' IS DISTINCT FROM NEW.pedido_id::text
    OR j->>'carritoId' IS DISTINCT FROM NEW.carrito_id::text OR j->>'expectedCarritoVersion' IS DISTINCT FROM NEW.expected_version::text
    OR j->'propietario'->>'tenantId' IS DISTINCT FROM NEW.tenant_id::text
    OR j->'propietario'->>'entraObjectId' IS DISTINCT FROM NEW.entra_object_id::text
    OR jsonb_typeof(j->'propietario') IS DISTINCT FROM 'object'
    OR (SELECT count(*) FROM jsonb_object_keys(j->'propietario'))<>2
    OR jsonb_typeof(j->'version') IS DISTINCT FROM 'number'
    OR jsonb_typeof(j->'pedidoId') IS DISTINCT FROM 'number'
    OR jsonb_typeof(j->'carritoId') IS DISTINCT FROM 'number'
    OR jsonb_typeof(j->'expectedCarritoVersion') IS DISTINCT FROM 'number'
    OR jsonb_typeof(j->'occurredAt') IS DISTINCT FROM 'string' OR (j->>'occurredAt') NOT LIKE '%Z' THEN
  RAISE EXCEPTION 'Cart payload association invalid' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_carrito_outbox BEFORE INSERT OR UPDATE OR DELETE ON carrito_vaciado_outbox
 FOR EACH ROW EXECUTE FUNCTION guard_carrito_outbox();
