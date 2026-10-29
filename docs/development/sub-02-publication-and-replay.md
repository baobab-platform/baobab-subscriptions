# SUB-02 — Signed delivery rehearsal, bounded publisher and operator replay

**Date:** 2026-10-29. **Governance:** ADR-SUB-0014 §§47–58, 83–90;
ADR-SUB-0016; ADR-SUB-0017 §§23–25, 35, 59–62; ADR-SUB-0018 §§40–43.
See SUB-00–SUB-12 programme and Shared's
`contracts/events/v1/signed-delivery.schema.json` plus
`docs/architecture/signed-event-delivery.md`.

## Contract discovery: the acceptance boundary is not yet available

Shared registers five Subscriptions event types under
`contracts/events/v1/event-registry.yaml`, produced only by
`baobab-subscriptions`. The `subscriptions/v1/asyncapi.yaml` owns
the canonical envelopes and channel. Shared also has a generic
`events:publish` scope **for the event-broker audience**.

However, **no registered and currently active Subscriptions publisher
identity for the event-broker audience was found**. Shared's signed
HTTPS delivery document is an FB-04 **PROPOSED** contract for ERP →
Control Plane. Its closed `control-plane/v1/event-ingress.yaml`
currently accepts **only** `com.baobab-platform.erp.provisioning.changed.v1`.
It categorically does **not** accept any Subscriptions events.
No owner-endorsed subscription billing event recipient has been proved.

Consequently **Application has no event publisher**, no startup
environment flag, no configured endpoint, no automatic replay and no
new public admin endpoint. An HTTP 200 from an arbitrary URL cannot
promote PUBLISHED or commercial readiness. Do not point the new
adapter at CP or invent a broker as a workaround.

## Engineering implemented, review pending

- `SignedHttpsEventTransport` uses the existing FB-04 protocol,
  signs the exact persisted envelope with an HMAC-SHA256 key mounted
  as an absolute secret file (32–4096 bytes), recipient
  `baobab-control-plane`, 300-second fresh timestamp, SHA-256
  body binding and explicit key ID. No URL credentials, user-supplied
  destinations, redirects or public HTTP except loopback test origin.
  A bounded 3-second response deadline and 16KiB cap reject response
  flooding/trickling. **Only** HTTP 202 with a valid matching
  `ACCEPTED` receipt or HTTP 200 with an exact matching
  `DUPLICATE` receipt is ACK; 400/409/413/422 are permanent;
  401, 503, timeouts and uncertain responses retry.
  The source/tenant/id/type are checked before sending.
  This is **a protocol implementation and test seam, not an
  authorised runtime route**.
- `OutboxPublicationWorker` has a per-tick
  `PublisherAuthority` gate; its `iam(...)` adapter uses
  fresh short-lived bearer contents, a verified
  `events:publish` JWT for **event-broker** audience and current
  issuer status without a positive cache. The exact client is
  checked. At most eight tenants are visited per tick, one delivery
  each and round-robin across ticks. Lease and interval are bounded.
  It is not connected to `Application`.
- V11 stores `first_attempt_at` and enforces the Shared
  **72-hour maximum retry horizon**, independent of the eight
  attempt bound, with quarantine as the outcome of expiry.
  Original event identity and envelope survive retries and recovery.
  This is strictly shorter than receipt retention (seven days).
- `OutboxReplayService` defaults to no authority. Its
  `CurrentReplayAuthority` must verify a **current**
  tenant/event-bound CP approval, named human principal, approval
  reference, reason and evidence before requeue. No CP admin
  authority/replay endpoint is currently certified, so it
  **cannot be activated**. PostgreSQL replay is accepted only from
  QUARANTINED, preserves canonical event contents, resets the
  delivery attempt window and records an immutable append-only
  `outbox_replay_audit` record atomically in the same transaction.
- `OutboxDeliveryQueue.health(tenant)` and bounded
  worker aggregate counters expose pending, in-flight, retry,
  quarantine, attempts and oldest age without event IDs,
  tenant IDs, key IDs, signatures or secrets as metrics labels.

## Required operational evidence and unimplemented SUB-02 exit work

1. Shared acceptance of an actual Subscriptions event-delivery
   consumer and its receipt/ACK contract. If CP is chosen, explicitly
   amend its **closed** ingress contract and implement a
   domain-specific consumer that treats events as hints, never
   as CP-authoritative ProductSubscription changes. Otherwise adopt
   an approved broker with the correct audience, scope and ACK shape.
   An ERP-only route is not a generic ingress.
2. IAM registration and current issuer proof for a dedicated publisher
   workload; receiver key rotation/registry and TLS certificate
   evidence, or approved broker equivalent. Do not reuse a
   Payments workload token or the CP assessor's token.
3. Wire the publisher only after those contracts are accepted and
   a disposable development/protected runtime is provisioned;
   test exactly one real durable ACK, crash after remote ACK and
   before local ACK, deduplicated recovery, revocation,
   tenant isolation, poison/conflict quarantine, bounded transport
   backlog, observed teardown and evidence retention.
4. Implement and qualify CP current AdministrativeGrant /
   Changeset/Approval authority for operator replay before
   exposing a UI or API, with no self-approval. Current service
   cannot turn a caller-supplied `approval_reference` into proof.
5. Define operational SLOs and alert thresholds from deployment
   evidence and capacity, not invented defaults. Minimum SLIs:
   event publication acceptance rate, oldest unpublished age,
   pending/retry/quarantine counts, first-to-ACK latency,
   total unsuccessful attempts, consumer receipt discrepancy,
   publisher current IAM failures. Metrics labels must use a
   bounded vocabulary; retain tenant-specific troubleshooting
   in RBAC-scoped logs/evidence, not public metrics.
6. Run PostgreSQL17/JDK21 final-head CI and security/container
   checks; review V11 migration reversibility, fatality and
   issuer/transport boundary; retain independent negative-case
   evidence. Commercial billing remains BLOCKED.

### Safety invariant

Signed transport is **not** bearer authentication and does not grant
scope to a consumer. Publisher-side IAM verification is additional
defence before delivery, never a replacement for receiver-side
signature/key/replay/producer/schema/tenant validation. Simulated
receipt tests are not native cross-engine qualification. No
component here moves money, grants entitlements or changes CP state.

### Rollback and incident response

Do not delete V11 or replay records while events exist. Disable any
future publisher configuration, revoke credential/key, retain original
outbox/inbox identities, inspect retry/quarantine/age and reconcile
the consuming authority before an operator-approved replay. A
quarantined event remains a correctness incident, not successful
completion. Do not edit production outbox rows manually.
