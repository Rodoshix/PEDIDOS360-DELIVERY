CREATE TABLE vaciado_por_pedido (
 message_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, entra_object_id UUID NOT NULL,
 pedido_id BIGINT NOT NULL CHECK(pedido_id>0), carrito_id BIGINT NOT NULL CHECK(carrito_id>0),
 expected_version BIGINT NOT NULL CHECK(expected_version>=0), canonical_payload TEXT NOT NULL,
 estado VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' CHECK(estado IN ('RECEIVED','EMPTIED','OMITTED_VERSION_CHANGED','REJECTED')),
 retry_authorized BOOLEAN NOT NULL DEFAULT FALSE,
 received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at TIMESTAMPTZ,
 CHECK((estado='RECEIVED')=(finished_at IS NULL))
);
CREATE FUNCTION guard_vaciado_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=carrito,pg_temp AS $$
DECLARE j JSONB;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Receipt retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.estado<>'RECEIVED' OR NEW.retry_authorized OR NEW.finished_at IS NOT NULL THEN
   RAISE EXCEPTION 'Initial receipt required' USING ERRCODE='23514'; END IF;
 ELSE
  IF (NEW.message_id,NEW.tenant_id,NEW.entra_object_id,NEW.pedido_id,NEW.carrito_id,NEW.expected_version,NEW.canonical_payload,NEW.received_at)
     IS DISTINCT FROM (OLD.message_id,OLD.tenant_id,OLD.entra_object_id,OLD.pedido_id,OLD.carrito_id,OLD.expected_version,OLD.canonical_payload,OLD.received_at)
     OR (OLD.retry_authorized AND NOT NEW.retry_authorized)
     OR (OLD.estado<>'RECEIVED' AND NEW IS DISTINCT FROM OLD) THEN
    RAISE EXCEPTION 'Receipt immutable or terminal' USING ERRCODE='23514'; END IF;
 END IF;
 j=NEW.canonical_payload::jsonb;
 IF jsonb_typeof(j) IS DISTINCT FROM 'object' OR (SELECT count(*) FROM jsonb_object_keys(j))<>8
    OR j->>'messageId' IS DISTINCT FROM NEW.message_id::text OR j->>'type' IS DISTINCT FROM 'VaciarCarritoPorPedido'
    OR j->>'version' IS DISTINCT FROM '1' OR j->>'pedidoId' IS DISTINCT FROM NEW.pedido_id::text
    OR j->>'carritoId' IS DISTINCT FROM NEW.carrito_id::text OR j->>'expectedCarritoVersion' IS DISTINCT FROM NEW.expected_version::text
    OR jsonb_typeof(j->'propietario') IS DISTINCT FROM 'object'
    OR (SELECT count(*) FROM jsonb_object_keys(j->'propietario'))<>2
    OR j->'propietario'->>'tenantId' IS DISTINCT FROM NEW.tenant_id::text
    OR j->'propietario'->>'entraObjectId' IS DISTINCT FROM NEW.entra_object_id::text
    OR jsonb_typeof(j->'occurredAt') IS DISTINCT FROM 'string' OR (j->>'occurredAt') NOT LIKE '%Z'
    OR jsonb_typeof(j->'version') IS DISTINCT FROM 'number'
    OR jsonb_typeof(j->'pedidoId') IS DISTINCT FROM 'number'
    OR jsonb_typeof(j->'carritoId') IS DISTINCT FROM 'number'
    OR jsonb_typeof(j->'expectedCarritoVersion') IS DISTINCT FROM 'number' THEN
  RAISE EXCEPTION 'Receipt payload association invalid' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_vaciado_receipt BEFORE INSERT OR UPDATE OR DELETE ON vaciado_por_pedido
 FOR EACH ROW EXECUTE FUNCTION guard_vaciado_receipt();
