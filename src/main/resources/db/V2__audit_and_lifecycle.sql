-- ADR-SUB-0016 sections 35-36: material audit records, immutable once written,
-- kept apart from application logs and events. ADR-SUB-0003: projections now
-- carry the lifecycle, operational condition and authoritative revision in
-- their document; documents written under V1 predate any deployment.
CREATE TABLE billing.audit_record (
    audit_id       uuid PRIMARY KEY,
    tenant_id      text NOT NULL,
    occurred_at    timestamptz NOT NULL,
    resource_type  text NOT NULL,
    resource_id    text NOT NULL,
    operation      text NOT NULL,
    record         jsonb NOT NULL
);
CREATE INDEX audit_record_tenant_idx ON billing.audit_record (tenant_id, occurred_at, audit_id);
CREATE INDEX audit_record_resource_idx ON billing.audit_record (resource_type, resource_id, occurred_at);

CREATE FUNCTION billing.audit_record_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'billing.audit_record is append-only (ADR-SUB-0016 section 40): % refused', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_record_no_update_or_delete
    BEFORE UPDATE OR DELETE ON billing.audit_record
    FOR EACH ROW EXECUTE FUNCTION billing.audit_record_immutable();
CREATE TRIGGER audit_record_no_truncate
    BEFORE TRUNCATE ON billing.audit_record
    FOR EACH STATEMENT EXECUTE FUNCTION billing.audit_record_immutable();

-- The applied authoritative revision, for revision-aware mutation.
ALTER TABLE billing.projection ADD COLUMN authoritative_revision bigint NOT NULL DEFAULT 1
    CHECK (authoritative_revision >= 1);
