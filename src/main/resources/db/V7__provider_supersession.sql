-- ADR-SUB-0015: a PREPARED operation can be superseded ONLY before
-- dispatch. SENT/UNKNOWN_OUTCOME may have contacted the provider and must
-- remain unresolved until independent observation settles the reference.
ALTER TABLE billing.provider_operation
  DROP CONSTRAINT provider_operation_state_check;
ALTER TABLE billing.provider_operation
  ADD CONSTRAINT provider_operation_state_check
  CHECK (state IN ('PREPARED', 'SUPERSEDED', 'SENT', 'CONVERGED', 'ALREADY_CONVERGED',
                  'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'CONFLICT',
                  'UNKNOWN_OUTCOME', 'RECONCILED'));

-- Hot-path check for earlier unfinished operations on the same real
-- provider-backed subscription; no tenant or provider wildcard allowed.
CREATE INDEX provider_operation_unresolved_subscription_idx
  ON billing.provider_operation (tenant_id, provider_kind, billing_subscription_id, created_at)
  WHERE state NOT IN ('CONVERGED', 'ALREADY_CONVERGED', 'RECONCILED', 'SUPERSEDED');
