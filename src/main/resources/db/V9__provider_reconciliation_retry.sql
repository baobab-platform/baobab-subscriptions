-- ADR-SUB-0015/0017: independent bounded scheduler custody.
-- Financially ambiguous sends are NEVER automatically redispatched.
-- Reconciliation attempts and cooldown survive restart and avoid retry storms.
ALTER TABLE billing.provider_operation
    ADD CONSTRAINT provider_operation_tenant_operation_unique UNIQUE (tenant_id, operation_id);

CREATE TABLE billing.provider_reconciliation_retry (
    tenant_id text NOT NULL,
    operation_id uuid NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0 AND attempts <= 8),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    status text NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','EXHAUSTED','RESOLVED')),
    last_reason_code text,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, operation_id),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES billing.provider_operation(tenant_id, operation_id)
);

CREATE INDEX provider_reconciliation_retry_due_idx
  ON billing.provider_reconciliation_retry (tenant_id, next_attempt_at, operation_id)
  WHERE status = 'SCHEDULED';
