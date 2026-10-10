-- PEO-02E: durable, append-only receipt of canonical CP founding governance
-- notifications. This table conveys NO billing or internal eligibility grant.
CREATE TABLE billing.founding_event_inbox (
  event_id uuid PRIMARY KEY,
  event_type text NOT NULL,
  event_source text NOT NULL,
  aggregate_id uuid NOT NULL,
  payload_sha256 char(64) NOT NULL CHECK(payload_sha256 ~ '^[0-9a-f]{64}$'),
  envelope jsonb NOT NULL,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK (event_type IN (
    'com.baobab-platform.control-plane.founding-sponsorship.activated.v1',
    'com.baobab-platform.control-plane.founding-sponsorship.suspended.v1',
    'com.baobab-platform.control-plane.founding-sponsorship.revoked.v1',
    'com.baobab-platform.control-plane.founding-sponsorship.expired.v1',
    'com.baobab-platform.control-plane.founding-documentary-deferral.activated.v1',
    'com.baobab-platform.control-plane.founding-documentary-deferral.revoked.v1',
    'com.baobab-platform.control-plane.founding-documentary-deferral.expired.v1'
  )),
  CHECK (event_source='urn:baobab-platform:service:baobab-cp')
);
CREATE INDEX founding_event_inbox_aggregate_order
 ON billing.founding_event_inbox(aggregate_id,received_at,event_id);
