-- baobab-subscriptions own persistence (ADR-SUB-0001 section 8). It is never
-- read by the Control Plane, and this engine never reads the Control Plane's.
-- Every row is scoped by tenant_id.
CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.projection (
    billing_subscription_id  text PRIMARY KEY,
    tenant_id                text NOT NULL,
    product_subscription_id  text NOT NULL,
    version                  bigint NOT NULL CHECK (version >= 1),
    document                 jsonb NOT NULL,
    updated_at               timestamptz NOT NULL,
    -- One projection per ProductSubscription: a reclassification updates it in place.
    UNIQUE (tenant_id, product_subscription_id)
);
CREATE INDEX projection_tenant_idx ON billing.projection (tenant_id, billing_subscription_id);

CREATE TABLE billing.usage_record (
    usage_record_id          text PRIMARY KEY,
    tenant_id                text NOT NULL,
    billing_subscription_id  text NOT NULL REFERENCES billing.projection (billing_subscription_id),
    metric_key               text NOT NULL,
    source_reference         text NOT NULL,
    document                 jsonb NOT NULL,
    recorded_at              timestamptz NOT NULL,
    -- A usage source is metered once, however often it is reported.
    UNIQUE (tenant_id, billing_subscription_id, metric_key, source_reference)
);

CREATE TABLE billing.idempotency (
    tenant_id      text NOT NULL,
    operation      text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash   text NOT NULL,
    status         integer NOT NULL,
    response_body  text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, operation, idempotency_key)
);

-- Canonical event envelopes, written in the same transaction as the change.
-- Relaying them to the event backbone is not built yet.
CREATE TABLE billing.outbox (
    event_id      uuid PRIMARY KEY,
    event_type    text NOT NULL,
    tenant_id     text NOT NULL,
    aggregate_id  text NOT NULL,
    envelope      jsonb NOT NULL,
    occurred_at   timestamptz NOT NULL,
    published_at  timestamptz
);
CREATE INDEX outbox_unpublished_idx ON billing.outbox (occurred_at) WHERE published_at IS NULL;
