-- Existing records remain explicitly unknown. No inferred tenant or backfill.
ALTER TABLE pedidos ADD COLUMN tenant_id UUID;
ALTER TABLE pedidos ADD COLUMN tenant_origin VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE pedidos ADD CONSTRAINT ck_pedidos_tenant_origin CHECK (
 (tenant_origin='UNKNOWN' AND tenant_id IS NULL) OR
 (tenant_origin IN ('AUTHENTICATED_NEW','RECONCILED_LEGACY') AND tenant_id IS NOT NULL));

-- Migration owner only. Application role must NOT own schema/tables/functions.
CREATE TABLE tenant_reconciliation_audit (
 resource_id BIGINT PRIMARY KEY REFERENCES pedidos(id),
 previous_version BIGINT NOT NULL,
 tenant_id UUID NOT NULL,
 evidence_sha256 CHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
 operator_name TEXT NOT NULL,
 reconciled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
REVOKE ALL ON tenant_reconciliation_audit FROM PUBLIC;

CREATE FUNCTION guard_tenant_origin() RETURNS trigger LANGUAGE plpgsql SET search_path=pedidos,pg_temp AS $$
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
CREATE TRIGGER guard_tenant_origin BEFORE INSERT OR UPDATE OR DELETE ON pedidos
 FOR EACH ROW EXECUTE FUNCTION guard_tenant_origin();

-- No grants to runtime roles. Future operator must independently verify evidence,
-- related resources and deployment identity namespaces before invoking this function.
CREATE FUNCTION reconcile_tenant(resource BIGINT, expected_version BIGINT, target UUID, evidence TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pedidos,pg_temp AS $$
DECLARE row_data pedidos%ROWTYPE;
BEGIN
 IF resource IS NULL OR resource<1 OR expected_version IS NULL OR expected_version<0 OR target IS NULL OR evidence IS NULL OR evidence !~ '^[0-9a-f]{64}$' THEN
  RAISE EXCEPTION 'Evidence and tenant required';
 END IF;
 SELECT * INTO row_data FROM pedidos WHERE id=resource FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Resource not found'; END IF;
 IF row_data.tenant_origin='RECONCILED_LEGACY' AND row_data.tenant_id=target
  AND EXISTS(SELECT 1 FROM tenant_reconciliation_audit WHERE resource_id=resource
      AND evidence_sha256=evidence AND previous_version=expected_version) THEN RETURN; END IF;
 IF row_data.tenant_origin<>'UNKNOWN' OR row_data.version<>expected_version THEN
  RAISE EXCEPTION 'Reconciliation conflict';
 END IF;
 INSERT INTO tenant_reconciliation_audit(resource_id,previous_version,tenant_id,evidence_sha256,operator_name)
 VALUES(resource,expected_version,target,evidence,session_user);
 UPDATE pedidos SET tenant_id=target,tenant_origin='RECONCILED_LEGACY',version=version+1 WHERE id=resource;
END $$;
REVOKE ALL ON FUNCTION reconcile_tenant(BIGINT,BIGINT,UUID,TEXT) FROM PUBLIC;
-- Prevent child-table mutation of retained historical aggregates.
CREATE FUNCTION guard_linea_tenant() RETURNS trigger LANGUAGE plpgsql SET search_path=pedidos,pg_temp AS $$
DECLARE parent_id BIGINT;
BEGIN
 IF TG_OP<>'INSERT' AND NOT EXISTS(SELECT 1 FROM pedidos WHERE id=OLD.pedido_id AND tenant_origin='AUTHENTICATED_NEW') THEN
  RAISE EXCEPTION 'Historical mutation prohibited' USING ERRCODE='23514';
 END IF;
 IF TG_OP<>'DELETE' AND NOT EXISTS(SELECT 1 FROM pedidos WHERE id=NEW.pedido_id AND tenant_origin='AUTHENTICATED_NEW') THEN
  RAISE EXCEPTION 'Authenticated parent required' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_linea_tenant BEFORE INSERT OR UPDATE OR DELETE ON lineas_pedido
 FOR EACH ROW EXECUTE FUNCTION guard_linea_tenant();
