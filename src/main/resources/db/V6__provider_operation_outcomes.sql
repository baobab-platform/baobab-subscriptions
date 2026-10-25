-- Keep failure/unknown-outcome classification durable; no sensitive vendor
-- messages or HTTP bodies enter the journal (ADR-SUB-0015 §13/36/65).
ALTER TABLE billing.provider_operation
  ADD COLUMN reason_code text,
  ADD CONSTRAINT provider_operation_reason_code CHECK
    (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{2,63}$');

-- An operation's apparent delivery is never inferred from the presence of
-- a mutable provider mapping. Reconciliation is an explicit state transition.
