-- Existing records remain explicitly unknown. No inferred tenant or backfill.
ALTER TABLE pagos ADD COLUMN tenant_id UUID;
ALTER TABLE pagos ADD COLUMN tenant_origin VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE pagos ADD CONSTRAINT ck_pagos_tenant_origin CHECK (
 (tenant_origin='UNKNOWN' AND tenant_id IS NULL) OR
 (tenant_origin IN ('AUTHENTICATED_NEW','RECONCILED_LEGACY') AND tenant_id IS NOT NULL));

-- Migration owner only. Application role must NOT own schema/tables/functions.
CREATE TABLE tenant_reconciliation_audit (
 resource_id BIGINT PRIMARY KEY REFERENCES pagos(id),
 previous_version BIGINT NOT NULL,
 tenant_id UUID NOT NULL,
 evidence_sha256 CHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
 operator_name TEXT NOT NULL,
 reconciled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
REVOKE ALL ON tenant_reconciliation_audit FROM PUBLIC;

CREATE FUNCTION guard_tenant_origin() RETURNS trigger LANGUAGE plpgsql SET search_path=pagos,pg_temp AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.tenant_id IS NULL OR NEW.tenant_origin<>'AUTHENTICATED_NEW' THEN
   RAISE EXCEPTION 'Authenticated tenant required' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN
  IF OLD.tenant_origin<>'AUTHENTICATED_NEW' THEN
   RAISE EXCEPTION 'Historical mutation prohibited' USING ERRCODE='23514';
  END IF;
  RETURN OLD;
 END IF;
 IF OLD.tenant_origin='UNKNOWN' AND NEW.tenant_origin='RECONCILED_LEGACY' THEN
  IF NEW.tenant_id IS NULL OR NEW.version<>OLD.version+1
    OR (to_jsonb(NEW)-'tenant_id'-'tenant_origin'-'version') IS DISTINCT FROM
       (to_jsonb(OLD)-'tenant_id'-'tenant_origin'-'version')
    OR NOT EXISTS (SELECT 1 FROM tenant_reconciliation_audit a
      WHERE a.resource_id=OLD.id AND a.previous_version=OLD.version AND a.tenant_id=NEW.tenant_id)
  THEN RAISE EXCEPTION 'Verified reconciliation required' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.tenant_origin IS DISTINCT FROM OLD.tenant_origin THEN
  RAISE EXCEPTION 'Tenant origin immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.tenant_origin<>'AUTHENTICATED_NEW' AND NEW IS DISTINCT FROM OLD THEN
  RAISE EXCEPTION 'Historical mutation prohibited' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_tenant_origin BEFORE INSERT OR UPDATE OR DELETE ON pagos
 FOR EACH ROW EXECUTE FUNCTION guard_tenant_origin();

-- Ordinary payment association never changes, including before an outbox INSERT.
-- Checking only for an existing outbox would race with a concurrent insert.
CREATE FUNCTION guard_payment_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.pedido_id IS DISTINCT FROM OLD.pedido_id THEN
  RAISE EXCEPTION 'Payment order immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_payment_order BEFORE UPDATE ON pagos
 FOR EACH ROW EXECUTE FUNCTION guard_payment_order();

-- No grants to runtime roles. Future operator must independently verify evidence,
-- related resources and deployment identity namespaces before invoking this function.
CREATE FUNCTION reconcile_tenant(resource BIGINT, expected_version BIGINT, target UUID, evidence TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pagos,pg_temp AS $$
DECLARE row_data pagos%ROWTYPE;
BEGIN
 IF resource IS NULL OR resource<1 OR expected_version IS NULL OR expected_version<0 OR target IS NULL OR evidence IS NULL OR evidence !~ '^[0-9a-f]{64}$' THEN
  RAISE EXCEPTION 'Evidence and tenant required';
 END IF;
 SELECT * INTO row_data FROM pagos WHERE id=resource FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource not found'; END IF;
 IF row_data.tenant_origin='RECONCILED_LEGACY' AND row_data.tenant_id=target
  AND EXISTS(SELECT 1 FROM tenant_reconciliation_audit WHERE resource_id=resource
      AND evidence_sha256=evidence AND previous_version=expected_version) THEN RETURN; END IF;
 IF row_data.tenant_origin<>'UNKNOWN' OR row_data.version<>expected_version THEN
  RAISE EXCEPTION 'Reconciliation conflict';
 END IF;
 INSERT INTO tenant_reconciliation_audit(resource_id,previous_version,tenant_id,evidence_sha256,operator_name)
 VALUES(resource,expected_version,target,evidence,session_user);
 UPDATE pagos SET tenant_id=target,tenant_origin='RECONCILED_LEGACY',version=version+1 WHERE id=resource;
END $$;
REVOKE ALL ON FUNCTION reconcile_tenant(BIGINT,BIGINT,UUID,TEXT) FROM PUBLIC;

-- Identity must survive native SQL writers as well as ORM updates. Existing
-- historical commands are retained; normal new work can update states/leases.
CREATE FUNCTION guard_outbox_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pagos,pg_temp AS $$
DECLARE command JSONB; parent_order BIGINT;
BEGIN
 IF TG_OP='UPDATE' AND (NEW.message_id IS DISTINCT FROM OLD.message_id
    OR NEW.pago_id IS DISTINCT FROM OLD.pago_id OR NEW.payload IS DISTINCT FROM OLD.payload) THEN
  RAISE EXCEPTION 'Outbox identity immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN
  IF NOT EXISTS(SELECT 1 FROM pagos WHERE id=OLD.pago_id AND tenant_origin='AUTHENTICATED_NEW') THEN
   RAISE EXCEPTION 'Historical outbox retained' USING ERRCODE='23514';
  END IF;
  RETURN OLD;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pagos WHERE id=NEW.pago_id AND tenant_origin='AUTHENTICATED_NEW') THEN
  RAISE EXCEPTION 'Authenticated outbox parent required' USING ERRCODE='23514';
 END IF;
 -- Only new inserts: preserve existing bytes and allow safe blocking of a
 -- previously corrupt command. This does not backfill/rewrite historical rows.
 IF TG_OP='INSERT' THEN
  SELECT pedido_id INTO parent_order FROM pagos WHERE id=NEW.pago_id;
  BEGIN
   command:=NEW.payload::jsonb;
   IF jsonb_typeof(command) IS DISTINCT FROM 'object'
      OR jsonb_typeof(command->'messageId') IS DISTINCT FROM 'string'
      OR command->>'messageId' IS DISTINCT FROM NEW.message_id::text
      OR jsonb_typeof(command->'pagoId') IS DISTINCT FROM 'number'
      OR command->>'pagoId' IS DISTINCT FROM NEW.pago_id::text
      OR jsonb_typeof(command->'pedidoId') IS DISTINCT FROM 'number'
      OR command->>'pedidoId' IS DISTINCT FROM parent_order::text THEN
    RAISE EXCEPTION 'Outbox references inconsistent' USING ERRCODE='23514';
   END IF;
  EXCEPTION WHEN invalid_text_representation THEN
   RAISE EXCEPTION 'Outbox references invalid' USING ERRCODE='23514';
  END;
 END IF;
 -- Recheck real time after acquiring the row lock, including an unchanged row
 -- whose writer held the lock beyond lease expiry. Returning NULL yields zero
 -- affected rows: the store reports NOT_SETTLED and leaves durable recovery intact.
 IF TG_OP='UPDATE' AND OLD.estado='IN_FLIGHT' AND NEW.estado<>'IN_FLIGHT'
    AND (OLD.lease_until IS NULL OR OLD.lease_until<=clock_timestamp()) THEN
  RETURN NULL;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_outbox_identity BEFORE INSERT OR UPDATE OR DELETE ON confirmacion_outbox
 FOR EACH ROW EXECUTE FUNCTION guard_outbox_identity();
