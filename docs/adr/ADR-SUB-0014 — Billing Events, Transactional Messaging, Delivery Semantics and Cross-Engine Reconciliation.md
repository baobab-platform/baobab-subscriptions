# ADR-SUB-0014 — Billing Events, Transactional Messaging, Delivery Semantics and Cross-Engine Reconciliation

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Eventing / Reliability / Cross-Engine Integration  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None

## Related Decisions

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-SUB-0004 — Usage Metering, Rating, Aggregation and Billable Consumption Model
- ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment
- ADR-SUB-0007 — Subscription Commercial Lifecycle, Amendments, Renewal, Suspension and Termination
- ADR-SUB-0009 — Charge Calculation, Adjustments, Credits and Monetary Calculation Model
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0013 — Payment Obligation Handoff, Collection State, Delinquency and Settlement Projection
- ADR-SUB-0015 — Kill Bill Adapter, BillingProvider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-SHARED-007 — Capability Contracts
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts

---

# 1. Context

`baobab-subscriptions` participates in several distributed workflows.

Conceptually:

```text
Control Plane
     │
     ▼
Subscriptions
     │
     ├────────► Kill Bill
     │
     ├────────► Payments
     │
     ├────────► ERP
     │
     └────────► Observability / other consumers
```

No single database transaction can atomically update all of these systems.

For example:

```text
Subscriptions DB transaction
        │
        ├── create InvoiceProjection
        ├── create FinancialObligation
        └── publish event
```

If the database commits but event publication fails:

```text
billing state = committed
event = missing
```

If the event is published before the database commits:

```text
consumer sees event
but authoritative state
does not exist
```

Likewise, messaging systems ordinarily provide delivery characteristics such as at-least-once delivery rather than magical business-level exactly-once execution.

Therefore Baobab must assume:

- duplicate messages;
- delayed messages;
- out-of-order messages;
- consumer downtime;
- producer downtime;
- network partitions;
- partial cross-engine failure;
- ambiguous external outcomes;
- event-schema evolution;
- replay;
- reconciliation after disaster recovery.

The event architecture must preserve each engine's domain authority while allowing the platform to converge safely.

---

# 2. Decision

`baobab-subscriptions` SHALL use a combination of:

1. local ACID transactions;
2. transactional outbox;
3. durable consumer inbox/deduplication;
4. idempotent handlers;
5. aggregate revision/version checks;
6. canonical versioned event contracts;
7. bounded retries;
8. dead-letter/quarantine handling;
9. explicit reconciliation;
10. audit and observability;

to coordinate distributed subscription-billing workflows.

Baobab SHALL NOT claim distributed exactly-once processing.

The reliability model is:

> **At-least-once delivery plus idempotent processing plus reconciliation.**

---

# 3. Fundamental distributed-systems rule

Baobab SHALL assume:

```text
message may arrive
0 times temporarily,
1 time normally,
or multiple times
```

until reliable delivery/retry/reconciliation converges the system.

Business correctness SHALL therefore not depend on receiving an event exactly once.

---

# 4. Local transaction authority

Each engine SHALL atomically commit only the state it owns.

For subscriptions:

```text
BEGIN LOCAL TRANSACTION

  mutate subscription-owned state

  write audit record

  write outbox event

COMMIT
```

External engine calls SHALL occur outside that transaction unless an explicitly justified local adapter operation can participate safely.

---

# 5. Transactional outbox

A domain mutation requiring external notification SHALL write its event intent to an outbox in the same local database transaction as the domain mutation.

Conceptually:

```text
Application Command
       │
       ▼
Subscriptions Transaction
       │
       ├── Domain State
       ├── Audit
       └── Outbox Record
              │
            COMMIT
              │
              ▼
        Outbox Publisher
              │
              ▼
         Event Transport
```

---

# 6. Why the outbox is mandatory

The following pattern is prohibited for financially material workflows:

```text
save database
     │
     ▼
publish event separately
```

without durable recovery of the publication intent.

Otherwise:

```text
DB commit succeeds
event publication fails
```

can permanently split platform state.

---

# 7. Outbox record

Conceptually:

```text
OutboxRecord
────────────────────────

outbox_id

event_id
event_type
schema_version

aggregate_type
aggregate_id
aggregate_revision

tenant_id
platform_account_id?

correlation_id
causation_id

payload

occurred_at
created_at

publication_status
attempt_count
next_attempt_at
published_at?
```

The exact persistence representation MAY evolve.

---

# 8. Event identity

Every canonical event SHALL have a globally unique stable:

```text
event_id
```

A retry SHALL preserve the same event identity.

---

# 9. Event identity versus aggregate identity

These SHALL remain distinct:

```text
event_id
!=
billing_subscription_id
!=
financial_obligation_id
!=
invoice_projection_id
```

---

# 10. Event envelope

Cross-engine subscription events SHOULD use a canonical envelope equivalent to:

```text
event_id

event_type
schema_version

producer

tenant_id
platform_account_id?

aggregate_type
aggregate_id
aggregate_revision

correlation_id
causation_id

occurred_at
published_at

payload
```

Normative definitions belong in Shared.

---

# 11. Producer authority

The event producer SHALL be authenticated through trusted infrastructure/workload identity.

A payload field saying:

```text
"producer": "baobab-cp"
```

does not prove that Control Plane emitted the event.

---

# 12. Tenant context

Tenant context SHALL be established through trusted messaging/workload context and validated against the referenced aggregate.

A consumer SHALL NOT trust arbitrary tenant identifiers inside the payload without consistency validation.

---

# 13. Event naming

Subscription-owned events SHALL use billing/subscription domain namespaces.

Examples MAY include:

```text
billing.subscription.created

billing.subscription.suspended

billing.subscription.terminated

billing.usage.recorded

billing.charge.created

billing.credit.created

billing.adjustment.created

billing.invoice.finalized

billing.invoice.issued

billing.obligation.created

billing.obligation.overdue
```

Exact canonical naming belongs in Shared.

---

# 14. Upstream event ownership

Subscriptions SHALL NOT publish events under a namespace implying Control Plane authority.

For example, subscriptions SHALL NOT emit:

```text
product.subscription.created
```

if ProductSubscription belongs to Control Plane.

---

# 15. Facts, not remote mutations

Events SHOULD describe durable domain facts.

For example:

```text
billing.obligation.overdue
```

is preferable to an event pretending to command:

```text
revoke.capability.now
```

where subscriptions lacks that authority.

---

# 16. Commands versus events

Baobab SHALL distinguish:

```text
Command
=
request that an authorised owner perform an action

Event
=
statement that an owned fact occurred
```

The distinction SHALL not be blurred merely because both use messaging infrastructure.

---

# 17. Inbox

Consumers SHALL durably record consumed event identity where duplicate execution could cause material effects.

Conceptually:

```text
InboxRecord
────────────────────────

consumer

event_id

received_at

processing_status

processed_at

aggregate_revision?

failure_code?

attempt_count
```

---

# 18. Deduplication

Before applying an event mutation:

```text
event received
      │
      ▼
event_id already processed?
      │
   ┌──┴──┐
  yes    no
   │      │
   ▼      ▼
return   process
safely
```

Duplicate delivery SHALL not duplicate business effects.

---

# 19. Durable deduplication

Deduplication SHALL survive:

- process restart;
- container replacement;
- deployment;
- transient database failover.

In-memory-only deduplication is insufficient for financially material events.

---

# 20. At-least-once delivery

Baobab SHALL design consumers under an at-least-once delivery assumption.

Therefore:

```text
delivery once
```

is an optimisation outcome, not a correctness assumption.

---

# 21. Exactly-once terminology

Baobab SHALL NOT document a workflow as:

```text
exactly once
```

unless that guarantee can be precisely demonstrated end-to-end.

Ordinarily, the correct statement is:

> effectively-once business outcome through durable deduplication and idempotency.

---

# 22. Idempotent handlers

Every financially material event handler SHALL be idempotent.

Examples include handlers that:

- create BillingSubscriptionProjection;
- create Charge;
- create InvoiceProjection;
- create FinancialObligation;
- project Payment outcome;
- update provider mapping.

---

# 23. Idempotency and deduplication are distinct

Deduplication asks:

> Have I processed this event ID?

Idempotency asks:

> Would repeating this business operation change the result incorrectly?

Both are required.

---

# 24. Aggregate revision

Events concerning mutable aggregate state SHOULD carry:

```text
aggregate_revision
```

or an equivalent monotonic version.

---

# 25. Stale event protection

Suppose subscriptions has applied:

```text
revision 12
```

and later receives:

```text
revision 10
```

Revision 10 SHALL NOT regress the aggregate.

---

# 26. Duplicate revision

If the same aggregate revision is received through a distinct event identity, the handler SHALL determine whether it is:

- semantically duplicate;
- conflicting;
- invalid.

Conflicting same-revision state requires reconciliation.

---

# 27. Revision gaps

If the consumer receives:

```text
revision 15
```

after:

```text
revision 12
```

the system SHALL NOT automatically assume revisions 13 and 14 were irrelevant.

Depending on the contract, it SHALL:

- fetch authoritative state;
- reconcile;
- safely apply state-based convergence;
- or quarantine until resolved.

---

# 28. No global ordering assumption

Baobab SHALL NOT assume global ordering across all events.

At most, ordering MAY be meaningful within a defined aggregate/partition where infrastructure and contracts support it.

---

# 29. Cross-aggregate ordering

Events concerning different aggregates SHALL not rely on accidental broker ordering.

For example:

```text
BillingAccount A
BillingSubscription B
FinancialObligation C
```

may be processed independently unless explicit causal dependencies exist.

---

# 30. Causation

`causation_id` SHALL allow a resulting event to reference the command/event that directly caused it.

Example:

```text
CP authoritative event
        │
        ▼
billing projection created
        │
        ▼
billing.subscription.created
```

The latter can carry the former as causation.

---

# 31. Correlation

`correlation_id` SHALL allow a distributed business workflow to be traced across engines.

Example:

```text
Control Plane
    │
    ▼
Subscriptions
    │
    ▼
Payments
    │
    ▼
ERP
```

may share a correlation identifier.

---

# 32. Correlation is not authority

A correlation ID SHALL NEVER be used as:

- authentication credential;
- authorization token;
- tenant authority;
- aggregate identity.

---

# 33. Event time

Baobab SHALL distinguish:

```text
occurred_at
published_at
received_at
processed_at
```

where materially relevant.

---

# 34. Occurrence time

`occurred_at` represents when the authoritative domain fact occurred.

It SHALL NOT be silently replaced by consumer receipt time.

---

# 35. Publication delay

An event may be published later than its occurrence.

Consumers SHALL preserve the authoritative occurrence semantics.

---

# 36. Schema versioning

Every cross-engine canonical event SHALL have explicit schema version semantics.

---

# 37. Schema ownership

Cross-engine schemas SHALL be governed through `baobab-platform/shared` where platform-wide contracts apply.

Individual engines SHALL not independently redefine another engine's canonical event schema.

---

# 38. Backward compatibility

Schema evolution SHOULD prefer additive backward-compatible changes where feasible.

Examples:

```text
add optional field
add new enum value where consumers tolerate unknown values
introduce new event version
```

rather than silently changing existing field meaning.

---

# 39. Semantic compatibility

A schema can remain syntactically valid while changing meaning incompatibly.

Therefore compatibility review SHALL consider both:

```text
shape
+
semantics
```

---

# 40. Breaking changes

A breaking event change SHALL require an explicit version transition.

Consumers SHALL be given a migration path.

---

# 41. Unknown fields

Consumers SHOULD tolerate unknown additive fields where the serialization contract permits.

---

# 42. Unknown enum values

Where future extensibility requires it, consumers SHOULD fail safely rather than crash or silently map unknown semantic values to incorrect known values.

---

# 43. Event payload minimisation

Events SHALL contain sufficient information for their contract without becoming replicas of entire domain aggregates.

---

# 44. No database replication through events

The event stream SHALL NOT be used to recreate unrestricted copies of another engine's database model.

Consumers should project only the information they legitimately require.

---

# 45. Sensitive information

Events SHALL minimise:

- PII;
- tax identifiers;
- payment data;
- provider secrets;
- internal credentials.

Canonical references SHOULD be preferred over unnecessary sensitive payload duplication.

---

# 46. Event retention

Retention SHALL reflect:

- replay needs;
- audit requirements;
- disaster recovery;
- financial reconciliation;
- privacy/data minimisation.

Transport retention alone SHALL not be treated as the sole permanent financial audit store.

---

# 47. Retry

Transient publication and consumption failures SHALL use bounded retries.

Retry policies SHOULD include:

- attempt limit or escalation threshold;
- exponential or policy-defined backoff;
- jitter where appropriate;
- failure classification.

---

# 48. Retryable failures

Examples may include:

```text
temporary broker unavailable

temporary database unavailable

transient downstream service failure
```

---

# 49. Non-retryable failures

Examples may include:

```text
unsupported schema

invalid tenant context

invalid monetary currency

impossible lifecycle transition

corrupt payload
```

Such conditions SHOULD enter quarantine/manual review rather than infinite retry.

---

# 50. Poison messages

A malformed or semantically impossible event SHALL NOT block an entire consumer indefinitely.

It SHALL be quarantined or dead-lettered according to platform policy.

---

# 51. Dead-letter / quarantine record

A quarantined event SHOULD retain:

```text
event_id

event_type

consumer

failure category

failure detail

attempt history

tenant context

first failure time

last failure time

resolution status
```

without leaking protected secrets.

---

# 52. Dead-letter is not resolution

Moving an event to a dead-letter mechanism SHALL NOT be considered business completion.

The underlying state discrepancy remains unresolved until:

- corrected;
- replayed;
- reconciled;
- or explicitly dispositioned.

---

# 53. Replay

Baobab SHALL support controlled event replay where operationally necessary.

Replay SHALL preserve original event identity where replaying the same event.

---

# 54. Replay safety

Consumers SHALL assume replay can deliver previously processed events.

Therefore replay safety depends on durable deduplication and idempotent handlers.

---

# 55. Replay scope

Replay SHOULD support bounded targeting such as:

```text
specific event

aggregate

tenant

time range

event type
```

rather than requiring unrestricted global replay.

---

# 56. Replay authorization

Financial event replay is a privileged operational action.

ADR-SUB-0016 controlled-mutation and audit requirements SHALL apply.

---

# 57. Replay does not change history

Replaying an event SHALL NOT change its original:

```text
event_id
occurred_at
aggregate_revision
```

merely because replay happens later.

---

# 58. Reprocessing with new logic

If historical facts need to be processed under newly introduced logic, that operation SHALL be explicitly distinguished from replay.

It MAY require:

- migration;
- recalculation;
- new projection version;
- corrective event.

---

# 59. State-based convergence

For authoritative upstream domains such as Control Plane, subscriptions MAY fetch current authoritative state after receiving an event.

This is especially useful where:

- events arrive out of order;
- revisions are missing;
- event payload intentionally remains small.

---

# 60. Event is trigger, authority remains upstream

For Control Plane-owned ProductSubscription:

```text
CP event
   │
   ▼
Subscriptions wakes
   │
   ▼
fetch/validate authoritative state
   │
   ▼
converge billing projection
```

The event triggers work.

It does not transfer authority.

---

# 61. No cross-engine database reads

State-based convergence SHALL use authorised APIs/contracts.

Subscriptions SHALL NOT read:

- Control Plane database;
- Payments database;
- ERP database;
- Kill Bill database

directly.

---

# 62. Provider events

Kill Bill/provider-native events SHALL terminate at the provider adapter boundary.

Conceptually:

```text
Kill Bill Event
      │
      ▼
KillBillAdapter
      │
      ├── authenticate
      ├── validate
      ├── deduplicate
      ├── resolve ProviderReference
      └── translate
      │
      ▼
Provider Observation
      │
      ▼
Subscriptions domain /
reconciliation
```

---

# 63. Provider events are not canonical platform events

A raw Kill Bill event SHALL NOT be republished unchanged as a canonical Baobab event.

---

# 64. Provider event authority

Provider events report provider-local observations.

They SHALL NOT authoritatively change:

- ProductSubscription;
- classification;
- tenant;
- PlatformAccount;
- CapabilityGrant;
- canonical Charge authority.

---

# 65. Payment events

Similarly:

```text
HyperSwitch
     │
     ▼
baobab-payments
     │
     ▼
canonical payment event
     │
     ▼
baobab-subscriptions
```

Subscriptions SHALL not consume HyperSwitch-native payment events directly as platform authority.

---

# 66. ERP events

Accounting outcomes originating from ERP SHALL be translated through canonical integration contracts where subscriptions legitimately requires them.

ERP-native database records SHALL not become subscription-domain state directly.

---

# 67. Reconciliation

Events are not sufficient by themselves to guarantee permanent convergence.

`baobab-subscriptions` SHALL implement explicit reconciliation.

---

# 68. Why reconciliation is mandatory

Even with reliable messaging:

- bugs occur;
- configuration drifts;
- operators intervene;
- providers behave unexpectedly;
- backups restore at different points;
- events may be quarantined;
- schema migrations fail.

Therefore:

> **Events drive convergence; reconciliation proves convergence.**

---

# 69. Reconciliation dimensions

Subscriptions SHALL be capable of reconciling, as applicable:

```text
Control Plane
      │
      ▼
Billing Projection
      │
      ▼
Charges / Invoice /
Financial Obligation
      │
   ┌──┴───────────────┐
   ▼                  ▼
Payments          Billing Provider
   │                  │
   └───────┬──────────┘
           ▼
          ERP
```

without treating all systems as one shared state machine.

---

# 70. Reconciliation ownership

Each reconciliation compares canonical expectations with relevant observed state.

It SHALL NOT silently transfer authority from the observed system.

---

# 71. Reconciliation categories

At minimum:

```text
AUTHORITATIVE_STATE_DRIFT

MISSING_PROJECTION

ORPHAN_PROJECTION

REVISION_DRIFT

MISSING_PROVIDER_RESOURCE

PROVIDER_STATE_DRIFT

MISSING_PAYMENT_REQUEST

PAYMENT_PROJECTION_DRIFT

MISSING_ERP_PROJECTION

DUPLICATE_RESOURCE

EVENT_DELIVERY_GAP

EVENT_PROCESSING_FAILURE

UNKNOWN_EXTERNAL_OUTCOME
```

or canonical equivalents.

---

# 72. Reconciliation result

A reconciliation run SHOULD classify findings equivalent to:

```text
IN_SYNC

REPAIRABLE

RETRY_REQUIRED

BLOCKED

MANUAL_REVIEW_REQUIRED
```

---

# 73. Auto-repair

Automated repair SHALL occur only when the intended result is:

- authoritative;
- deterministic;
- idempotent;
- tenant-safe;
- non-destructive;
- financially unambiguous.

---

# 74. No authority invention during repair

Reconciliation SHALL NEVER create upstream authority from downstream observations.

For example:

```text
Kill Bill subscription exists
```

does not justify inventing:

```text
Control Plane ProductSubscription
```

---

# 75. Orphan provider resource

If Kill Bill contains a resource with no valid canonical mapping:

```text
Provider Resource
      │
      X
Canonical Billing Projection
```

the system SHALL investigate/reconcile.

It SHALL not manufacture canonical state merely to match the provider.

---

# 76. Orphan payment

Likewise, a payment without a valid FinancialObligation does not cause subscriptions to invent an obligation.

---

# 77. Reconciliation cadence

Baobab SHOULD support:

1. event-triggered reconciliation;
2. targeted reconciliation after ambiguous outcomes;
3. operator-triggered reconciliation;
4. bounded periodic reconciliation.

---

# 78. No uncontrolled full scans

Periodic reconciliation SHOULD use:

- partitions;
- checkpoints;
- bounded batches;
- tenant scope;
- time windows;

rather than repeatedly scanning every financial object without operational control.

---

# 79. Reconciliation checkpoints

Long-running reconciliation SHOULD persist progress/checkpoints so interruption does not require unsafe restart from the beginning.

---

# 80. Reconciliation records

Material reconciliation actions SHALL be durably recorded.

Conceptually:

```text
ReconciliationRecord
────────────────────────

reconciliation_id

scope

resource_type
resource_id

expected_state
observed_state

classification

recommended_action

action_taken

actor/workload

started_at
completed_at
```

---

# 81. Reconciliation repair as controlled mutation

Any reconciliation action that changes state SHALL satisfy ADR-SUB-0016.

Read permission and repair permission SHALL remain distinct.

---

# 82. Manual repair

Manual repair SHALL require:

- target resource;
- tenant context;
- reason;
- authorised actor;
- expected change;
- audit evidence.

Routine direct database editing SHALL NOT be the reconciliation model.

---

# 83. Event recovery after outage

After an event-transport outage:

```text
Outbox
accumulates events
      │
      ▼
Transport recovers
      │
      ▼
Publisher resumes
      │
      ▼
Consumers deduplicate
and converge
```

Domain transactions need not be rolled back merely because the transport was temporarily unavailable.

---

# 84. Backpressure

Publishers and consumers SHALL handle backpressure.

A temporary backlog SHALL not cause uncontrolled:

- memory growth;
- thread creation;
- provider calls;
- database connection exhaustion.

---

# 85. Consumer concurrency

Concurrent event processing SHALL preserve aggregate correctness.

Where several events mutate the same aggregate, use:

- revision checks;
- optimistic locking;
- appropriate serialization;
- or equivalent concurrency controls.

---

# 86. Financial concurrency

Two workers SHALL NOT independently create duplicate:

- Charges;
- Credits;
- InvoiceProjections;
- FinancialObligations;
- payment handoffs

for the same business fact.

---

# 87. Outbox publication state

Publisher state SHOULD distinguish:

```text
PENDING

IN_FLIGHT

PUBLISHED

RETRY

FAILED / QUARANTINED
```

or equivalent operational states.

---

# 88. Broker acknowledgement

An event SHALL not be marked successfully published until the configured transport acknowledgement semantics have been satisfied.

---

# 89. Crash recovery

If the publisher crashes after transport acceptance but before local `published` marking, the event may be republished.

This is expected.

Consumers SHALL deduplicate.

---

# 90. Consumer atomicity

Where feasible, a consumer SHOULD atomically commit:

```text
domain projection mutation
+
inbox/dedup record
+
new outbox event
```

within one local transaction.

---

# 91. Chained workflows

Conceptually:

```text
Inbound Event
      │
      ▼
BEGIN
  inbox record
  domain mutation
  outbound event
COMMIT
      │
      ▼
publish later
```

This supports reliable event chains without distributed transactions.

---

# 92. Event contract testing

Cross-repo event contracts SHALL have automated compatibility tests.

At minimum:

- schema validation;
- producer examples;
- consumer examples;
- version compatibility;
- unknown-field handling;
- required-field enforcement.

---

# 93. Shared contract governance

Changes to Shared canonical contracts SHALL be reviewed for all known producers and consumers.

A producer repository SHALL not merge a breaking event change merely because its local tests pass.

---

# 94. Contract version support

During migration, an engine MAY temporarily support multiple event versions.

Such compatibility windows SHALL be explicit and removable.

---

# 95. Event deprecation

Deprecated event versions SHALL have:

- replacement;
- migration plan;
- known consumers;
- removal condition.

---

# 96. Disaster recovery

After restoration from backup, event checkpoints and domain state may represent different recovery points.

Therefore disaster recovery SHALL include reconciliation.

---

# 97. Backup restoration principle

A restored database SHALL not assume:

```text
restored state
=
entire platform state
```

Cross-engine reconciliation is mandatory before declaring financial consistency restored.

---

# 98. Event transport is not source of truth

The broker/event log SHALL not automatically become canonical domain authority.

Domain authority remains with the owning engine.

---

# 99. Audit versus event history

An event log and an audit log serve different purposes.

```text
Event:
what domain fact was communicated

Audit:
who/what caused or changed protected state,
under what authority
```

One SHALL not automatically substitute for the other.

---

# 100. Observability

Subscriptions SHOULD expose metrics for:

- outbox backlog;
- oldest unpublished event age;
- publication failures;
- inbox backlog;
- processing latency;
- duplicate events;
- stale events;
- revision gaps;
- retry counts;
- quarantined events;
- reconciliation backlog;
- reconciliation failures;
- cross-engine drift.

---

# 101. Tracing

Distributed tracing SHOULD propagate:

```text
correlation_id
```

and appropriate technical trace context across:

```text
CP
→ Subscriptions
→ Payments
→ ERP
```

and provider adapters where supported.

---

# 102. Health versus convergence

A process can be technically healthy while financially inconsistent.

Therefore:

```text
HTTP 200 health
!=
billing convergence
```

Operational readiness SHALL expose both infrastructure health and relevant domain/reconciliation health.

---

# 103. Alerting

Alert-worthy conditions SHOULD include:

- growing outbox backlog;
- prolonged event publication failure;
- poison financial event;
- persistent revision gap;
- repeated reconciliation drift;
- INTERNAL payment event;
- duplicate provider monetary resource;
- unresolved payment outcome;
- cross-tenant event mismatch.

---

# 104. Domain invariants

### INV-EVT-01

Every canonical event has stable event identity.

### INV-EVT-02

Retrying publication preserves event identity.

### INV-EVT-03

Domain state and outbox intent are committed atomically where an event is required.

### INV-EVT-04

Consumers assume duplicate delivery.

### INV-EVT-05

Financial event deduplication is durable.

### INV-EVT-06

Handlers are idempotent.

### INV-EVT-07

Baobab does not rely on distributed exactly-once execution.

### INV-EVT-08

Stale aggregate revisions cannot regress newer state.

### INV-EVT-09

Global event ordering is not assumed.

### INV-EVT-10

Correlation ID is not authorization.

### INV-EVT-11

Payload tenant ID alone is not tenant authority.

### INV-EVT-12

Canonical cross-engine event schemas are versioned.

### INV-EVT-13

Breaking semantic changes require explicit contract evolution.

### INV-EVT-14

Events do not transfer domain authority.

### INV-EVT-15

Subscriptions does not publish Control Plane-owned facts as if it owned them.

### INV-EVT-16

Raw provider events are not canonical platform events.

### INV-EVT-17

Kill Bill events cannot redefine ProductSubscription authority.

### INV-EVT-18

HyperSwitch events reach subscriptions through the Payments boundary.

### INV-EVT-19

Dead-lettering does not resolve the underlying business inconsistency.

### INV-EVT-20

Replay is safe through deduplication and idempotency.

### INV-EVT-21

Replay preserves original event identity and occurrence semantics.

### INV-EVT-22

No cross-engine database reads are used for reconciliation.

### INV-EVT-23

Reconciliation cannot manufacture upstream authority.

### INV-EVT-24

Automated repair is permitted only when unambiguous and idempotent.

### INV-EVT-25

Financial repair is controlled and audited.

### INV-EVT-26

Broker state is not canonical domain authority.

### INV-EVT-27

Event history is not a substitute for security audit.

### INV-EVT-28

Disaster recovery includes cross-engine reconciliation.

### INV-EVT-29

A healthy process does not imply reconciled billing state.

### INV-EVT-30

Sensitive provider/payment credentials never belong in canonical event payloads.

---

# 105. Failure matrix

| Failure | Behaviour |
|---|---|
| DB transaction fails | No state/outbox fact committed |
| DB succeeds, broker unavailable | Outbox retains event for retry |
| Broker receives duplicate | Consumer deduplicates |
| Consumer crashes before commit | Event safely redelivered |
| Consumer commits then crashes before ack | Redelivery deduplicated |
| Event arrives out of order | Revision/state convergence |
| Revision gap detected | Fetch/reconcile authoritative state |
| Invalid schema | Quarantine |
| Unknown event version | Fail safely / supported migration path |
| Provider event duplicated | Provider ingress deduplicates |
| Payment event duplicated | Inbox deduplicates |
| Event permanently missing | Reconciliation detects drift |
| DR restores unequal checkpoints | Reconciliation restores convergence |

---

# 106. Alternatives considered

## 106.1 Publish after DB commit without an outbox

**Rejected.**

Creates a permanent lost-event window.

## 106.2 Publish before DB commit

**Rejected.**

Consumers may observe facts that never became authoritative.

## 106.3 Rely on exactly-once broker delivery

**Rejected.**

Business correctness must survive duplicates and ambiguous failures.

## 106.4 Deduplicate only in memory

**Rejected.**

Restart destroys the deduplication history.

## 106.5 Require global event ordering

**Rejected.**

It introduces unnecessary coupling and does not solve cross-engine partial failure.

## 106.6 Put complete database aggregates in every event

**Rejected.**

Creates coupling, data leakage and shadow databases.

## 106.7 Republish raw Kill Bill events

**Rejected.**

Provider schemas are not canonical Baobab contracts.

## 106.8 Consume HyperSwitch events directly in subscriptions

**Rejected.**

Bypasses `baobab-payments`.

## 106.9 Treat dead-lettering as success

**Rejected.**

The business inconsistency remains.

## 106.10 Use event replay instead of reconciliation

**Rejected.**

Replay cannot detect every form of drift or operator/provider mutation.

## 106.11 Let reconciliation create missing upstream objects

**Rejected.**

Downstream observations cannot manufacture upstream authority.

## 106.12 Use shared databases for consistency

**Rejected.**

Violates engine ownership and polyrepo/polyglot isolation.

---

# 107. Consequences

## Positive

- Domain mutations and event publication become recoverable.
- Duplicate messages become safe.
- Cross-engine outages become survivable.
- Financial workflows remain idempotent.
- Event contracts can evolve deliberately.
- Provider-native events remain contained.
- Replay becomes operationally viable.
- Cross-engine drift becomes detectable.
- Disaster recovery gains a convergence mechanism.
- Engine authority remains intact.

## Negative

- Outbox and inbox persistence add operational complexity.
- Consumers need revision and deduplication logic.
- Reconciliation requires dedicated implementation.
- Contract governance spans repositories.
- Dead-letter handling requires operational processes.
- Eventual consistency must be visible to operators.

These costs are accepted because distributed financial workflows cannot safely depend on synchronous happy-path execution.

---

# 108. Implementation requirements

A conforming implementation SHALL provide:

1. transactional outbox;
2. durable outbox publisher;
3. stable event IDs;
4. canonical event envelope;
5. aggregate revisions where applicable;
6. correlation and causation IDs;
7. durable consumer inbox;
8. event deduplication;
9. idempotent handlers;
10. stale-revision protection;
11. revision-gap handling;
12. bounded retry;
13. quarantine/dead-letter handling;
14. controlled replay;
15. versioned Shared contracts;
16. provider-event translation;
17. payment-event boundary enforcement;
18. event contract tests;
19. reconciliation service/process;
20. reconciliation records;
21. controlled repair;
22. backpressure controls;
23. concurrency protection;
24. observability;
25. audit;
26. DR reconciliation procedures.

---

# 109. Required tests

At minimum:

## Outbox

- domain commit and outbox commit atomically;
- transaction rollback creates neither;
- broker unavailable after commit;
- publisher restart;
- duplicate publication.

## Inbox

- first delivery;
- duplicate delivery;
- restart before duplicate;
- crash before transaction commit;
- crash after commit before acknowledgement.

## Ordering

- stale revision;
- future revision gap;
- duplicate revision;
- out-of-order lifecycle events.

## Contracts

- valid schema;
- invalid schema;
- additive field;
- supported old version;
- unsupported version;
- semantic incompatibility.

## Replay

- single-event replay;
- aggregate replay;
- already-processed replay;
- replay after consumer restart.

## Providers

- duplicate Kill Bill event;
- unknown provider reference;
- provider event for wrong tenant;
- provider state conflicting with canonical state.

## Payments

- duplicate payment event;
- stale payment event;
- orphan payment;
- HyperSwitch-native event cannot bypass Payments.

## Reconciliation

- missing projection;
- orphan projection;
- provider drift;
- missing payment request;
- unknown external outcome;
- safe auto-repair;
- ambiguous manual-review case.

## Security

- spoofed producer;
- tenant mismatch;
- unauthorised replay;
- unauthorised repair;
- sensitive field redaction.

---

# 110. Recommended implementation sequence

```text
1. Canonical event envelope
          │
          ▼
2. Shared event schemas
          │
          ▼
3. Transactional outbox
          │
          ▼
4. Durable publisher
          │
          ▼
5. Consumer inbox
          │
          ▼
6. Idempotent handlers
          │
          ▼
7. Aggregate revisions
          │
          ▼
8. Retry / quarantine
          │
          ▼
9. Contract compatibility tests
          │
          ▼
10. Provider event ingress
          │
          ▼
11. Payment event ingress
          │
          ▼
12. Replay tooling
          │
          ▼
13. Reconciliation framework
          │
          ▼
14. Controlled repair
          │
          ▼
15. DR / observability hardening
```

---

# 111. End-to-end event flow

```text
              CONTROL PLANE
                    │
           authoritative event
                    │
                    ▼
            ┌────────────────┐
            │ Subscriptions  │
            │     Inbox      │
            └───────┬────────┘
                    │
                    ▼
            Local Transaction
                    │
          ┌─────────┼──────────┐
          ▼         ▼          ▼
      Projection   Audit     Outbox
          │                    │
          │                  COMMIT
          │                    │
          │                    ▼
          │               Publisher
          │                    │
          │                    ▼
          │               Event Bus
          │              ┌─────┴─────┐
          │              ▼           ▼
          │          Payments       ERP
          │
          └────────► Kill Bill Adapter
                           │
                           ▼
                       Kill Bill


All cross-engine paths:

local transaction
      +
durable message intent
      +
idempotent consumer
      +
reconciliation
```

---

# 112. Worked example — lost publication window avoided

Without an outbox:

```text
Create FinancialObligation
          │
          ▼
DB COMMIT
          │
          X process crashes
          │
          ▼
Payment event never published
```

The customer is billed internally but collection never begins.

With the required design:

```text
BEGIN
   FinancialObligation
   Outbox(PaymentRequired)
COMMIT
          │
          X process crashes
          │
       restart
          │
          ▼
Publisher finds pending outbox
          │
          ▼
Payment request delivered
```

No billing fact is lost merely because publication was temporarily interrupted.

---

# 113. Worked example — duplicate delivery

The broker delivers:

```text
event_id = EVT-100
```

twice.

The first delivery:

```text
Inbox lookup
    │
    ▼
not found
    │
    ▼
process
    │
    ▼
persist Inbox(EVT-100)
```

The second:

```text
Inbox lookup
    │
    ▼
EVT-100 already processed
    │
    ▼
no duplicate business effect
```

A second FinancialObligation is not created.

---

# 114. Worked example — out-of-order Control Plane state

Subscriptions receives:

```text
ProductSubscription revision 8
classification = COMMERCIAL
```

and converges.

Later it receives delayed:

```text
revision 7
classification = INTERNAL
```

The system does **not** regress to revision 7.

Instead:

```text
incoming revision 7
<
current revision 8
       │
       ▼
stale event
       │
       ▼
ignore for mutation
retain appropriate diagnostics
```

---

# 115. Worked example — event does not transfer authority

Suppose Kill Bill reports:

```text
provider subscription = ACTIVE
```

while Control Plane reports:

```text
ProductSubscription = TERMINATED
```

Baobab does not conclude:

```text
ProductSubscription = ACTIVE
```

Instead:

```text
CP authoritative state
TERMINATED
       │
       ▼
Expected billing/provider state
TERMINATED
       │
       ▼
Kill Bill observed
ACTIVE
       │
       ▼
PROVIDER_STATE_DRIFT
       │
       ▼
reconciliation
```

The provider is repaired toward Baobab authority, not the reverse.

---

# 116. Worked example — disaster recovery

Assume:

```text
Subscriptions restored to:
12:00

Payments restored to:
12:05

ERP restored to:
11:58
```

Baobab SHALL NOT assume consistency merely because all three services start successfully.

Instead:

```text
Restore
   │
   ▼
Infrastructure health
   │
   ▼
Cross-engine reconciliation
   │
   ├── subscription obligations
   ├── payment outcomes
   ├── provider mappings
   └── ERP projections
   │
   ▼
Resolve drift
   │
   ▼
Financial convergence
```

Only then can financial recovery be considered complete.

---

# 117. Final Decision

Baobab SHALL implement subscription billing integration as **reliable asynchronous convergence between independently authoritative engines**, not as a distributed monolith.

The principal transaction rule is:

> **A domain mutation and the durable intent to publish its resulting event must be committed together through the transactional outbox pattern.**

The principal delivery rule is:

> **Baobab assumes at-least-once delivery and achieves effectively-once business outcomes through durable deduplication and idempotent processing rather than claiming magical end-to-end exactly-once execution.**

The principal ordering rule is:

> **Global ordering is not assumed; aggregate revisions, authoritative-state lookup and reconciliation prevent delayed events from regressing newer state.**

The principal authority rule is:

> **An event communicates a fact; it does not transfer ownership of that fact's domain to the consumer.**

The principal provider rule is:

> **Kill Bill, HyperSwitch and other provider-native events terminate at their owning Baobab adapter/engine boundaries and are translated before entering canonical platform workflows.**

The principal contract rule is:

> **Cross-engine event contracts are canonical, explicitly versioned and governed through Shared rather than independently redefined by producers or consumers.**

The principal replay rule is:

> **Replay repeats communication of historical facts; it does not rewrite their identity, occurrence time or authority.**

The principal reconciliation rule is:

> **Events drive convergence; reconciliation proves convergence.**

The principal repair rule is:

> **Reconciliation may repair deterministic downstream projections, but it may never manufacture upstream authority from downstream or provider state.**

The principal recovery rule is:

> **Restoring healthy processes after a disaster is not equivalent to restoring financial consistency; cross-engine reconciliation is part of recovery.**

And the principal platform rule is:

> **Control Plane, Subscriptions, Payments, ERP and providers retain independent authority and storage boundaries, converging through canonical contracts, local transactions, durable messaging, idempotency and reconciliation rather than shared databases or distributed ACID transactions.**