-- ADR-SUB-0015 (14, 27-30, 55-58): provider identities and execution
-- uncertainty must be durable and tenant-scoped before Kill Bill activation.
-- No provider operations are enabled by this migration.
--
-- The composite reference ensures a provider operation cannot be inserted
-- against another tenant's billing projection, even if the global ID is known.
ALTER TABLE billing.projection
  ADD CONSTRAINT billing_projection_tenant_id_unique UNIQUE (tenant_id, billing_subscription_id);

CREATE TABLE billing.provider_resource_mapping (
  mapping_id             uuid PRIMARY KEY,
  tenant_id              text NOT NULL CHECK (length(trim(tenant_id)) > 0),
  provider_kind          text NOT NULL CHECK (provider_kind <> 'TEMPORARY' AND length(trim(provider_kind)) > 0),
  baobab_resource_type   text NOT NULL CHECK (length(trim(baobab_resource_type)) > 0),
  baobab_resource_id     text NOT NULL CHECK (length(trim(baobab_resource_id)) > 0),
  provider_resource_type text NOT NULL CHECK (length(trim(provider_resource_type)) > 0),
  provider_resource_id   text NOT NULL CHECK (length(trim(provider_resource_id)) > 0),
  provider_metadata      jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at             timestamptz NOT NULL DEFAULT now(),
  last_verified_at       timestamptz,
  UNIQUE (tenant_id, provider_kind, baobab_resource_type, baobab_resource_id),
  UNIQUE (tenant_id, provider_kind, provider_resource_type, provider_resource_id)
);
CREATE INDEX provider_mapping_tenant_idx
  ON billing.provider_resource_mapping (tenant_id, baobab_resource_type, baobab_resource_id);

-- Unknown provider outcomes are held for observation/reconciliation; never
-- translate an HTTP timeout into a safe automatic re-create.
CREATE TABLE billing.provider_operation (
  operation_id           uuid PRIMARY KEY,
  tenant_id              text NOT NULL CHECK (length(trim(tenant_id)) > 0),
  billing_subscription_id text NOT NULL,
  provider_kind          text NOT NULL CHECK (provider_kind <> 'TEMPORARY' AND length(trim(provider_kind)) > 0),
  operation_kind         text NOT NULL CHECK (length(trim(operation_kind)) > 0),
  idempotency_key        text NOT NULL CHECK (length(trim(idempotency_key)) > 0),
  request_sha256         char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  authoritative_revision bigint NOT NULL CHECK (authoritative_revision >= 1),
  state                  text NOT NULL DEFAULT 'PREPARED'
    CHECK (state IN ('PREPARED', 'SENT', 'CONVERGED', 'ALREADY_CONVERGED',
                    'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'CONFLICT',
                    'UNKNOWN_OUTCOME', 'RECONCILED')),
  lease_owner            text,
  lease_epoch            bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
  lease_expires_at       timestamptz,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, provider_kind, operation_kind, idempotency_key),
  FOREIGN KEY (tenant_id, billing_subscription_id)
    REFERENCES billing.projection (tenant_id, billing_subscription_id),
  CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL))
);
CREATE INDEX provider_operation_reconciliation_idx
  ON billing.provider_operation (tenant_id, created_at, operation_id)
  WHERE state IN ('SENT', 'UNKNOWN_OUTCOME', 'RETRYABLE_FAILURE');
