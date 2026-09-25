# ADR-SUB-0018 — Availability, Backup, Recovery, Disaster Resilience and Financial Data Durability

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Availability / Disaster Recovery / Data Durability  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None

## Related Decisions

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-SUB-0004 — Usage Metering, Rating, Aggregation and Billable Consumption Model
- ADR-SUB-0005 — Subscription Catalogue, Pricing, Plan Versioning and Billing Terms Resolution
- ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment
- ADR-SUB-0007 — Subscription Commercial Lifecycle, Amendments, Renewal, Suspension and Termination
- ADR-SUB-0008 — Billing Periods, Anchors, Calendars, Proration and Temporal Semantics
- ADR-SUB-0009 — Charge Calculation, Adjustments, Credits and Monetary Calculation Model
- ADR-SUB-0010 — Tax Jurisdiction, Tax Determination, Exemptions and Tax Calculation Boundary
- ADR-SUB-0011 — Billing Account, Payer, Invoice Recipient and Financial Responsibility Model
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0013 — Payment Obligation Handoff, Collection State, Delinquency and Settlement Projection
- ADR-SUB-0014 — Billing Events, Transactional Messaging, Delivery Semantics and Cross-Engine Reconciliation
- ADR-SUB-0015 — Kill Bill Adapter, BillingProvider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-SUB-0017 — Observability, Service Levels, Operational Health, Incident Response and Billing Correctness
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts

---

# 1. Context

`baobab-subscriptions` manages financially material state.

Its durable records may include:

```text id="x1nzka"
BillingSubscriptionProjection

BillingAccountProjection

BillingTerms

UsageRecord

UsageAggregate

RatedUsage

Charge

Credit

Adjustment

InvoiceProjection

FinancialObligation

PaymentStateProjection

ProviderReference

Outbox / Inbox

Audit

Reconciliation State
```

Loss, duplication, corruption, or inconsistent restoration of these records can cause:

- incorrect billing;
- lost billable usage;
- duplicate collection;
- incorrect credits;
- incorrect invoice balances;
- broken historical reproducibility;
- divergence from Control Plane;
- divergence from Payments;
- divergence from ERP;
- divergence from Kill Bill.

Ordinary application availability is therefore insufficient.

The architecture must answer:

> What survives a process failure?

> What survives a node failure?

> What survives database failure?

> What survives availability-zone failure?

> What survives regional failure?

> What happens when different Baobab engines recover to different points in time?

> How do we prove that restored billing state is financially correct?

---

# 2. Decision

Baobab SHALL design `baobab-subscriptions` for:

1. durable financial persistence;
2. stateless application failover where practicable;
3. database high availability;
4. automated and tested backups;
5. point-in-time recovery where supported;
6. explicit Recovery Point Objectives;
7. explicit Recovery Time Objectives;
8. provider-independent recovery;
9. cross-engine reconciliation after restoration;
10. immutable or append-oriented financial history;
11. controlled disaster declaration and recovery;
12. regular recovery exercises.

A backup SHALL NOT be considered successful merely because it was created.

It is successful only when it is:

```text id="rc38zc"
created
+
protected
+
retained
+
restorable
+
tested
```

---

# 3. Core durability principle

Financial facts SHALL be persisted before success is acknowledged where the operation requires durable commitment.

Conceptually:

```text id="jpspcq"
Request
   │
   ▼
Validate
   │
   ▼
BEGIN
   domain mutation
   audit
   outbox
COMMIT
   │
   ▼
Success
```

The system SHALL NOT acknowledge a financially material mutation before its authoritative local transaction is durably committed.

---

# 4. Availability is not durability

These concepts SHALL remain distinct:

```text id="y1hvs4"
Availability
=
can the service operate now?

Durability
=
will committed state survive failure?

Recoverability
=
can valid state be restored?

Correctness
=
does restored state still represent the right financial facts?
```

A highly available system can still lose data.

A durable backup can still be operationally useless if it cannot be restored.

---

# 5. Recovery hierarchy

Baobab SHALL distinguish:

```text id="9vtodx"
Instance Recovery

Node Recovery

Database Recovery

Availability-Zone Recovery

Regional Recovery

Cross-Engine Financial Recovery
```

Each has different consequences.

---

# 6. Stateless application instances

Application instances SHOULD remain stateless with respect to authoritative billing state.

Authoritative state SHALL not depend on:

- local container filesystem;
- process memory;
- a particular application node.

---

# 7. Ephemeral state

Caches MAY be ephemeral where loss does not compromise financial correctness.

A cache SHALL NOT be the sole copy of:

- accepted usage;
- Charge;
- Credit;
- InvoiceProjection;
- FinancialObligation;
- audit;
- outbox event;
- provider mapping.

---

# 8. PostgreSQL authority

Baobab-owned subscription state SHALL reside in the subscriptions-owned PostgreSQL persistence boundary.

No other engine SHALL directly share or mutate that database.

---

# 9. PostgreSQL version

The Baobab subscriptions persistence layer SHALL target the platform-approved PostgreSQL 17 baseline unless superseded by a later accepted platform decision.

Provider databases remain separately governed.

---

# 10. Kill Bill database independence

The fact that Baobab subscriptions uses PostgreSQL 17 SHALL NOT imply that a chosen Kill Bill release supports or must use PostgreSQL 17.

Kill Bill's supported database matrix SHALL be independently verified and pinned during provider deployment.

---

# 11. Database high availability

Production deployment SHOULD provide database redundancy appropriate to the defined service tier.

The architecture SHOULD tolerate loss of an individual database node without requiring reconstruction from backup under ordinary failure conditions.

---

# 12. HA is not backup

Database replication SHALL NOT be treated as backup.

```text id="v7xx81"
Replication
!=
Backup
```

Replication may reproduce:

- accidental deletion;
- corruption;
- malicious mutation.

Independent recoverable backups remain mandatory.

---

# 13. Backup scope

Backups SHALL include all authoritative persistence necessary to reconstruct subscriptions-owned state.

This includes, where stored in the same persistence boundary:

- domain tables;
- event outbox;
- consumer inbox;
- audit state;
- provider mappings;
- reconciliation metadata;
- schema metadata.

---

# 14. Backup consistency

Backups SHALL be transactionally consistent according to the supported database backup mechanism.

The platform SHALL not assemble an authoritative backup from unrelated table exports taken at arbitrary times.

---

# 15. Point-in-time recovery

Production persistence SHOULD support Point-in-Time Recovery where infrastructure permits.

Conceptually:

```text id="0v9a6d"
Base Backup
     │
     ├── WAL
     ├── WAL
     ├── WAL
     └── WAL
          │
          ▼
restore to selected
recovery point
```

---

# 16. Recovery Point Objective

Baobab SHALL define an explicit:

```text id="wx4j9j"
RPO
```

for production subscriptions data.

This ADR does not invent a numerical RPO.

The production value SHALL be determined from:

- financial exposure;
- transaction volume;
- infrastructure capability;
- cost;
- regulatory obligations;
- business tolerance.

---

# 17. Recovery Time Objective

Baobab SHALL similarly define an explicit:

```text id="py13hp"
RTO
```

for the subscription billing capability.

Again, the value SHALL be evidence-based rather than aspirational.

---

# 18. Different workloads may require different recovery priorities

For example:

```text id="3n2u50"
read-only billing history

usage ingestion

invoice finalisation

payment handoff

administrative reconciliation
```

need not all resume simultaneously.

---

# 19. Recovery order

A disaster recovery plan SHOULD identify a safe capability restoration order.

Conceptually:

```text id="vfrz9c"
Persistence
     │
     ▼
Core application
     │
     ▼
Event processing
     │
     ▼
Upstream reconciliation
     │
     ▼
Provider reconciliation
     │
     ▼
Payment handoff
     │
     ▼
ERP projection
     │
     ▼
Normal billing processing
```

---

# 20. Financially dangerous capabilities resume last

Capabilities capable of generating new external monetary movement SHOULD not automatically resume before the system has sufficient confidence in restored state.

In particular:

```text id="tq6wwk"
payment handoff
```

may require reconciliation before resumption.

---

# 21. Recovery mode

The service SHOULD support an operational recovery mode.

Conceptually:

```text id="ztq1vi"
NORMAL

RECOVERY_READ_ONLY

RECOVERY_RECONCILING

RECOVERY_RESTRICTED

NORMAL
```

Exact implementation MAY differ.

---

# 22. Recovery mode is not domain state

Recovery mode is an operational control.

It SHALL NOT rewrite:

- ProductSubscription;
- classification;
- invoice;
- obligation;
- payment;
- provider state.

---

# 23. Restore does not equal resume

The central recovery rule is:

```text id="q87j3v"
Restore
!=
Resume Financial Processing
```

Restored state must first be validated.

---

# 24. Cross-engine restoration problem

Baobab is polyrepo, polyglot and distributed.

A disaster may produce:

```text id="pc8cmq"
Control Plane restored to 10:05

Subscriptions restored to 10:02

Payments restored to 10:07

ERP restored to 09:58

Kill Bill restored independently
```

There is no legitimate global database rollback transaction that makes these identical.

---

# 25. Cross-engine reconciliation

Therefore disaster recovery SHALL include:

```text id="yhy97f"
Control Plane
      │
      ▼
Subscriptions
      │
      ├────► Kill Bill
      │
      ├────► Payments
      │
      └────► ERP
```

reconciliation according to each engine's authority.

---

# 26. Authority survives disaster recovery

Recovery SHALL NOT change domain authority.

After restoration:

- Control Plane still owns ProductSubscription/classification/entitlement;
- Subscriptions still owns billing projections and obligations;
- Payments still owns payment execution;
- ERP still owns accounting;
- Kill Bill remains provider state.

---

# 27. Latest timestamp is not automatically authoritative

Suppose:

```text id="0ay9zs"
Subscriptions backup:
10:02

Kill Bill:
10:05
```

Kill Bill's newer timestamp does not make its state canonical.

Authority is architectural, not determined by which database happens to contain newer data.

---

# 28. Recovery reconciliation direction

Where upstream authority survives, downstream projections SHALL converge toward it.

Example:

```text id="2zpvfs"
CP ProductSubscription
TERMINATED
      │
      ▼
Subscriptions restored
ACTIVE projection
      │
      ▼
reconcile
      │
      ▼
Billing projection
TERMINATED
      │
      ▼
provider convergence
```

---

# 29. Financial history requires special handling

Some subscriptions-owned facts are themselves authoritative financial history.

Examples:

- accepted UsageRecord;
- Charge;
- Credit;
- Adjustment;
- finalised InvoiceProjection;
- FinancialObligation.

These SHALL NOT be casually regenerated from current upstream state.

---

# 30. Projection versus financial fact

Recovery SHALL distinguish:

```text id="h7sfyw"
reconstructible projection
```

from:

```text id="ebzy9v"
authoritative financial fact
```

The recovery strategy MAY differ.

---

# 31. Reconstructible state

Examples MAY include certain:

- derived readiness;
- caches;
- provider observations;
- non-authoritative operational indexes.

These MAY be rebuilt where deterministic.

---

# 32. Non-reconstructible or historically sensitive state

Examples include accepted usage and finalised monetary facts.

These require durable preservation and controlled correction.

---

# 33. No silent financial recomputation

Restoration SHALL NOT automatically rerun historical billing using current pricing rules.

Historical calculation must use the applicable historical:

- pricing version;
- BillingTerms;
- classification treatment;
- tax inputs;
- rounding rules.

---

# 34. Historical reproducibility

The platform SHALL retain sufficient provenance to answer:

> Why was this amount calculated?

after restoration.

---

# 35. Pricing history durability

Referenced pricing versions and resolved BillingTerms required to reproduce historical financial outcomes SHALL remain available according to retention policy.

---

# 36. Tax provenance durability

Where tax affects financial facts, the applicable tax determination provenance SHALL survive recovery and retention requirements.

---

# 37. Usage durability

Accepted UsageRecords SHALL be treated as financially material where they can affect billing.

They SHALL not exist only in an ephemeral queue.

---

# 38. Usage acknowledgment

Where usage ingestion acknowledges acceptance, the corresponding accepted usage fact SHALL already be durably committed or otherwise protected by a platform-approved durable mechanism.

---

# 39. Duplicate usage after recovery

Recovery/replay MAY redeliver usage events.

Stable usage event identity and deduplication SHALL prevent double charging.

---

# 40. Outbox recovery

Unpublished outbox records SHALL survive restoration within the applicable recovery point.

After recovery:

```text id="8whfmm"
pending outbox
      │
      ▼
publisher resumes
      │
      ▼
possible redelivery
      │
      ▼
consumer deduplication
```

---

# 41. Inbox recovery

Inbox/deduplication state is financially relevant.

Loss of inbox history can make previously processed events appear new.

Its recovery SHALL therefore be considered alongside domain state.

---

# 42. Inbox retention

Deduplication retention SHALL be long enough to safely cover:

- event transport retention;
- retry horizons;
- replay horizons;
- disaster recovery scenarios.

---

# 43. Event replay after restore

Events MAY be replayed to restore downstream convergence.

Replay SHALL obey ADR-SUB-0014:

- same historical identity;
- idempotent consumption;
- no authority invention.

---

# 44. Event log is not the sole backup

The event transport SHALL NOT be assumed to contain every piece of state required to reconstruct the subscriptions database.

Database backup remains independently required.

---

# 45. Audit durability

Security and controlled-mutation audit records SHALL be backed up and retained according to applicable policy.

Loss of audit evidence is itself a material recovery concern.

---

# 46. Audit restoration

Audit history SHALL not be silently regenerated from application logs.

---

# 47. Provider mapping durability

`ProviderReference` mappings are essential for safe provider reconciliation.

Their loss can create:

- duplicate provider resources;
- orphan provider resources;
- incorrect resource association.

They SHALL be protected accordingly.

---

# 48. Lost provider mapping

If mapping state is uncertain after recovery:

```text id="fn3k74"
DO NOT:
create replacement provider resource blindly
```

Instead:

```text id="2qmk5p"
search/reconcile deterministically
      │
      ▼
establish identity
      │
      ▼
repair mapping
```

---

# 49. Payment recovery

Subscriptions SHALL never assume:

```text id="7n6s4c"
no local payment outcome
=
payment did not happen
```

after disaster recovery.

---

# 50. Ambiguous payment state

For every financially material ambiguous payment:

```text id="6y84vf"
FinancialObligation
      │
      ▼
Payments reconciliation
      │
      ▼
provider reconciliation if required
      │
      ▼
determine outcome
```

SHALL precede unsafe retry.

---

# 51. Duplicate collection prevention

The recovery process SHALL prioritise prevention of duplicate collection over rapid blind retry.

---

# 52. ERP recovery

ERP may recover to a different point from subscriptions.

Missing accounting projections SHALL be reconstructed/replayed through canonical integration contracts where possible.

Subscriptions SHALL NOT write directly into ERP databases to repair recovery drift.

---

# 53. Accounting does not redefine billing

If ERP lacks a receivable corresponding to a valid FinancialObligation, the recovery action is:

```text id="5a13gd"
re-project/reconcile accounting
```

not:

```text id="hqlimq"
delete the valid billing obligation
```

---

# 54. Backup encryption

Production backups SHALL be encrypted according to platform security requirements.

---

# 55. Backup credentials

Backup credentials SHALL use least privilege and SHALL be separately governed from ordinary application credentials.

---

# 56. Backup access

Access to financial backups SHALL be restricted and auditable.

A backup is effectively a concentrated copy of protected data.

---

# 57. Backup immutability

Where supported and proportionate, production backups SHOULD use controls protecting them against:

- accidental deletion;
- ransomware;
- compromised application credentials;
- unauthorised alteration.

---

# 58. Backup separation

At least one recoverable backup copy SHOULD avoid sharing the same failure domain and credentials as the primary database.

---

# 59. Failure domains

Backup strategy SHALL consider:

```text id="2dpbaf"
application failure

database failure

availability-zone failure

regional failure

operator error

credential compromise

malicious deletion
```

---

# 60. Regional resilience

Where the production service tier requires regional disaster resilience, backups SHALL be recoverable outside the failed region.

---

# 61. Data residency

Cross-region backup placement SHALL respect applicable:

- tenant policy;
- contractual restrictions;
- data residency requirements;
- privacy obligations.

Availability objectives do not automatically override data-governance constraints.

---

# 62. Multi-region does not mean multi-writer

Supporting multiple markets or regions SHALL NOT automatically imply active-active multi-writer billing databases.

---

# 63. Single-writer preference

For financially authoritative aggregates, the architecture SHOULD prefer a clear writer authority unless a genuinely safe multi-writer model is explicitly designed and accepted.

---

# 64. Split-brain prevention

The platform SHALL avoid a recovery configuration in which two independent subscription deployments both believe they are authoritative writers for the same billing scope.

---

# 65. Regional failover

Failover SHALL establish:

```text id="crifk4"
old writer fenced
      │
      ▼
recovery state established
      │
      ▼
new writer promoted
```

before accepting unrestricted financial mutations.

---

# 66. Fencing

The disaster recovery design SHALL provide a mechanism to prevent the previous primary from continuing conflicting writes after failover.

---

# 67. DNS/service routing is insufficient

Changing network routing alone does not establish financial writer authority.

---

# 68. Recovery epoch

The implementation MAY use a recovery/failover epoch, generation, lease, or equivalent mechanism to strengthen writer fencing and detect stale workers.

---

# 69. Scheduled workers

Billing-cycle workers, reconciliation workers and outbox publishers SHALL respect failover/writer authority.

A stale worker in a failed region SHALL not continue producing financial mutations.

---

# 70. Job idempotency

Scheduled financial jobs SHALL be idempotent and protected against duplicate execution after failover.

---

# 71. Billing-cycle failover

If failure occurs while a cycle is being processed:

```text id="k7r1bt"
cycle starts
   │
   ▼
some charges committed
   │
   X
failure
```

the recovered worker SHALL determine committed state before continuing.

It SHALL not blindly restart the entire cycle and duplicate charges.

---

# 72. Durable checkpoints

Long-running financial workflows SHOULD maintain durable checkpoints or equivalent deterministic state.

---

# 73. Invoice finalisation recovery

Invoice finalisation SHALL be idempotent.

If failure occurs after finalisation commit but before acknowledgement, retry SHALL return/observe the existing finalised result rather than create another invoice.

---

# 74. Financial obligation recovery

FinancialObligation creation SHALL similarly be protected from duplication across retries and failover.

---

# 75. Credit and adjustment recovery

Credit/adjustment commands SHALL preserve stable operation identity so recovery cannot apply the same correction twice.

---

# 76. Provider mutation recovery

Provider operations SHALL follow ADR-SUB-0015 unknown-outcome handling.

A timeout or disaster after provider commit SHALL trigger reconciliation rather than blind recreation.

---

# 77. Backup verification

Every backup process SHALL verify:

- backup job completion;
- expected artefacts;
- encryption;
- retention;
- integrity metadata where available.

---

# 78. Restore testing

Baobab SHALL regularly perform restoration tests.

A backup never restored is an unverified recovery hypothesis.

---

# 79. Restore test scope

Restore tests SHOULD validate:

```text id="8xaswo"
database restoration

schema compatibility

application startup

domain integrity

outbox/inbox integrity

audit accessibility

provider mapping availability

reconciliation capability
```

---

# 80. Recovery exercise

Periodic exercises SHOULD simulate failures such as:

- database loss;
- accidental deletion;
- availability-zone loss;
- regional loss;
- partial engine restoration;
- Kill Bill divergence;
- Payments divergence.

---

# 81. Recovery evidence

Recovery exercises SHALL produce evidence such as:

- achieved RPO;
- achieved RTO;
- discrepancies discovered;
- reconciliation duration;
- failed procedures;
- remediation actions.

---

# 82. RPO/RTO validation

Declared RPO and RTO SHALL be periodically validated against actual recovery exercises.

---

# 83. Backup monitoring

Operational monitoring SHALL alert on:

- backup failure;
- backup age exceeding policy;
- missing WAL/archive continuity where required;
- restore-test failure;
- retention failure;
- backup-storage access anomalies.

---

# 84. Backup success is an SLI

Backup and restore reliability SHOULD form part of operational service-level reporting for the financial platform.

---

# 85. Retention

Financial data SHALL have explicit retention rules.

Different categories MAY require different periods.

---

# 86. Retention categories

At minimum, policy SHALL distinguish:

```text id="lzb3y0"
financial transaction history

invoice history

usage history

audit history

event deduplication history

operational logs

provider observations

temporary reconciliation data
```

---

# 87. Retention is not one universal TTL

A single blanket deletion period SHALL NOT be applied to all subscription data.

---

# 88. Legal and contractual retention

Actual retention periods SHALL be determined by applicable:

- legal obligations;
- tax/accounting requirements;
- privacy law;
- contracts;
- market requirements;
- platform policy.

This ADR intentionally does not invent jurisdiction-specific durations.

---

# 89. Privacy and minimisation

Financial durability does not justify indefinite retention of unnecessary personal data.

The platform SHALL separate durable financial evidence from unnecessary personal detail where possible.

---

# 90. Referential preservation

Where personal information must later be removed or minimised, financial records SHOULD retain the references/provenance necessary for lawful historical integrity without retaining unnecessary data.

---

# 91. Hard deletion

Financially used records SHALL NOT ordinarily be hard-deleted merely because they are no longer active.

Lifecycle closure and retention expiration are separate concepts.

---

# 92. Tombstones

Where identity/reference deletion is required, tombstone or anonymised-reference strategies MAY preserve financial referential integrity without retaining prohibited personal information.

---

# 93. Backup expiry

Expired backups SHALL be deleted according to controlled retention policy.

Indefinite forgotten backups create security and privacy risk.

---

# 94. Data destruction

Backup destruction SHALL be governed and auditable where required.

---

# 95. Schema migrations and recovery

Database migrations SHALL be designed with recovery in mind.

A backup restored to an older schema version must have a documented path to a compatible application state.

---

# 96. Migration rollback

Destructive schema migrations SHALL require particular caution.

Application rollback does not automatically restore removed financial data.

---

# 97. Expand-contract preference

Where feasible, financial-schema evolution SHOULD use staged expand/migrate/contract approaches.

Conceptually:

```text id="dkwm54"
Expand schema
     │
     ▼
Deploy compatible application
     │
     ▼
Migrate/backfill
     │
     ▼
Verify
     │
     ▼
Contract obsolete schema
```

---

# 98. Backfill safety

Financial backfills SHALL be:

- deterministic;
- restartable;
- observable;
- idempotent where possible;
- reconciled.

---

# 99. Backup before high-risk migration

High-risk production migrations SHOULD have an appropriate verified recovery point before execution.

---

# 100. Provider backup responsibility

Baobab SHALL explicitly determine who is responsible for backup and recovery of Kill Bill persistence.

It SHALL NOT assume provider state is protected merely because Baobab's subscriptions database is backed up.

---

# 101. Provider restore coordination

Kill Bill restoration SHALL be coordinated with Baobab provider mappings and reconciliation.

Independent restoration can produce:

```text id="gv92n8"
Baobab mapping
points to provider object
that no longer exists
```

or:

```text id="j58sqj"
provider object exists
but mapping was restored earlier
```

Both require reconciliation.

---

# 102. Payment-provider recovery

Subscriptions SHALL not own HyperSwitch/provider backup.

That belongs to the Payments architecture.

However, subscriptions recovery SHALL account for the possibility that Payments and provider state recovered differently.

---

# 103. Control Plane recovery

If Control Plane is unavailable during subscriptions recovery, subscriptions MAY enter restricted/recovery operation.

It SHALL not invent missing upstream subscription/classification authority.

---

# 104. Fail closed on authority uncertainty

Where recovery cannot determine an authoritative classification or billing policy for a new monetary action, the system SHALL block that action.

---

# 105. Existing historical facts remain historical

Authority uncertainty during recovery SHALL not cause already valid historical Charges or invoices to be deleted.

---

# 106. Recovery manifest

A disaster recovery process SHOULD create a recovery manifest recording:

```text id="5pdj1u"
incident/recovery ID

backup identifier

recovery timestamp

database recovery point

application version

schema version

event checkpoint information

known dependency recovery points

reconciliation status

operator/automation identity
```

---

# 107. Recovery manifest purpose

The manifest provides provenance for answering:

> From what state was this billing environment restored?

---

# 108. Recovery audit

Disaster recovery actions SHALL be audited.

---

# 109. Recovery privileges

Restore, failover, fencing, replay and repair privileges SHALL be restricted under ADR-SUB-0016.

---

# 110. Separation of duties

Where practicable, the same identity SHOULD NOT have unrestricted authority to:

```text id="g72ylu"
modify billing data
+
delete backups
+
disable audit
```

---

# 111. Break-glass access

Emergency recovery access SHALL be:

- exceptional;
- strongly authenticated;
- time-bound where possible;
- audited;
- reviewed afterward.

---

# 112. Disaster declaration

The platform SHOULD define criteria for formally entering disaster-recovery mode rather than relying on improvised operator judgement.

---

# 113. Disaster categories

Runbooks SHOULD consider at least:

```text id="b3kkcl"
APPLICATION_FAILURE

DATABASE_FAILURE

ZONE_FAILURE

REGION_FAILURE

DATA_CORRUPTION

SECURITY_COMPROMISE

OPERATOR_ERROR

PROVIDER_FAILURE

CROSS_ENGINE_INCONSISTENCY
```

---

# 114. Corruption differs from outage

A healthy-looking database containing corrupted financial data can be more dangerous than an unavailable database.

Recovery procedures SHALL therefore distinguish:

```text id="vrpz0m"
availability incident
```

from:

```text id="19t2nq"
integrity incident
```

---

# 115. Integrity incident containment

Suspected corruption MAY require:

```text id="v88lca"
stop affected mutations
      │
      ▼
preserve evidence
      │
      ▼
identify safe recovery point
      │
      ▼
restore
      │
      ▼
reconcile
```

---

# 116. No automatic failover on logical corruption

Automatic database failover may reproduce logical corruption.

Therefore corruption detection and recovery require different procedures from infrastructure-node failure.

---

# 117. Recovery completeness

Recovery SHALL be evaluated across four dimensions:

```text id="ojv4xz"
Infrastructure Restored?

Data Restored?

Cross-Engine Converged?

Financial Correctness Verified?
```

Only the final combination represents complete billing recovery.

---

# 118. Recovery states

Conceptually:

```text id="1m4ohk"
DISASTER_DECLARED

RESTORING

RESTORED_UNVERIFIED

RECONCILING

RESTRICTED_OPERATION

VERIFIED

NORMAL
```

Exact implementation MAY differ.

---

# 119. RESTORED_UNVERIFIED

This state is especially important.

It means:

> The service and data have been restored, but financial correctness has not yet been demonstrated.

Financially dangerous workflows MAY remain blocked.

---

# 120. VERIFIED

A recovered environment becomes VERIFIED only after required:

- database integrity checks;
- domain checks;
- event checks;
- provider reconciliation;
- payment reconciliation;
- ERP reconciliation;
- security checks

have completed to the defined recovery standard.

---

# 121. Domain invariants

### INV-DR-01

Committed financial state is durable before success acknowledgement.

### INV-DR-02

Application instances do not hold the sole authoritative copy of financial state.

### INV-DR-03

Replication is not backup.

### INV-DR-04

Backup existence does not prove recoverability.

### INV-DR-05

Production RPO and RTO are explicit and tested.

### INV-DR-06

Restore does not automatically authorise financial processing.

### INV-DR-07

Cross-engine recovery includes reconciliation.

### INV-DR-08

Domain authority survives disaster recovery.

### INV-DR-09

The newest timestamp does not determine architectural authority.

### INV-DR-10

Historical financial facts are not silently recalculated using current policy.

### INV-DR-11

Accepted billable usage is durably protected.

### INV-DR-12

Event replay cannot duplicate financial effects.

### INV-DR-13

Inbox/deduplication state is part of financial recovery design.

### INV-DR-14

Provider mapping loss does not justify blind provider-resource creation.

### INV-DR-15

Unknown payment state is reconciled before retry.

### INV-DR-16

ERP recovery does not redefine billing authority.

### INV-DR-17

Backups are encrypted and access-controlled.

### INV-DR-18

At least one recoverable backup avoids the primary failure domain where required by the service tier.

### INV-DR-19

Regional failover prevents split-brain financial writers.

### INV-DR-20

Scheduled financial workers respect writer/failover authority.

### INV-DR-21

Billing-cycle recovery cannot duplicate Charges.

### INV-DR-22

Invoice finalisation recovery is idempotent.

### INV-DR-23

Credit and adjustment recovery cannot double-apply corrections.

### INV-DR-24

Recovery exercises validate actual recoverability.

### INV-DR-25

Retention is data-category-specific.

### INV-DR-26

Financial retention does not justify unnecessary indefinite PII retention.

### INV-DR-27

Software rollback does not erase financial history.

### INV-DR-28

Provider backup is independently governed.

### INV-DR-29

Recovery authority uncertainty fails closed for new monetary action.

### INV-DR-30

Disaster recovery actions are audited.

### INV-DR-31

Logical corruption is not treated as ordinary availability failure.

### INV-DR-32

RESTORED does not mean VERIFIED.

### INV-DR-33

Financially dangerous workflows remain restricted until required recovery verification completes.

### INV-DR-34

Recovery is complete only when financial correctness has been established to the defined recovery standard.

---

# 122. Recovery matrix

| Failure | Primary mechanism | Additional requirement |
|---|---|---|
| Application instance loss | Restart/reschedule | No financial recovery required if durable state intact |
| Node loss | Failover/reschedule | Verify local dependencies |
| DB primary loss | HA database failover | Confirm writer fencing |
| DB corruption | Restore from safe point | Full reconciliation |
| Accidental deletion | PITR/restore | Determine affected financial facts |
| Event transport loss | Durable outbox | Drain/reconcile after recovery |
| Kill Bill loss | Provider recovery | Provider mapping reconciliation |
| Payments outage | Preserve obligations | Reconcile before retry |
| ERP outage | Queue/replay projection | Accounting reconciliation |
| Availability-zone loss | Infrastructure failover | Verify writer authority |
| Regional loss | DR restoration/failover | Fence old region + reconcile |
| Partial multi-engine restore | Per-engine recovery | Cross-engine reconciliation |
| Security compromise | Isolate + restore trusted state | Credential rotation + audit review |

---

# 123. Recovery priority matrix

| Capability | Typical recovery priority | Safety condition |
|---|---|---|
| Database | Highest | Trusted recovery point |
| Read-only inspection | High | Restored data accessible |
| Audit access | High | Integrity verified |
| Reconciliation | High | Dependencies available |
| Usage ingestion | Policy dependent | Durable deduplication intact |
| Event publication | High | Outbox consistency verified |
| Provider mutation | Restricted initially | Provider reconciliation |
| Invoice finalisation | Restricted initially | Billing state verified |
| Payment handoff | Highest financial caution | Payment reconciliation complete |
| ERP projection | After billing state | Canonical billing facts verified |

Exact production priorities SHALL be formalised in operational runbooks.

---

# 124. Required backup policy

Production policy SHALL define at minimum:

| Policy area | Required definition |
|---|---|
| RPO | Explicit target |
| RTO | Explicit target |
| Backup frequency | Defined |
| PITR | Enabled where required |
| Encryption | Required |
| Retention | Defined by data class |
| Backup location | Failure-domain aware |
| Access | Least privilege |
| Monitoring | Required |
| Restore tests | Scheduled |
| DR exercises | Scheduled |
| Evidence retention | Required |
| Data residency | Enforced |

---

# 125. Required recovery runbooks

At minimum:

1. application failure;
2. PostgreSQL primary failure;
3. database corruption;
4. accidental deletion;
5. failed migration;
6. event transport outage;
7. Kill Bill outage/recovery;
8. Kill Bill database restoration;
9. Payments outage/recovery;
10. ERP outage/recovery;
11. availability-zone failure;
12. regional failure;
13. partial cross-engine restoration;
14. suspected financial corruption;
15. security-compromise recovery.

---

# 126. Required tests

## Persistence

- committed transaction survives restart;
- failed transaction leaves no partial financial state;
- outbox committed atomically;
- inbox survives restart.

## Database

- primary failover;
- backup creation;
- backup encryption;
- PITR;
- restore to isolated environment;
- schema validation after restore.

## Usage

- accepted usage survives failure;
- replay does not duplicate usage;
- rating after restore uses historical pricing version.

## Billing

- interrupted billing cycle resumes safely;
- no duplicate Charge;
- invoice finalisation idempotent;
- no duplicate FinancialObligation;
- credit retry does not duplicate credit.

## Provider

- Kill Bill unavailable during recovery;
- provider restored ahead of Baobab;
- Baobab restored ahead of provider;
- lost provider mapping;
- provider reconciliation.

## Payments

- payment committed but subscriptions restored before outcome;
- ambiguous payment reconciled;
- duplicate collection prevented;
- payment handoff held during RESTORED_UNVERIFIED.

## ERP

- ERP restored behind subscriptions;
- missing accounting projection replayed;
- ERP state cannot rewrite valid billing history.

## Region

- old writer fenced;
- new writer promoted;
- stale worker rejected;
- scheduled billing job does not run twice.

## Security

- unauthorised restore rejected;
- backup access audited;
- break-glass recorded;
- restored secrets rotated where required.

## Recovery verification

- RESTORED_UNVERIFIED prevents dangerous workflows;
- reconciliation completes;
- VERIFIED enables normal processing.

---

# 127. Recommended implementation sequence

```text id="4nxqfc"
1. Data classification
       │
       ▼
2. RPO / RTO definition
       │
       ▼
3. PostgreSQL HA
       │
       ▼
4. Automated backups
       │
       ▼
5. PITR
       │
       ▼
6. Backup encryption / isolation
       │
       ▼
7. Restore automation
       │
       ▼
8. Recovery modes
       │
       ▼
9. Writer fencing
       │
       ▼
10. Workflow idempotency verification
       │
       ▼
11. Cross-engine reconciliation
       │
       ▼
12. Provider recovery
       │
       ▼
13. Payment recovery safeguards
       │
       ▼
14. DR runbooks
       │
       ▼
15. Restore tests
       │
       ▼
16. Regional DR exercises
       │
       ▼
17. Evidence / continuous improvement
```

---

# 128. Full disaster-recovery flow

```text id="njzgac"
                 DISASTER DETECTED
                        │
                        ▼
                 Declare Incident
                        │
                        ▼
                     Contain
                        │
              ┌─────────┴─────────┐
              ▼                   ▼
        Fence Writers       Preserve Evidence
              │
              └─────────┬─────────┘
                        ▼
              Determine Recovery Point
                        │
                        ▼
                  Restore Storage
                        │
                        ▼
                 Validate Database
                        │
                        ▼
                Start Application
                        │
                        ▼
               RESTORED_UNVERIFIED
                        │
                        ▼
               Validate Outbox/Inbox
                        │
                        ▼
                Reconcile with CP
                        │
                        ▼
              Reconcile with Kill Bill
                        │
                        ▼
               Reconcile with Payments
                        │
                        ▼
                  Reconcile ERP
                        │
                        ▼
              Verify Financial Facts
                        │
                        ▼
                     VERIFIED
                        │
                        ▼
             Resume Restricted Work
                        │
                        ▼
              Observe / Reconcile
                        │
                        ▼
                      NORMAL
```

---

# 129. Worked example — database loss

Assume the subscriptions database fails completely at:

```text id="od1p66"
14:05
```

and the chosen recovery point is:

```text id="1o4iz9"
14:03
```

The service SHALL NOT simply restore 14:03 and immediately resume collection.

Instead:

```text id="b13s85"
restore 14:03
     │
     ▼
RESTORED_UNVERIFIED
     │
     ▼
determine events/actions
between 14:03 and failure
     │
     ├── CP changes
     ├── usage accepted
     ├── provider mutations
     ├── payment outcomes
     └── ERP projections
     │
     ▼
reconcile
     │
     ▼
restore canonical financial state
     │
     ▼
VERIFY
     │
     ▼
resume
```

---

# 130. Worked example — payment ambiguity

Before failure:

```text id="6gw7yt"
FinancialObligation
ZAR 4,000
       │
       ▼
PaymentRequest sent
       │
       ▼
provider captured ZAR 4,000
       │
       X
subscriptions state lost before outcome projection
```

After restore, local state may say:

```text id="6b5df0"
payment outcome unknown
```

The system SHALL NOT send another ZAR 4,000 request.

Required flow:

```text id="mxfh6r"
UNKNOWN
   │
   ▼
Payments reconciliation
   │
   ▼
existing payment found
   │
   ▼
project payment result
   │
   ▼
obligation satisfied
```

---

# 131. Worked example — interrupted billing cycle

Suppose:

```text id="sgd0oz"
Cycle C-10

Charge A committed
Charge B committed
Charge C not yet created
```

then the database connection fails.

After recovery, the worker SHALL inspect durable state.

It SHALL continue toward:

```text id="bxj28v"
A exists
B exists
C created exactly once
```

not:

```text id="43nmof"
A duplicate
B duplicate
C created
```

---

# 132. Worked example — Kill Bill restored to a different point

```text id="nxvzi5"
Baobab:
BillingSubscription B
ACTIVE

ProviderReference:
KB-100

Kill Bill restored:
KB-100 missing
```

The engine SHALL classify:

```text id="vyf7yr"
MISSING_PROVIDER_RESOURCE
```

and reconcile according to policy.

It SHALL NOT change the authoritative ProductSubscription merely because the provider lost its projection.

---

# 133. Worked example — Control Plane ahead of subscriptions

```text id="jhp5yn"
Control Plane:
ProductSubscription
revision 45
TERMINATED

Subscriptions restored:
revision 42
ACTIVE
```

Recovery:

```text id="rnvnzn"
fetch authoritative CP state
        │
        ▼
revision 45
TERMINATED
        │
        ▼
converge BillingSubscriptionProjection
        │
        ▼
reconcile provider
```

The restored revision 42 does not become authoritative merely because it came from a valid backup.

---

# 134. Worked example — regional failover

```text id="zzdxfp"
Region A
PRIMARY
   │
   X regional failure
   │
   ▼
Fence Region A
   │
   ▼
Restore/promote Region B
   │
   ▼
RESTORED_UNVERIFIED
   │
   ▼
Reconcile
   │
   ▼
VERIFIED
   │
   ▼
Enable financial writers
```

If Region A later returns, it SHALL NOT resume writer activity until explicitly reintegrated.

---

# 135. Recovery acceptance checklist

Before returning to unrestricted NORMAL operation, operators/automation SHALL establish as applicable:

- [ ] authoritative database restored;
- [ ] expected recovery point documented;
- [ ] schema compatible;
- [ ] writer fencing confirmed;
- [ ] outbox integrity checked;
- [ ] inbox/deduplication integrity checked;
- [ ] audit accessible;
- [ ] Control Plane reconciliation complete;
- [ ] subscription projections converged;
- [ ] usage integrity checked;
- [ ] billing-cycle integrity checked;
- [ ] invoices reconciled;
- [ ] FinancialObligations reconciled;
- [ ] Kill Bill provider mappings reconciled;
- [ ] Payments outcomes reconciled;
- [ ] duplicate collection risk cleared;
- [ ] ERP projections reconciled;
- [ ] INTERNAL monetary-treatment invariants checked;
- [ ] cross-tenant isolation checks passed;
- [ ] security credentials reviewed/rotated where required;
- [ ] critical reconciliation findings resolved;
- [ ] recovery manifest completed;
- [ ] recovery audit recorded;
- [ ] observability healthy;
- [ ] financial correctness VERIFIED.

---

# 136. Final Decision

Baobab SHALL treat subscription billing disaster recovery as **restoration of financially correct distributed state**, not merely restoration of servers and databases.

The principal durability rule is:

> **A financially material mutation is not successful until its authoritative local state is durably committed.**

The principal backup rule is:

> **Replication is not backup, and a backup that has never been successfully restored is not demonstrated recoverability.**

The principal recovery rule is:

> **Restore does not equal resume: restored subscription state remains unverified until the required integrity and cross-engine reconciliation checks complete.**

The principal authority rule is:

> **Disaster recovery does not alter domain ownership; Control Plane, Subscriptions, Payments, ERP and providers retain their established authorities regardless of which system recovered most recently.**

The principal history rule is:

> **Historical financial facts must remain reproducible from the policy, pricing, usage, tax and classification provenance applicable when those facts were created; recovery must not silently recalculate history using current rules.**

The principal payment-safety rule is:

> **Unknown payment outcome after failure must be reconciled before retry, because preventing duplicate collection takes precedence over blind recovery speed.**

The principal provider rule is:

> **Kill Bill is restored and reconciled as a provider projection; loss or divergence of provider state does not redefine canonical Baobab subscription authority.**

The principal failover rule is:

> **Only one authorised writer may control a given financially authoritative billing scope; regional failover requires fencing against split-brain mutation.**

The principal retention rule is:

> **Financial durability and privacy must coexist: retain the evidence required for financial integrity and lawful obligations without retaining unnecessary personal data indefinitely.**

The principal operational rule is:

> **RPO and RTO are engineering commitments that must be demonstrated through recovery exercises, not numbers written in documentation and never tested.**

The principal verification rule is:

> **RESTORED is an infrastructure condition; VERIFIED is a financial condition.**

And the final resilience principle for the Baobab subscription engine is:

> **A disaster is not over when the service comes back online. It is over when Baobab can demonstrate that its authoritative billing facts are durable, its distributed projections have converged, unsafe monetary duplication has been excluded, and financial correctness has been restored.**