-- PEO-02F: provenance of the *verified* IAM workload at receipt, not an
-- untrusted CloudEvents header. V3 might already have executed in a preview
-- staging database; append a forward-only migration instead of rewriting V3.
ALTER TABLE billing.founding_event_inbox
  ADD COLUMN IF NOT EXISTS received_by_client_id text
    NOT NULL DEFAULT 'UNVERIFIED_PRIOR_RECEIPT';
ALTER TABLE billing.founding_event_inbox
  ALTER COLUMN received_by_client_id DROP DEFAULT;
ALTER TABLE billing.founding_event_inbox
  ADD CONSTRAINT founding_inbox_verified_client_length
  CHECK (length(btrim(received_by_client_id)) BETWEEN 3 AND 200);
-- Historical receipts are intentionally NOT certified. A new event must
-- arrive with a caller ID verified by WorkloadAuthenticator at the API edge.
