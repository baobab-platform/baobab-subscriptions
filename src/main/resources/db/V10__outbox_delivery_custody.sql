-- ADR-SUB-0014/0017: retain historical event identity/envelope while
-- adding crash-recoverable, fenced publication attempts. No event transport
-- or financial dispatch is activated by this migration.
ALTER TABLE billing.outbox
    ADD COLUMN publication_status text NOT NULL DEFAULT 'PENDING',
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0
        CHECK (attempt_count >= 0),
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN lease_owner text,
    ADD COLUMN lease_epoch bigint NOT NULL DEFAULT 0
        CHECK (lease_epoch >= 0),
    ADD COLUMN lease_expires_at timestamptz,
    ADD COLUMN first_failure_at timestamptz,
    ADD COLUMN last_failure_at timestamptz,
    ADD COLUMN failure_code text,
    ADD CONSTRAINT outbox_status_valid CHECK
        (publication_status IN ('PENDING','IN_FLIGHT','RETRY','PUBLISHED','QUARANTINED')),
    ADD CONSTRAINT outbox_lease_consistent CHECK
        ((publication_status = 'IN_FLIGHT') =
         (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL));

-- Older deployments may have acknowledged records. Never republish them.
UPDATE billing.outbox SET publication_status='PUBLISHED'
WHERE published_at IS NOT NULL;

CREATE INDEX outbox_delivery_due_idx
    ON billing.outbox(tenant_id, occurred_at, event_id)
    WHERE publication_status IN ('PENDING','RETRY','IN_FLIGHT');
CREATE INDEX outbox_delivery_status_idx
    ON billing.outbox(tenant_id, publication_status, occurred_at);
