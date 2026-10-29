# SUB-01 — Outbox publication custody (ADR-SUB-0014/0017/0018)

V10 adds crash-recoverable delivery custody to the existing transactional
outbox without altering event contents or introducing a broker.

`store.outboxDeliveryQueue()` obtains the PostgreSQL-backed queue.
An internal, explicitly invoked `OutboxPublisher` requires an injected
transport that returns `ACKNOWLEDGED` only after that transport's **durable**
acknowledgement. Application does not instantiate/start any publisher,
configure an endpoint or accept a public publish/replay command.

Each `claim(tenant,worker,lease)` selects at most one due row through
`FOR UPDATE SKIP LOCKED`, increments attempt/lease epoch and stores an
expiry in the database's clock domain. Workers must carry the returned
exact tenant, event ID, lease epoch and owner into `published` or
`failed`; stale/expired leases cannot overwrite a successor. No transport
operation runs with an open SQL transaction. If a process stops after an
external ACK and before `published`, another claim can redeliver the
**same canonical event_id** after expiry; downstream MUST deduplicate.

Explicit transient failures enter RETRY with exponential seconds plus
stable event-derived jitter. Permanent failures and eight-attempt
exhaustion enter QUARANTINED. A crashed eighth attempt is swept to
QUARANTINED when the next claimant inspects that tenant. Quarantined
rows are NOT resolved; a future operator-controlled replay needs
authority, reason, audit, revalidation and accepted transport policy.

`health(tenant)` gives pending, inflight, retry, quarantine, cumulative
attempts and oldest-unpublished age. Keep tenant IDs out of unbounded
metrics label sets. No public endpoint is opened by this increment.

## Qualification and operational boundaries

The PostgreSQL integration suite exercises stable event identity,
cross-tenant denials, two workers, wrong owner, stale epoch, retry,
poison quarantine, exhausted crashed claim and an acknowledged fixture.
CI's `TEST_DATABASE_URL` service supplies PostgreSQL 17.

No live event transport, IAM publisher identity, outbox worker scheduler,
telemetry dashboard, SLO, operator replay or broker proof is supplied here.
The SQL migration does not permit any monetary provider operation.
SUB-02 owns those remaining publication tasks. Do not claim that
`PUBLISHED` is attainable in production until an independently
accepted, authenticated transport adapter and acknowledgement contract
exist; fixture acknowledgements are tests only.

On rollback, do not drop V10 or erase publication custody while the
table contains live events. Stop any future publisher, review queued
state and preserve all event/audit identities before a controlled
forward migration. Do not routinely mutate subscription tables by hand.
