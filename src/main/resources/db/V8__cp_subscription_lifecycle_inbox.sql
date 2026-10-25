-- ADR-SUB-0003 §§13-17 and ADR-SUB-0014 §§17-29: source+event-id
-- deduplication is durable; notification does not assign CP authority.
-- Shared product/v1 event payloads contain no monotonic aggregate revision:
-- preserve PENDING_CP_REFRESH until an authorised CP state fetch is available.
CREATE TABLE billing.cp_subscription_lifecycle_inbox (
  event_id uuid NOT NULL,
  event_source text NOT NULL
    CHECK (event_source = 'urn:baobab-platform:service:baobab-cp'),
  event_type text NOT NULL,
  tenant_id text NOT NULL CHECK (length(trim(tenant_id)) > 0),
  subscription_id text NOT NULL CHECK (length(trim(subscription_id)) > 0),
  received_by_client_id text NOT NULL
    CHECK (length(trim(received_by_client_id)) BETWEEN 3 AND 200),
  payload_sha256 char(64) NOT NULL
    CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  envelope jsonb NOT NULL,
  status text NOT NULL DEFAULT 'PENDING_CP_REFRESH'
    CHECK (status IN ('PENDING_CP_REFRESH','RECONCILING','APPLIED','QUARANTINED')),
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  processed_at timestamptz,
  retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  failure_code text,
  PRIMARY KEY (event_source, event_id)
);
CREATE INDEX cp_subscription_lifecycle_pending_idx
  ON billing.cp_subscription_lifecycle_inbox
  (tenant_id, subscription_id, received_at, event_id)
  WHERE status = 'PENDING_CP_REFRESH';
