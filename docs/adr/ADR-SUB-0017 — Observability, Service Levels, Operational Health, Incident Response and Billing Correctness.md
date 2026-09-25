# ADR-SUB-0017 — Observability, Service Levels, Operational Health, Incident Response and Billing Correctness

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Reliability / Observability / Operations  
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
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts

---

# 1. Context

`baobab-subscriptions` is a financial-domain engine.

Its failure modes therefore extend far beyond:

```text
process down
```

A service can respond successfully to health checks while:

- subscription projections are stale;
- usage events are accumulating;
- rating is delayed;
- billing cycles are not closing;
- invoices are not finalising;
- payment obligations are not reaching Payments;
- Kill Bill has drifted from canonical state;
- events remain unpublished in the outbox;
- reconciliation is failing;
- tax determination is blocked;
- COMMERCIAL subscriptions are incorrectly treated as ready;
- INTERNAL subscriptions are accidentally approaching monetary execution.

Therefore:

```text
HTTP 200
!=
billing health
```

and:

```text
billing health
!=
billing correctness
```

The engine requires observability that describes both technical availability and financial-domain correctness.

---

# 2. Decision

`baobab-subscriptions` SHALL implement a layered operational model covering:

1. process health;
2. dependency health;
3. workload readiness;
4. asynchronous pipeline health;
5. provider health;
6. billing-domain health;
7. financial reconciliation health;
8. security/control health;
9. service-level indicators and objectives;
10. incident detection and response;
11. controlled degradation;
12. recovery verification.

The engine SHALL distinguish:

```text
AVAILABLE
```

from:

```text
READY
```

from:

```text
CONVERGED
```

from:

```text
FINANCIALLY CORRECT
```

---

# 3. Operational health model

Conceptually:

```text
                    Billing Correctness
                           ▲
                           │
                     Reconciliation
                           ▲
                           │
                    Domain Pipelines
                           ▲
                           │
                 Dependencies / Providers
                           ▲
                           │
                       Readiness
                           ▲
                           │
                       Liveness
```

A lower layer being healthy does not prove higher-layer correctness.

---

# 4. Liveness

Liveness answers:

> Is this process alive and capable of continuing execution?

It SHALL be intentionally narrow.

Examples include:

- runtime alive;
- application event loop/thread system functioning;
- no unrecoverable internal deadlock.

---

# 5. Liveness SHALL NOT depend on every external service

The liveness endpoint SHALL NOT ordinarily fail merely because:

- Kill Bill is unavailable;
- Payments is unavailable;
- ERP is unavailable;
- Control Plane is temporarily unavailable.

Otherwise an external outage could cause unnecessary restart loops.

---

# 6. Readiness

Readiness answers:

> Can this instance safely accept the workload for which it is registered?

Readiness MAY depend on mandatory local infrastructure such as:

- subscriptions PostgreSQL;
- required configuration;
- schema/migration compatibility;
- mandatory credentials;
- internal startup state.

---

# 7. Readiness is workload-specific

A single binary readiness result MAY be insufficient.

The implementation SHOULD distinguish capabilities such as:

```text
API_READINESS

USAGE_INGESTION_READINESS

BILLING_PROCESSING_READINESS

EVENT_PUBLICATION_READINESS

PROVIDER_INTEGRATION_READINESS

RECONCILIATION_READINESS
```

where operationally useful.

---

# 8. Dependency health

External dependency health SHALL be independently observable.

Examples:

```text
Control Plane

PostgreSQL

event transport

baobab-payments

ERP integration

Kill Bill
```

One failing dependency SHALL not automatically imply every subscription capability is unavailable.

---

# 9. Provider health

ADR-SUB-0015 establishes Kill Bill as a provider behind `BillingProvider`.

Therefore Kill Bill health SHALL be represented as:

```text
provider health
```

not:

```text
Baobab canonical truth health
```

---

# 10. Provider outage

If Kill Bill becomes unavailable:

```text
BillingProvider
     │
     X
Kill Bill
```

Baobab SHALL preserve already-authoritative local billing state.

It SHALL NOT:

- erase BillingSubscriptionProjection;
- alter classification;
- revoke ProductSubscription;
- invent provider success;
- silently mark failed provisioning as complete.

---

# 11. Degraded mode

The engine SHALL support explicit degraded conditions.

Conceptually:

```text
HEALTHY

DEGRADED

BLOCKED

RECOVERING
```

These operational conditions are distinct from domain aggregate lifecycle states.

---

# 12. Domain health

Domain health asks whether billing workflows are progressing correctly.

Examples:

- projection convergence;
- usage processing;
- rating progress;
- cycle finalisation;
- invoice finalisation;
- obligation handoff;
- provider reconciliation.

---

# 13. Projection lag

Subscriptions SHALL measure lag between authoritative Control Plane state and the local BillingSubscriptionProjection where feasible.

Conceptually:

```text
CP revision/time
      │
      ▼
event transport
      │
      ▼
subscriptions projection
```

Excessive lag SHALL be observable.

---

# 14. Usage ingestion health

At minimum, usage observability SHOULD include:

- accepted events;
- rejected events;
- duplicates;
- conflicting duplicates;
- processing latency;
- future-dated events;
- late usage;
- quarantined usage;
- aggregation backlog.

---

# 15. Usage loss is a financial incident

Confirmed loss of valid billable usage SHALL be treated as a financial-integrity incident, not merely a telemetry issue.

---

# 16. Rating health

Rating observability SHOULD include:

- pending usage awaiting rating;
- rating throughput;
- rating failures;
- missing pricing;
- pricing-version resolution failures;
- rounding failures;
- blocked ratings.

---

# 17. INTERNAL rating observability

INTERNAL subscriptions remain metered.

Where shadow valuation/rating is enabled, its health SHOULD be independently visible without implying collectible monetary obligation.

---

# 18. Billing-cycle health

The system SHOULD observe:

```text
cycles due

cycles opened

cycles closed

cycles delayed

cycles blocked

cycles failed
```

---

# 19. Cycle delay

A billing cycle that should have completed but remains open beyond policy threshold SHALL produce an operational signal.

---

# 20. Invoice health

InvoiceProjection observability SHOULD include:

- drafts;
- finalised invoices;
- issued invoices;
- blocked invoices;
- tax-blocked invoices;
- unresolved invoice discrepancies.

---

# 21. Financial obligation health

At minimum:

```text
open obligations

payment-pending obligations

partially satisfied obligations

overdue obligations

delinquent obligations

satisfied obligations
```

SHOULD be measurable.

---

# 22. Monetary totals

Operational financial metrics MAY expose aggregate monetary totals where useful.

They SHALL be:

- currency-specific;
- access-controlled;
- non-PII;
- not misleadingly aggregated across currencies.

For example:

```text
outstanding_amount{currency="ZAR"}
```

is meaningful.

A single metric summing ZAR, USD and UGX is not.

---

# 23. No high-cardinality identifiers in metrics

Metrics SHALL NOT ordinarily use:

- tenant ID;
- subscription ID;
- invoice ID;
- customer ID;
- payment ID;
- tax registration;

as unbounded metric labels.

---

# 24. Logs

Structured logs SHALL support diagnosis without becoming a financial database.

Recommended contextual fields MAY include:

```text
service

environment

operation

tenant context where safe

aggregate type

correlation_id

trace_id

event_type

result

error_code
```

---

# 25. Log redaction

Logs SHALL NOT expose:

- payment credentials;
- access tokens;
- secrets;
- raw tax identifiers unnecessarily;
- provider credentials;
- sensitive personal information.

---

# 26. Audit is not logging

ADR-SUB-0016 remains authoritative:

```text
Operational Log
!=
Security Audit
!=
Financial Domain History
```

Logs MAY expire according to operational retention without destroying required audit or financial evidence.

---

# 27. Distributed tracing

Tracing SHOULD cover cross-engine flows such as:

```text
Control Plane
      │
      ▼
Subscriptions
      │
      ├────► Payments
      │
      ├────► ERP
      │
      └────► Kill Bill
```

---

# 28. Trace context

Technical trace context SHOULD be propagated independently of domain identity.

A trace identifier SHALL NOT become:

- tenant authority;
- idempotency authority;
- financial identity.

---

# 29. Correlation

Domain `correlation_id` SHOULD allow operators to follow a billing workflow across:

```text
ProductSubscription
       │
       ▼
BillingSubscriptionProjection
       │
       ▼
InvoiceProjection
       │
       ▼
FinancialObligation
       │
       ▼
Payment
       │
       ▼
ERP
```

---

# 30. Correlation versus tracing

These concepts SHALL remain distinct:

```text
trace_id
=
technical execution trace

correlation_id
=
business workflow correlation
```

They MAY be associated but SHALL not be conflated.

---

# 31. Service Level Indicators

Subscriptions SHALL define measurable Service Level Indicators.

At minimum, candidates SHALL cover:

- API availability;
- API latency;
- event-processing latency;
- projection convergence lag;
- usage ingestion success;
- billing-cycle completion;
- invoice finalisation;
- payment-handoff success;
- reconciliation backlog.

---

# 32. SLI: API availability

Conceptually:

```text
successful valid requests
─────────────────────────
eligible requests
```

Health probes and intentionally rejected invalid requests SHOULD be treated appropriately rather than distorting availability.

---

# 33. SLI: API latency

Latency SHOULD be measured by endpoint/workload class rather than one meaningless global number.

For example:

```text
read API

controlled mutation API

usage ingestion

administrative reconciliation
```

have different performance characteristics.

---

# 34. SLI: projection convergence

A key billing SLI is:

> How long does it take an authoritative Control Plane change to be represented correctly in subscriptions?

---

# 35. SLI: event publication

Measure:

```text
domain commit
      │
      ▼
outbox
      │
      ▼
successful publication
```

Excessive outbox age indicates degraded distributed operation.

---

# 36. SLI: usage processing

Measure the latency from accepted usage to:

```text
accepted usage
     │
     ▼
aggregation
     │
     ▼
rating eligibility
```

according to product policy.

---

# 37. SLI: billing completion

Measure whether billing cycles complete within their expected processing window.

---

# 38. SLI: payment handoff

Measure FinancialObligations requiring collection that are successfully handed to `baobab-payments` within the expected operational window.

---

# 39. SLI: reconciliation

Measure:

- reconciliation backlog;
- oldest unresolved discrepancy;
- percentage converged;
- repair latency.

---

# 40. Service Level Objectives

Concrete SLO thresholds SHALL be defined from production requirements and observed capacity rather than invented in this ADR.

This ADR therefore mandates the SLO categories but does not hard-code unsupported numerical targets.

---

# 41. No false precision

Before production evidence exists, Baobab SHALL NOT claim arbitrary objectives such as:

```text
99.99999%
```

without engineering and operational justification.

---

# 42. SLO classes

Different workflows MAY have different SLOs.

For example:

```text
interactive API
```

and:

```text
end-of-cycle reconciliation
```

need not share the same latency objective.

---

# 43. Error budgets

Where SLOs are defined, Baobab SHOULD use error-budget concepts to balance reliability and change velocity.

---

# 44. Financial correctness is not an ordinary latency SLO

A system can meet API latency targets while calculating incorrect invoices.

Therefore financial correctness SHALL have independent indicators and reconciliation controls.

---

# 45. Billing correctness indicators

At minimum, Baobab SHOULD detect:

```text
duplicate Charge

duplicate FinancialObligation

missing Charge

missing invoice

incorrect classification treatment

INTERNAL monetary execution

currency mismatch

provider drift

payment mismatch

unreconciled invoice total

usage/rating discrepancy
```

---

# 46. Correctness severity

Some anomalies require immediate escalation even if system availability remains high.

For example:

```text
INTERNAL subscription charged
```

is potentially more severe than temporary API latency degradation.

---

# 47. Error taxonomy

Errors SHALL be categorised sufficiently to distinguish:

```text
VALIDATION

AUTHENTICATION

AUTHORIZATION

CONCURRENCY

CONFIGURATION

DEPENDENCY

PROVIDER

TRANSIENT

FINANCIAL_INTEGRITY

SECURITY

UNKNOWN
```

or equivalent canonical classes.

---

# 48. Stable error codes

Operationally meaningful failure modes SHOULD expose stable machine-readable error codes.

Human-readable error text alone is insufficient for alerting and automation.

---

# 49. Blocker observability

Canonical blockers established in prior ADRs SHALL be observable.

Examples include:

```text
PRICING_MISSING

PAYER_MISSING

TAX_CONTEXT_INCOMPLETE

PROVIDER_UNAVAILABLE

FINANCIAL_RESPONSIBILITY_AMBIGUOUS
```

---

# 50. PENDING_CONFIGURATION is not generic failure

A subscription in:

```text
PENDING_CONFIGURATION
```

with a precise known blocker is operationally different from an unexpected system error.

Monitoring SHALL preserve that distinction.

---

# 51. Readiness reporting

A billing projection MAY report readiness dimensions such as:

```text
configuration_ready

billing_ready

provider_ready

payment_ready

reconciliation_healthy
```

where appropriate.

A single Boolean SHOULD NOT hide meaningful blockers.

---

# 52. COMMERCIAL readiness

A COMMERCIAL subscription SHALL NOT be reported billing-ready merely because the application process is healthy.

Required commercial configuration must also be satisfied.

---

# 53. INTERNAL readiness

INTERNAL subscriptions SHALL NOT be reported unhealthy solely because:

```text
payment provider = NOT_REQUIRED
```

or:

```text
payment execution = PROHIBITED
```

Those may be correct policy outcomes.

---

# 54. Provider readiness

Provider readiness SHALL be interpreted according to policy.

For example:

```text
INTERNAL
provider = NOT_REQUIRED
```

may be healthy.

For a COMMERCIAL subscription requiring provider representation:

```text
provider unavailable
```

may block provider/billing readiness.

---

# 55. Dependency isolation

The engine SHOULD degrade narrowly.

Example:

```text
Kill Bill outage
```

should not necessarily stop:

- read APIs;
- usage acceptance;
- audit access;
- local reconciliation inspection.

---

# 56. Controlled degradation

Each capability SHALL define whether dependency failure causes:

```text
CONTINUE

QUEUE

RETRY

BLOCK

FAIL_CLOSED
```

---

# 57. Financial fail-closed rule

Where uncertainty could create an incorrect monetary fact, the system SHALL prefer blocking over guessing.

---

# 58. Availability versus financial safety

Baobab SHALL NOT increase nominal availability by manufacturing:

- zero prices;
- zero tax;
- successful provider state;
- payment success;
- fake invoice completion.

---

# 59. Backlog observability

Asynchronous backlogs SHALL expose at least:

```text
queue depth

oldest item age

processing rate

failure rate
```

where applicable.

---

# 60. Outbox health

ADR-SUB-0014 outbox monitoring SHALL include:

- unpublished count;
- oldest unpublished age;
- retry count;
- permanently failed/quarantined count.

---

# 61. Inbox health

Consumer monitoring SHALL include:

- receive rate;
- processing rate;
- duplicate rate;
- failure rate;
- processing lag;
- quarantine count.

---

# 62. Reconciliation health

Reconciliation SHALL expose:

```text
resources scanned

resources converged

drift detected

auto-repaired

manual review required

failed reconciliation

oldest unresolved drift
```

---

# 63. Reconciliation debt

Unresolved reconciliation findings constitute operational debt.

The platform SHALL not allow such debt to accumulate indefinitely without visibility.

---

# 64. Financial reconciliation severity

Findings SHOULD be classified by impact.

Conceptually:

```text
INFO

WARNING

MATERIAL

CRITICAL
```

Exact severity vocabulary MAY be standardized platform-wide.

---

# 65. Critical examples

Potential critical conditions include:

- duplicate collection;
- INTERNAL payment execution;
- cross-tenant financial mutation;
- unreconciled monetary divergence;
- missing financial history;
- provider resource mapped to wrong tenant.

---

# 66. Alert design

Alerts SHOULD be actionable.

An alert SHOULD identify:

- condition;
- severity;
- affected capability;
- environment;
- approximate scope;
- runbook reference.

---

# 67. Avoid alerting on every individual failure

Expected transient failures SHOULD generally aggregate into meaningful signals.

Otherwise alert fatigue will obscure material financial incidents.

---

# 68. Security alerts

Security-sensitive alerts SHOULD include:

- repeated authorization failure;
- cross-tenant access attempt;
- workload identity failure;
- forbidden INTERNAL payment attempt;
- provider callback validation failure;
- unauthorised controlled mutation;
- suspicious reconciliation repair.

---

# 69. Incident classification

Incidents SHALL distinguish at least:

```text
Availability Incident

Performance Incident

Financial Integrity Incident

Security Incident

Data Integrity Incident

Provider Incident

Cross-Engine Consistency Incident
```

One incident MAY belong to several classes.

---

# 70. Financial integrity incident

A Financial Integrity Incident occurs when there is credible risk that canonical financial facts are:

- missing;
- duplicated;
- incorrectly calculated;
- incorrectly attributed;
- incorrectly collected;
- irreconcilable.

---

# 71. Incident response priority

Financial and security correctness MAY require higher operational priority than ordinary availability degradation.

---

# 72. Incident containment

Possible containment actions MAY include:

```text
pause invoice finalisation

pause payment handoff

disable affected provider path

quarantine affected events

block specific tenant billing workflow

switch affected capability to manual review
```

Containment SHALL be scoped as narrowly as safely possible.

---

# 73. No global shutdown by default

A single tenant-specific financial problem SHALL not automatically require shutting down billing for every tenant.

Isolation boundaries SHOULD permit targeted containment.

---

# 74. Tenant-scoped containment

Where technically safe, incident controls SHOULD support:

```text
tenant

billing account

subscription

provider

market

workflow
```

scopes.

---

# 75. Kill switch

High-risk monetary workflows SHOULD support an authorised operational kill switch or equivalent safety mechanism.

Examples:

```text
pause payment handoff

pause invoice finalisation

pause provider mutation
```

---

# 76. Kill switch governance

A kill switch SHALL be:

- authenticated;
- authorised;
- audited;
- visible;
- reversible through controlled mutation.

It SHALL not be an undocumented environment hack.

---

# 77. Kill switch does not rewrite state

Pausing a workflow SHALL not change existing canonical financial facts.

It changes processing permission, not history.

---

# 78. Recovery

Incident recovery SHALL not end when:

```text
service starts responding
```

Recovery requires validating affected domain state.

---

# 79. Recovery sequence

Conceptually:

```text
Incident
   │
   ▼
Contain
   │
   ▼
Repair infrastructure/code/config
   │
   ▼
Restore processing
   │
   ▼
Replay/retry where safe
   │
   ▼
Reconcile
   │
   ▼
Verify financial correctness
   │
   ▼
Close incident
```

---

# 80. Reconciliation before incident closure

Financial incidents SHALL NOT be considered fully resolved until affected canonical and downstream states have been reconciled or explicitly dispositioned.

---

# 81. Provider recovery

After Kill Bill recovery:

```text
provider healthy
```

does not prove:

```text
all provider projections correct
```

Provider reconciliation SHALL follow material outages where drift is possible.

---

# 82. Payment recovery

After Payments recovery, outstanding FinancialObligations SHALL be reconciled before blind replay to prevent duplicate collection.

---

# 83. Event transport recovery

After messaging recovery, outbox/inbox backlog SHALL be observed until normal convergence is restored.

---

# 84. Capacity planning

The engine SHALL measure sufficient workload characteristics to support capacity planning.

At minimum:

- subscriptions;
- billing accounts;
- usage events;
- billing cycles;
- charges;
- invoices;
- obligations;
- outbox throughput;
- reconciliation workload.

---

# 85. Capacity dimensions

Capacity SHALL consider both:

```text
steady-state volume
```

and:

```text
billing-boundary burst
```

because billing work may cluster around cycle boundaries.

---

# 86. Multi-tenant noisy-neighbour risk

One tenant generating exceptional usage or reconciliation load SHALL not be allowed to indefinitely starve other tenants.

The implementation SHOULD support:

- bounded concurrency;
- tenant-aware quotas where appropriate;
- queue fairness;
- backpressure.

---

# 87. Backpressure

When downstream processing cannot keep pace, Baobab SHALL prefer controlled backlog over uncontrolled resource exhaustion.

---

# 88. Database observability

PostgreSQL observability SHOULD cover:

- connection saturation;
- transaction latency;
- lock contention;
- deadlocks;
- storage growth;
- query latency;
- replication/recovery health where applicable.

---

# 89. Migration health

Application readiness SHALL detect incompatible or incomplete required schema migrations.

An instance SHALL NOT silently serve financial mutations against an incompatible schema.

---

# 90. Provider latency

Kill Bill latency SHALL be measured independently of Baobab API latency.

This allows operators to distinguish:

```text
Baobab slow
```

from:

```text
provider slow
```

---

# 91. Payments latency

Payment-handoff latency SHALL similarly distinguish:

```text
subscriptions processing
```

from:

```text
payments/provider processing
```

---

# 92. ERP lag

Where ERP receives billing facts asynchronously, projection lag SHOULD be measurable.

ERP lag does not alter subscription billing truth, but prolonged lag may create accounting operational risk.

---

# 93. Dashboards

Operational dashboards SHOULD separate:

## Platform health

- liveness;
- readiness;
- dependency status.

## Billing pipeline

- projection;
- usage;
- rating;
- cycles;
- invoices;
- obligations.

## Integration health

- events;
- Kill Bill;
- Payments;
- ERP.

## Financial correctness

- reconciliation;
- duplicates;
- mismatches;
- blocked workflows.

---

# 94. Tenant-aware diagnosis

Operators SHALL be able to investigate a specific tenant or aggregate using controlled diagnostic tooling.

This does not justify exposing tenant identifiers as unbounded public metrics labels.

---

# 95. Diagnostic access

Financial diagnostic tooling SHALL obey ADR-SUB-0016 least privilege and audit requirements.

---

# 96. Runbooks

Material alerts SHALL have runbooks.

At minimum, runbooks SHOULD exist for:

- database unavailable;
- event transport unavailable;
- Kill Bill unavailable;
- Payments unavailable;
- outbox backlog;
- invoice finalisation failure;
- reconciliation drift;
- duplicate collection risk;
- INTERNAL payment violation;
- cross-tenant integrity violation.

---

# 97. Runbook safety

Runbooks SHALL identify actions that operators must **not** take.

Examples:

```text
do not delete duplicate-looking financial records manually

do not modify provider DB directly

do not replay payment requests blindly

do not change classification to bypass billing blockers

do not patch invoice totals directly
```

---

# 98. Manual operational intervention

Manual interventions SHALL use controlled commands/tools rather than routine direct database manipulation.

---

# 99. Operational audit

Material interventions SHALL record:

- actor;
- workload;
- reason;
- target;
- prior state;
- requested action;
- resulting state;
- timestamp;
- correlation/reference.

---

# 100. Synthetic checks

Baobab MAY use synthetic checks for critical paths.

However, synthetic transactions SHALL be clearly isolated from real billing and SHALL never accidentally generate collectible customer obligations.

---

# 101. Production test identities

Synthetic monitoring SHALL use designated test identities/tenants where required.

It SHALL not piggyback on real customer subscriptions.

---

# 102. Environment separation

Metrics, logs, alerts and operational actions SHALL clearly identify environment.

A staging incident SHALL not be mistaken for production financial state.

---

# 103. No production secrets in observability configuration

Observability tooling SHALL use appropriate secret management and workload identity.

Credentials SHALL not be embedded in dashboards or repository configuration.

---

# 104. Data retention

Operational telemetry retention SHALL balance:

- diagnostic requirements;
- financial investigation needs;
- cost;
- privacy;
- security.

Audit/financial retention requirements SHALL be handled separately where they exceed telemetry retention.

---

# 105. Time synchronisation

Reliable timestamps are essential for:

- billing periods;
- event ordering;
- audit;
- incident reconstruction;
- latency measurement.

Production infrastructure SHALL maintain trustworthy clock synchronisation.

---

# 106. Timezone presentation

Operational timestamps SHOULD be stored/transmitted in an unambiguous canonical form.

Human dashboards MAY render local time, but SHALL preserve the underlying instant and timezone semantics.

---

# 107. Error-budget burn

Where formal SLOs exist, Baobab SHOULD support burn-rate alerting rather than relying solely on static instantaneous thresholds.

---

# 108. Release observability

Deployments SHOULD be correlatable with changes in:

- error rate;
- latency;
- backlog;
- reconciliation drift;
- provider failure;
- financial anomalies.

---

# 109. Deployment marker

Operational telemetry SHOULD record deployment/version markers so incidents can be correlated with software releases.

---

# 110. Version observability

Running instances SHOULD expose sufficient build metadata to identify:

```text
application version

commit/release identifier

schema compatibility

provider adapter version/config
```

without leaking secrets.

---

# 111. Rollback

A software rollback SHALL NOT imply financial-state rollback.

Database and financial-domain state require explicit compatibility and reconciliation consideration.

---

# 112. Rollback safety

Before rollback, operators SHALL consider:

- schema compatibility;
- newly emitted event versions;
- financial facts already created;
- provider mutations already executed;
- payment requests already issued.

---

# 113. No destructive financial rollback

A deployment rollback SHALL never simply erase valid Charges, invoices, obligations or payment history produced before rollback.

---

# 114. Domain invariants

### INV-OPS-01

Liveness does not imply billing correctness.

### INV-OPS-02

Readiness does not imply financial convergence.

### INV-OPS-03

Provider health is distinct from canonical billing health.

### INV-OPS-04

A dependency outage does not erase authoritative local state.

### INV-OPS-05

Billing health includes asynchronous pipeline health.

### INV-OPS-06

Valid billable usage loss is a financial-integrity incident.

### INV-OPS-07

INTERNAL payment execution is an alert-worthy financial-integrity violation.

### INV-OPS-08

Metrics do not aggregate incompatible currencies into a misleading monetary total.

### INV-OPS-09

Unbounded tenant/customer identifiers are not ordinary metric labels.

### INV-OPS-10

Operational logs are not security audit records.

### INV-OPS-11

Trace identity is not domain authority.

### INV-OPS-12

SLOs are measurable and workload-specific.

### INV-OPS-13

Financial correctness is measured independently of API availability.

### INV-OPS-14

Known configuration blockers are distinguishable from unexpected system failures.

### INV-OPS-15

INTERNAL provider/payment `NOT_REQUIRED` is not an unhealthy state.

### INV-OPS-16

Financial uncertainty fails closed rather than inventing monetary facts.

### INV-OPS-17

Reconciliation debt is observable.

### INV-OPS-18

Financial incidents require domain reconciliation before closure.

### INV-OPS-19

Provider recovery does not prove provider-state convergence.

### INV-OPS-20

Payment recovery does not justify blind collection replay.

### INV-OPS-21

Incident containment is scoped as narrowly as safely possible.

### INV-OPS-22

Operational kill switches are controlled and audited.

### INV-OPS-23

Kill switches pause processing; they do not rewrite financial history.

### INV-OPS-24

Manual financial repair uses controlled mutation.

### INV-OPS-25

Rollback of software does not roll back financial history.

### INV-OPS-26

Production synthetic checks cannot create real collectible obligations.

### INV-OPS-27

Cross-tenant financial anomalies receive elevated operational treatment.

### INV-OPS-28

Financial correctness after disaster recovery is established by reconciliation, not merely service startup.

---

# 115. Failure and observability matrix

| Condition | Service state | Required operational response |
|---|---|---|
| Application process dead | Unavailable | Restart/failover |
| PostgreSQL unavailable | Not ready for authoritative mutation | Restore DB connectivity |
| Kill Bill unavailable | Degraded | Queue/retry/block provider-required work |
| Payments unavailable | Degraded | Preserve obligations; retry handoff safely |
| ERP unavailable | Degraded | Preserve canonical billing facts; queue projection |
| Event transport unavailable | Degraded | Accumulate durable outbox |
| Pricing missing | Configuration blocked | Precise blocker; no fake zero price |
| Tax unresolved | Billing blocked | Resolve/review; no fake zero tax |
| Outbox backlog growing | Degraded | Investigate transport/publisher |
| Usage backlog growing | Degraded | Scale/investigate processing |
| INTERNAL payment request | Financial integrity violation | Block, alert, investigate |
| Duplicate collection suspected | Critical financial incident | Contain collection; reconcile |
| Provider drift | Reconciliation required | Repair if safe or manual review |
| Cross-tenant mutation | Critical security/integrity incident | Contain and investigate |
| Service recovered after outage | Recovering | Reconcile before declaring full recovery |

---

# 116. Alternatives considered

## 116.1 Use only `/health`

**Rejected.**

Process availability cannot establish financial correctness.

## 116.2 Make every dependency part of liveness

**Rejected.**

External outages would create destructive restart loops.

## 116.3 Treat Kill Bill health as subscription-engine health

**Rejected.**

Kill Bill is a provider, not canonical authority.

## 116.4 Alert on every failed payment

**Rejected.**

Expected business failures would create alert fatigue.

## 116.5 Use logs as audit history

**Rejected.**

Operational logs have different security, retention and semantic requirements.

## 116.6 Put tenant IDs on every metric

**Rejected.**

Creates cardinality, privacy and operational problems.

## 116.7 Define arbitrary numerical SLOs before production evidence

**Rejected.**

Creates false precision.

## 116.8 Declare incident recovery when HTTP health returns

**Rejected.**

Distributed financial state may remain inconsistent.

## 116.9 Automatically continue billing under uncertain financial state

**Rejected.**

Availability must not override monetary correctness.

## 116.10 Roll back financial records with an application rollback

**Rejected.**

Financial history is append-oriented and requires explicit corrections.

---

# 117. Consequences

## Positive

- Operators can distinguish infrastructure failure from billing failure.
- Provider outages become diagnosable without confusing provider state with canonical authority.
- Financial correctness becomes observable.
- Reconciliation becomes an operational first-class capability.
- Incident response becomes safer.
- Payment and INTERNAL-subscription violations can be detected quickly.
- SLOs become meaningful to actual billing workloads.
- Deployment regressions become easier to identify.
- Recovery becomes evidence-based.

## Negative

- Considerably more instrumentation is required.
- Dashboards and alerts require ongoing maintenance.
- Domain-specific SLOs are more complex than generic uptime.
- Reconciliation metrics require additional storage and processing.
- Operational teams require billing-domain knowledge.
- Runbooks must evolve with the architecture.

These costs are accepted because a billing engine that is merely “up” but financially incorrect is not operationally healthy.

---

# 118. Implementation requirements

A conforming implementation SHALL provide:

1. liveness endpoint;
2. readiness endpoint;
3. dependency health reporting;
4. provider health reporting;
5. domain pipeline metrics;
6. structured logs;
7. secret/PII redaction;
8. distributed tracing;
9. business correlation;
10. outbox/inbox metrics;
11. usage/rating metrics;
12. billing-cycle metrics;
13. invoice metrics;
14. obligation/delinquency metrics;
15. reconciliation metrics;
16. stable error codes;
17. blocker reporting;
18. operational degradation states;
19. SLI instrumentation;
20. configurable SLOs;
21. actionable alerts;
22. incident classification;
23. controlled kill switches for high-risk financial workflows;
24. operational audit;
25. runbooks;
26. deployment/version markers;
27. recovery reconciliation;
28. capacity telemetry.

---

# 119. Required tests

At minimum:

## Health

- process healthy;
- database unavailable;
- Kill Bill unavailable;
- Payments unavailable;
- event transport unavailable;
- readiness versus liveness distinction.

## Metrics

- usage accepted;
- usage rejected;
- rating failure;
- cycle delayed;
- invoice blocked;
- obligation overdue;
- outbox backlog;
- reconciliation drift.

## Security

- secrets absent from logs;
- payment credentials absent from traces;
- sensitive tax identifiers redacted;
- unauthorised diagnostic access rejected.

## INTERNAL

- INTERNAL no-payment state considered healthy;
- INTERNAL payment attempt alerts;
- INTERNAL shadow rating does not create monetary obligation metrics.

## Incidents

- duplicate collection detection;
- provider drift;
- cross-tenant mismatch;
- financial integrity incident;
- targeted kill switch.

## Recovery

- Kill Bill outage and recovery;
- Payments outage and recovery;
- event transport outage and backlog drain;
- reconciliation after recovery;
- service health restored while drift remains;
- drift resolution before full recovery.

## Deployment

- release marker visible;
- rollback does not erase financial facts;
- incompatible migration prevents readiness.

---

# 120. Recommended implementation sequence

```text
1. Health model
        │
        ▼
2. Structured logging
        │
        ▼
3. Metrics foundation
        │
        ▼
4. Distributed tracing
        │
        ▼
5. Outbox / inbox telemetry
        │
        ▼
6. Usage / rating telemetry
        │
        ▼
7. Billing pipeline telemetry
        │
        ▼
8. Provider / Payments / ERP health
        │
        ▼
9. Reconciliation telemetry
        │
        ▼
10. SLI definitions
        │
        ▼
11. Production SLO configuration
        │
        ▼
12. Alerting
        │
        ▼
13. Kill switches
        │
        ▼
14. Runbooks
        │
        ▼
15. Incident / recovery automation
```

---

# 121. Operational dashboard model

```text
┌─────────────────────────────────────────────────────────┐
│             BAOBAB SUBSCRIPTIONS OPERATIONS             │
├─────────────────────────────────────────────────────────┤
│                                                         │
│ PLATFORM HEALTH                                         │
│ ├── API                                                 │
│ ├── PostgreSQL                                          │
│ ├── Event Transport                                     │
│ └── Instance Readiness                                  │
│                                                         │
│ BILLING PIPELINE                                        │
│ ├── CP Projection Lag                                   │
│ ├── Usage Ingestion                                     │
│ ├── Rating                                              │
│ ├── Billing Cycles                                      │
│ ├── Invoice Projection                                  │
│ └── Financial Obligations                               │
│                                                         │
│ INTEGRATIONS                                            │
│ ├── Kill Bill                                           │
│ ├── Baobab Payments                                     │
│ └── ERP                                                 │
│                                                         │
│ FINANCIAL CORRECTNESS                                   │
│ ├── Reconciliation                                      │
│ ├── Provider Drift                                      │
│ ├── Payment Mismatch                                    │
│ ├── Duplicate Monetary Facts                            │
│ └── INTERNAL Monetary Violations                        │
│                                                         │
│ ASYNC RELIABILITY                                       │
│ ├── Outbox                                              │
│ ├── Inbox                                               │
│ ├── Retry                                               │
│ └── Quarantine                                          │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

# 122. Worked example — Kill Bill outage

Assume:

```text
Subscriptions API
HEALTHY

PostgreSQL
HEALTHY

Event transport
HEALTHY

Kill Bill
UNAVAILABLE
```

The engine should report something equivalent to:

```text
liveness:
HEALTHY

core readiness:
HEALTHY

provider integration:
DEGRADED

provider-required provisioning:
BLOCKED
```

It SHALL NOT report every subscription as terminated.

INTERNAL subscriptions for which provider participation is `NOT_REQUIRED` may remain fully healthy.

---

# 123. Worked example — HTTP healthy, billing unhealthy

Suppose:

```text
API:
200 OK

Database:
healthy

Kill Bill:
healthy
```

but:

```text
oldest unpublished outbox event:
6 hours

billing cycles awaiting processing:
12,000
```

Then:

```text
process health = healthy
billing pipeline = degraded
financial convergence = at risk
```

A green HTTP health endpoint SHALL not hide the condition.

---

# 124. Worked example — incorrect INTERNAL collection

Suppose:

```text
ProductSubscription:
INTERNAL

shadow-rated value:
ZAR 5,000
```

and a defect generates:

```text
PaymentRequest:
ZAR 5,000
```

The required behaviour is:

```text
PaymentRequest
     │
     X
policy safety barrier
     │
     ▼
REJECT
     │
     ├── audit
     ├── critical financial alert
     ├── quarantine affected workflow
     └── reconciliation investigation
```

The system SHALL not rely on the payment provider to catch the mistake.

---

# 125. Worked example — provider recovery

```text
Kill Bill unavailable
       │
       ▼
provider mutations queued/blocked
       │
       ▼
Kill Bill restored
       │
       ▼
health = available
       │
       ▼
provider reconciliation
       │
       ├── missing resource
       ├── stale resource
       ├── duplicate resource
       └── state drift
       │
       ▼
repair / manual review
       │
       ▼
provider convergence confirmed
```

Provider availability alone is insufficient to declare recovery.

---

# 126. Worked example — payment outage

```text
FinancialObligation
ZAR 10,000
       │
       ▼
Payments unavailable
       │
       ▼
obligation preserved
payment handoff pending
       │
       ▼
Payments restored
       │
       ▼
reconcile prior outcome
       │
       ▼
safe handoff/retry
```

The engine SHALL not:

```text
delete obligation
```

or blindly create repeated payment attempts.

---

# 127. Worked example — incident lifecycle

```text
Duplicate payment suspected
        │
        ▼
CRITICAL FINANCIAL INCIDENT
        │
        ▼
Contain
pause affected collection scope
        │
        ▼
Investigate
billing + payments + provider
        │
        ▼
Determine canonical state
        │
        ▼
Correct through controlled
refund/adjustment/reconciliation
        │
        ▼
Reconcile
        │
        ▼
Verify
        │
        ▼
Resume
        │
        ▼
Post-incident review
```

Returning HTTP health to green is only one step.

---

# 128. Final Decision

Baobab SHALL operate `baobab-subscriptions` as a **financially observable system**, not merely an available web service.

The principal health rule is:

> **Liveness proves that a process is alive; readiness proves that it can safely accept a workload; neither proves that billing is financially correct.**

The principal observability rule is:

> **Infrastructure, asynchronous pipelines, providers, billing-domain state and financial reconciliation must be observable as distinct layers.**

The principal SLO rule is:

> **Service levels must measure the workflows that matter—projection convergence, usage processing, billing completion, payment handoff and reconciliation—not merely HTTP uptime.**

The principal correctness rule is:

> **Financial correctness is an independent operational dimension and may require immediate intervention even when availability and latency remain healthy.**

The principal degradation rule is:

> **Dependency failures should degrade only the capabilities that depend on them, while monetary uncertainty must fail closed rather than be disguised as availability.**

The principal provider rule is:

> **Kill Bill availability is provider health; it is not canonical billing health, and provider recovery must be followed by reconciliation where drift is possible.**

The principal INTERNAL rule is:

> **The absence of payment/provider activity for an INTERNAL subscription can be correct and healthy; attempted monetary execution for INTERNAL is a financial-integrity violation.**

The principal incident rule is:

> **Financial incidents end only after the affected financial state has been reconciled—not merely when processes return to service.**

The principal operational-control rule is:

> **High-risk billing workflows must support narrowly scoped, authenticated, authorised and audited containment without rewriting existing financial history.**

The principal recovery rule is:

> **Service recovery restores processing; reconciliation establishes correctness.**

And the overarching operational principle is:

> **A billing system is not healthy merely because it is running. It is healthy when it is running, progressing, converging, controlled, observable, and capable of demonstrating that its financial facts remain correct.**