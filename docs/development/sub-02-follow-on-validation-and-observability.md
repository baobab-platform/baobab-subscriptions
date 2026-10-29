# SUB-02 follow-on — publisher correctness and telemetry (stacked on PR #55)

**Status:** review-only, no activation. **Dependency:** PR #55 and
[Shared #418](https://github.com/baobab-platform/shared/issues/418).
**Governance:** ADR-SUB-0014, ADR-SUB-0016, ADR-SUB-0017,
ADR-SUB-0018; pinned Shared `events/v1/envelope.schema.json`,
`subscriptions/v1/events.schema.json` and signed delivery policy.

## Correctness changes

1. `OutboxPublisher.runOneObserved` distinguishes `IDLE`,
   `ACKNOWLEDGED`, `RETRY_SCHEDULED`, `QUARANTINED` and
   `LEASE_LOST`. A receiver ACK followed by a lost local lease is
   **not** counted as committed publication. The next eligible worker
   may redeliver the same immutable identity, and its consumer must
   deduplicate by `(source,id)`. The existing boolean `runOne`
   continues to mean *work was attempted*, not *ACK persisted*.
2. Only exceptions from the **transport** are treated as uncertain
   deliveries. PostgreSQL claim, ACK, retry and quarantine write
   failures now propagate to the operator/scheduler; an unavailable
   database must not be logged as an ordinary upstream transport
   problem. The eighth failed attempt is correctly classified as
   `QUARANTINED`, with a single shared attempt ceiling.
3. `SignedHttpsEventTransport` rejects duplicate JSON object keys,
   unregistered event types, wrong source/tenant/subject, forged schema
   URI, mismatched persisted occurrence time, invalid canonical
   CloudEvents envelope and invalid payload according to pinned
   Shared schemas **before** signing or any network operation.
   The source event JSON is not normalized or rewritten during a
   delivery: the HMAC continues to cover its original byte sequence.
4. `OutboxPublicationWorker.Tick` records per-pass acknowledgements,
   retries, newly quarantined events and lost leases, along with
   bounded aggregate backlog and oldest age. These are diagnostic
   aggregates; they carry no tenant, event, key, subject or other
   unbounded labels. The process is NOT auto-started and these
   counters are not falsely advertised as long-lived production
   Prometheus metrics.

## Test evidence and run instructions

Owner CI must run Java 21 with PostgreSQL 17 and pass final-head
Foundation, container vulnerability, secret scanning and review gates.
`OutboxPublisherOutcomeTest` simulates ambiguous transport success,
local ACK failure, stale lease, eighth-attempt quarantine, idle
and permanent negative. `SignedHttpsEventTransportTest` exercises
a synthetic signed HTTP fixture with valid canonical sample data,
plus duplicate keys, forged dataschema, invalid payload, wrong tenant,
recipient, host and ACK. These simulations do not certify a live
receiver; keep the externally signed endpoint out of application
runtime until acceptance.

## SLI accounting semantics (no invented thresholds)

- **attempted** counts events claimed and submitted for an outcome,
  not delivery success.
- **acknowledged** counts lease-fenced PostgreSQL `PUBLISHED`
  transitions following matching durable receiver ACK.
- **retry_scheduled** counts a lease-fenced transient failure that
  remains retryable.
- **quarantined_now** counts newly terminal or exhausted failures.
- **lost_lease** counts a transport attempt with no owned PostgreSQL
  terminal transition; it is *not* a successful publication.
- **oldest_unpublished_seconds** is the age of the oldest pending,
  retry, leased or quarantined record, observed among the bounded
  tenants visited on that tick. For whole-platform alerting, use
  a separate approved all-tenant/partition monitoring consumer, not
  a partial per-tick value.
- All counters above are **per-tick** and reset at process restart.
  They are not reliable cumulative SLIs. Implement a controlled
  telemetry export / metrics pipeline and calibrate alert thresholds
  against real traffic and current estate policy before promotion.

## Remaining explicit holds

Shared #418 must settle the event receiver and producer registration.
CP's closed signed ingress currently accepts ERP provisioning only,
not the five billing events. IAM must issue and qualify an exact
publisher identity. Operator replay remains blocked without an accepted
CP current-approval verifier. A disposable connected test must prove
a real durable ACK, receiver deduplication, producer rejection,
revocation, tenant isolation, replay and complete teardown with
independent evidence custody. **No Subscriptions publisher, monetary
provider, entitlement or Payments dispatch is activated by this PR.**
