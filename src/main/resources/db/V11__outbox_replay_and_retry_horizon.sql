-- SUB-02: preserve the first delivery attempt across retries, including crashed
-- workers; cap retry horizon at 72 hours, below Shared's seven-day inbox
-- retention. Controlled replay starts a NEW attempt horizon but preserves the
-- immutable canonical event identity and historical delivery audit.
ALTER TABLE billing.outbox ADD COLUMN first_attempt_at timestamptz;

-- Conservatively retain retry age for V10 rows already attempted before
-- V11 was installed. A migrated retry must not gain a fresh 72-hour horizon.
UPDATE billing.outbox SET first_attempt_at=COALESCE(first_failure_at,occurred_at)
WHERE first_attempt_at IS NULL AND attempt_count>0 AND published_at IS NULL;

CREATE TABLE billing.outbox_replay_audit (
    replay_id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES billing.outbox(event_id),
    tenant_id text NOT NULL,
    principal_id text NOT NULL,
    approval_reference text NOT NULL,
    reason_code text NOT NULL,
    evidence_reference text NOT NULL,
    prior_attempt_count integer NOT NULL,
    CONSTRAINT outbox_replay_approval_one_use
        UNIQUE (tenant_id,event_id,approval_reference),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CHECK(length(principal_id) BETWEEN 3 AND 200),
    CHECK(length(approval_reference) BETWEEN 8 AND 256),
    CHECK(length(evidence_reference) BETWEEN 8 AND 256),
    CHECK(reason_code ~ '^[A-Z][A-Z0-9_]{2,47}$')
);
CREATE INDEX outbox_replay_audit_tenant_event
    ON billing.outbox_replay_audit(tenant_id,event_id,recorded_at);

CREATE FUNCTION billing.prevent_outbox_replay_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'billing.outbox_replay_audit is append-only'
        USING ERRCODE='insufficient_privilege';
END;
$$;
CREATE TRIGGER outbox_replay_audit_no_mutation
    BEFORE UPDATE OR DELETE ON billing.outbox_replay_audit
    FOR EACH ROW EXECUTE FUNCTION billing.prevent_outbox_replay_audit_mutation();
CREATE TRIGGER outbox_replay_audit_no_truncate
    BEFORE TRUNCATE ON billing.outbox_replay_audit
    FOR EACH STATEMENT EXECUTE FUNCTION billing.prevent_outbox_replay_audit_mutation();
