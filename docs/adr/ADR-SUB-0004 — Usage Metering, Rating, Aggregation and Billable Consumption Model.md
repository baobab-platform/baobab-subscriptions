# ADR-SUB-0004 — Usage Metering, Rating, Aggregation and Billable Consumption Model

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Usage Metering / Rating  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None

**Related:**

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment
- ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-SHARED-007 — Capability Contracts
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- `shared/contracts/subscriptions/v1`
- `shared/contracts/product/v1`

---

# 1. Context

Baobab subscriptions may require measurement of service consumption.

Usage has purposes beyond invoicing.

It may support:

- commercial usage billing;
- capacity management;
- internal cost attribution;
- operational intelligence;
- product analytics;
- abuse detection;
- quota visibility;
- forecasting;
- service optimisation;
- shadow pricing;
- future product design.

ADR-SUB-0006 establishes an important distinction:

```text
INTERNAL
    metering = applicable/required
    monetary obligation = zero

COMMERCIAL
    metering = policy-driven
    monetary obligation = policy-driven
```

Consequently:

```text
usage != charge
```

Likewise:

```text
usage event
    != rated usage
    != charge
    != invoice
    != payment
    != ledger entry
```

These distinctions are necessary for financial correctness.

A raw measurement emitted by a product engine cannot safely become a payment merely because it contains a number.

Baobab therefore requires an explicit pipeline from **observed consumption** to **financial consequence**.

---

# 2. Decision

`baobab-subscriptions` SHALL own the canonical operational process by which authorised usage observations become accepted usage, aggregates, rated usage and—where policy permits—monetary charges.

The architecture SHALL be:

```text
Product / Capability Engine
           │
           │ Usage Observation
           ▼
   Usage Ingestion Boundary
           │
           ▼
      UsageRecord
           │
           ▼
   Aggregation / Metering
           │
           ▼
      UsageAggregate
           │
           ▼
         Rating
           │
           ▼
       RatedUsage
           │
           ▼
    Billing Policy
       /       \
      /         \
     ▼           ▼
 INTERNAL    COMMERCIAL
     │           │
     ▼           ▼
zero charge    Charge
                 │
                 ▼
            Billing Cycle
                 │
                 ▼
       Payment obligation
        where applicable
```

No stage SHALL be silently collapsed into another where doing so would lose financial provenance or replay safety.

---

# 3. Domain separation

The following concepts SHALL remain distinct.

| Concept | Meaning |
|---|---|
| Usage Observation | A producer's assertion that consumption occurred |
| UsageRecord | Validated and accepted Baobab usage fact |
| UsageAggregate | Derived quantity over a defined dimension/window |
| RatedUsage | Usage interpreted under a rating rule |
| Charge | Monetary billing consequence |
| Credit/Adjustment | Controlled modification of monetary consequence |
| Invoice Projection | Billing presentation/collection grouping |
| Payment Obligation | Request for monetary execution |
| Payment | Monetary execution owned by `baobab-payments` |
| Ledger Entry | Accounting consequence owned by `baobab-erp` |

---

# 4. Usage authority

A producer is authoritative only for the usage facts it is authorised to report.

It is not authoritative for:

- subscription classification;
- billing policy;
- price;
- monetary amount;
- payment execution;
- entitlement;
- accounting.

Therefore:

```text
Producer
   │
   │ quantity = 250 API calls
   ▼
Subscriptions
```

is valid.

But:

```text
Producer
   │
   │ "charge customer ZAR 500"
   ▼
Subscriptions
```

SHALL NOT constitute canonical billing authority.

---

# 5. Usage producers

Usage MAY originate from Baobab engines or authorised platform components that can reliably observe consumption.

Examples may include:

```text
baobab-trade
baobab-cms
baobab-pulse
future Baobab engines
platform infrastructure
```

The specific authorised producers and metrics SHALL be registered through canonical product/capability configuration rather than implicitly trusted because they are internal services.

---

# 6. Producer authorization

Every usage producer SHALL be authenticated and authorised.

The subscriptions engine SHALL determine:

```text
Who produced this?
        │
        ▼
For which tenant?
        │
        ▼
For which subscription?
        │
        ▼
For which metric?
        │
        ▼
Is this producer authorised
to report that metric?
```

Only then may the observation become an accepted `UsageRecord`.

---

# 7. UsageObservation

An incoming observation SHOULD carry sufficient canonical information equivalent to:

```text
UsageObservation
────────────────────────────

usage_event_id
schema_version

tenant_id
product_subscription_id

metric_id
quantity
unit

occurred_at

producer

correlation_id
causation_id

dimensions
```

The normative schema SHALL remain in `baobab-platform/shared`.

---

# 8. Event identity

Every financially relevant usage observation SHALL have a stable event identity.

For example:

```text
usage_event_id = usage_01...
```

The identity SHALL be suitable for duplicate detection.

Transport-level message identity MAY be preserved separately where necessary.

---

# 9. UsageRecord

After successful validation, the observation becomes an accepted `UsageRecord`.

Conceptually:

```text
UsageRecord
────────────────────────────

usage_record_id

source_event_id
producer

tenant_id
product_subscription_id
billing_subscription_id

metric_id

quantity
unit

occurred_at
received_at
accepted_at

dimensions

authoritative_revision/context

correlation_id
causation_id
```

A UsageRecord is an operational billing fact.

It is not yet a charge.

---

# 10. Usage immutability

Accepted usage records SHALL be immutable in their financial meaning.

A producer SHALL NOT silently update an old record from:

```text
quantity = 100
```

to:

```text
quantity = 500
```

Corrections SHALL be represented explicitly.

This preserves auditability.

---

# 11. Duplicate usage

At-least-once delivery SHALL be assumed.

Therefore:

```text
usage event X
   │
   ├── delivery 1
   ├── delivery 2
   └── delivery 3
```

must produce:

```text
one accepted usage effect
```

not three.

Deduplication SHALL survive process restart.

---

# 12. Idempotent usage ingestion

Conceptually:

```text
incoming usage
      │
      ▼
source_event_id already accepted?
      │
   ┌──┴──┐
  yes    no
   │      │
   ▼      ▼
return   validate
prior       │
result      ▼
        persist UsageRecord
```

Usage idempotency is a financial invariant.

---

# 13. Conflicting duplicate

If the same `usage_event_id` arrives with materially different content, it SHALL NOT be silently accepted.

For example:

```text
event X:
    quantity = 100
```

followed by:

```text
event X:
    quantity = 900
```

SHALL result in an idempotency/data-integrity conflict.

The second payload does not overwrite the first.

---

# 14. Exact quantities

Usage quantities SHALL use data types appropriate to the metric.

Where fractional quantities are valid, exact decimal semantics SHALL be used.

Binary floating-point SHALL NOT be used where it could introduce financially meaningful rounding errors.

---

# 15. Metric definition

Every meterable metric SHALL have a canonical definition.

Conceptually:

```text
UsageMetric
─────────────────────

metric_id
name
unit

aggregation_method
allowed_dimensions

metering_precision

late_arrival_policy

correction_policy
```

Where financial rating applies, additional billing-policy references MAY be associated with the metric.

---

# 16. Metric identity

Human-readable metric names SHALL not be canonical identity.

For example:

```text
"AI Tokens"
```

may change.

A stable:

```text
metric_id
```

SHALL identify the metric.

---

# 17. Unit integrity

Usage SHALL have explicit units.

Examples:

```text
request
transaction
GB
GB-hour
token
shipment
document
seat-day
```

A number without a defined unit SHALL not be financially rated.

The platform SHALL NOT assume:

```text
100
```

has financial meaning without knowing what is being measured.

---

# 18. Unit compatibility

The rating policy SHALL consume a compatible metric unit.

For example:

```text
meter:
    GB

rating rule:
    price per GB
```

is compatible.

But:

```text
meter:
    API request

rating rule:
    price per GB
```

SHALL fail validation unless an explicit transformation exists.

---

# 19. Dimensions

Usage MAY contain dimensions required for aggregation or pricing.

Examples:

```text
market
region
service_tier
model
operation_type
storage_class
```

Dimensions SHALL be governed.

Producers SHALL NOT create arbitrary unbounded dimensions that become billing policy without canonical definition.

---

# 20. High-cardinality dimensions

High-cardinality operational metadata SHALL not automatically become billing dimensions.

For example:

```text
request_id
trace_id
user_agent
```

may be useful operationally but inappropriate as rating dimensions.

Billing dimensions SHALL be allowlisted.

---

# 21. Tenant context

Every accepted UsageRecord SHALL belong to a trusted tenant context.

The subscriptions engine SHALL verify consistency between:

- producer authority;
- ProductSubscription;
- BillingSubscriptionProjection;
- tenant;
- metric.

A usage event cannot change tenant merely by declaring another tenant identifier.

---

# 22. Subscription validation

Before accepting billable usage, subscriptions SHALL establish that the referenced ProductSubscription/BillingSubscriptionProjection is appropriate for the usage timestamp and metric.

Usage SHALL not be accepted as billable merely because an identifier exists.

---

# 23. Temporal semantics

The following timestamps SHALL remain distinguishable:

```text
occurred_at
received_at
accepted_at
rated_at
charged_at
```

For example:

```text
occurred_at = 23:59 August 31
received_at = 00:02 September 1
```

does not automatically make the usage September consumption.

Billing-period allocation SHALL use explicit policy.

---

# 24. Event time

`occurred_at` SHOULD represent when the consumption actually occurred.

This is normally the primary temporal basis for assigning usage to a billing window.

Producer clock reliability and late-arrival policy SHALL nevertheless be considered.

---

# 25. Late-arriving usage

Late usage is expected in distributed systems.

The architecture SHALL explicitly support:

```text
billing window closes
        │
        ▼
usage arrives later
```

The system SHALL NOT silently discard financially valid usage merely because transport was delayed.

---

# 26. Late-arrival policy

Each applicable billing policy SHALL define how late usage is treated.

Possible governed outcomes include:

```text
include before cycle finalisation
carry into correction
create adjustment
manual review
reject after retention boundary
```

The engine SHALL not invent an ad hoc policy at ingestion time.

---

# 27. Future-dated usage

Usage substantially in the future relative to trusted system time SHALL be rejected, quarantined or flagged according to policy.

A producer SHALL not be able to manipulate billing periods by sending arbitrary future timestamps.

---

# 28. Usage windows

Aggregation SHALL occur over explicit windows.

Examples:

```text
hour
day
billing cycle
calendar month
contract period
```

The window SHALL be determined by metric and billing policy.

---

# 29. Aggregation

A `UsageAggregate` represents derived consumption over a defined scope.

Conceptually:

```text
UsageAggregate
─────────────────────────

aggregate_id

billing_subscription_id
metric_id

window_start
window_end

dimensions

aggregation_method
quantity

source_count

computed_at
revision
```

---

# 30. Aggregation methods

The architecture SHALL support explicit aggregation semantics such as:

```text
SUM
COUNT
MAX
MIN
LATEST
DISTINCT_COUNT
```

where required by product/billing policy.

The exact supported set SHALL be defined canonically.

The engine SHALL not assume every metric is summed.

---

# 31. Example aggregation

Raw usage:

```text
10 requests
20 requests
15 requests
```

under:

```text
aggregation = SUM
```

produces:

```text
45 requests
```

But a seat metric may instead use:

```text
MAX
```

or another defined rule.

Aggregation semantics are part of the billing contract.

---

# 32. Recomputability

Usage aggregates SHOULD be reproducible from accepted usage records within applicable retention boundaries.

Derived aggregates SHALL not become the only evidence that consumption occurred.

This enables:

- reconciliation;
- correction;
- policy migration analysis;
- dispute investigation.

---

# 33. Rating

Rating answers:

> Given accepted/aggregated usage and an applicable pricing rule, what economic value or monetary consequence does that usage represent?

Rating SHALL be separate from metering.

```text
metering:
    what happened?

rating:
    what is it worth under this policy?
```

---

# 34. RatedUsage

Conceptually:

```text
RatedUsage
─────────────────────────

rated_usage_id

billing_subscription_id
usage_aggregate_id

metric_id

quantity
unit

rating_policy_id
rating_policy_version

currency

unit_rate
rated_amount

monetary_treatment

effective_period

rated_at
```

The exact implementation MAY vary.

The semantic separation SHALL remain.

---

# 35. Pricing authority

Usage producers SHALL NOT provide authoritative monetary rates.

The rating engine SHALL resolve applicable pricing from Baobab billing policy.

Therefore:

```text
usage producer
      │
      │ quantity
      ▼
subscriptions
      │
      │ authoritative pricing
      ▼
rating
```

Not:

```text
usage producer
      │
      │ quantity + arbitrary price
      ▼
charge
```

---

# 36. Pricing version

Every financially meaningful rating SHALL identify the pricing/policy version applied.

Historical usage SHALL remain explainable after prices change.

For example:

```text
August:
    price = ZAR 1/unit

September:
    price = ZAR 1.20/unit
```

August usage SHALL not silently become worth ZAR 1.20/unit because the current price changed.

---

# 37. Effective pricing

Pricing SHALL be selected according to effective-time semantics.

Conceptually:

```text
usage occurred_at
       │
       ▼
effective pricing window
       │
       ▼
rating policy version
```

The precise policy may use billing-period or contractual semantics, but it SHALL be explicit.

---

# 38. Rating models

The architecture SHALL permit rating models such as:

```text
flat per unit
tiered
volume
graduated
included allowance + overage
fixed + usage
market-specific
contract-specific
```

without redefining usage ingestion.

Metering should remain stable as pricing evolves.

---

# 39. Included allowances

A commercial plan MAY include usage allowances.

For example:

```text
first 10,000 requests included
thereafter ZAR 0.01/request
```

The system SHALL retain the actual usage quantity even where some or all usage produces no incremental charge.

---

# 40. Zero-rated usage

Rated usage can legitimately produce:

```text
rated_amount = 0
```

for reasons including:

- included allowance;
- zero-price tier;
- trial;
- credit treatment;
- INTERNAL policy;
- shadow rating.

Zero rated amount SHALL retain its reason/provenance.

---

# 41. INTERNAL usage

INTERNAL subscriptions SHALL support usage ingestion and aggregation where applicable.

The flow is:

```text
INTERNAL ProductSubscription
          │
          ▼
     UsageRecord
          │
          ▼
   UsageAggregate
          │
          ▼
 optional analytical rating
          │
          ▼
 monetary obligation = ZERO
```

Usage volume SHALL never autonomously convert the subscription to COMMERCIAL.

---

# 42. INTERNAL shadow rating

Baobab MAY compute economic shadow values for INTERNAL consumption.

Example:

```text
actual consumption:
    1,000 units

commercial-equivalent value:
    ZAR 500

actual payable amount:
    ZAR 0
```

The distinction SHALL be explicit.

Shadow value SHALL never be presented to `baobab-payments` as payable.

---

# 43. COMMERCIAL usage

For COMMERCIAL usage-based billing:

```text
UsageRecord
      │
      ▼
UsageAggregate
      │
      ▼
Rating
      │
      ▼
RatedUsage
      │
      ▼
Charge
```

The charge may then participate in billing-cycle/invoice/payment workflows.

---

# 44. Charge

A `Charge` SHALL represent a monetary billing consequence.

Conceptually:

```text
Charge
─────────────────────────

charge_id

billing_subscription_id

source_type
source_reference

billing_period

amount
currency

pricing_reference
policy_reference

status

created_at
```

A charge is not a payment.

---

# 45. Charge provenance

Every usage-derived charge SHALL be traceable to:

```text
Charge
  │
  ▼
RatedUsage
  │
  ▼
UsageAggregate
  │
  ▼
UsageRecords
  │
  ▼
source UsageObservations
```

and separately:

```text
Charge
  │
  ▼
pricing/policy version
```

This lineage is mandatory for explainability.

---

# 46. Charge and payment separation

The relationship is:

```text
Charge
   │
   ▼
billing obligation
   │
   ▼
baobab-payments
   │
   ▼
payment execution
```

Not:

```text
UsageRecord
   │
   ▼
payment provider
```

Usage SHALL never bypass billing policy.

---

# 47. Charge and accounting separation

Likewise:

```text
Charge
   │
   ▼
canonical billing fact
   │
   ▼
baobab-erp
   │
   ▼
accounting consequence
```

A usage record SHALL not directly become a general-ledger entry.

---

# 48. Corrections

An accepted usage record SHALL not be destructively rewritten to correct financial history.

Corrections SHOULD use explicit adjustment semantics.

Conceptually:

```text
Original UsageRecord
       │
       ▼
CorrectionRecord
       │
       ▼
recomputed aggregate
       │
       ▼
rating correction
       │
       ▼
credit / debit adjustment
```

where applicable.

---

# 49. Negative usage

Negative usage quantities SHALL NOT be used as an undocumented shortcut for corrections unless the canonical metric contract explicitly defines such semantics.

A first-class correction model is preferred.

This prevents ambiguity between:

```text
actual negative consumption
```

and:

```text
correction of prior consumption
```

---

# 50. Usage correction provenance

A correction SHALL identify:

- original record/reference;
- reason;
- correcting actor/workload;
- correction time;
- corrected quantity or delta;
- correlation;
- resulting billing consequence.

Financial corrections SHALL be auditable.

---

# 51. Billing-cycle finalisation

A billing period SHALL have explicit finalisation semantics.

Conceptually:

```text
OPEN
  │
  ▼
CLOSING
  │
  ├── collect allowed late usage
  ├── aggregate
  ├── rate
  ├── reconcile
  │
  ▼
FINALIZED
```

The exact billing-cycle state model may be specified in a subsequent ADR.

This ADR requires only that usage processing respect explicit finalisation rather than assuming wall-clock period end equals immutable closure.

---

# 52. Post-finalisation usage

Usage received after finalisation SHALL be handled through explicit late/correction policy.

It SHALL NOT silently mutate a finalised charge without audit evidence.

Possible consequences include:

```text
next-cycle adjustment
supplementary charge
credit/debit adjustment
manual review
```

according to policy.

---

# 53. Re-rating

Re-rating historical usage is financially sensitive.

It SHALL require an explicit reason, such as:

- pricing correction;
- usage correction;
- policy defect;
- contract correction.

The engine SHALL NOT automatically re-rate historical usage merely because a new price version exists.

---

# 54. Re-rating output

Where historical re-rating changes monetary consequence, the system SHOULD produce an adjustment rather than erasing the original financial result.

Conceptually:

```text
original rated amount = 100
correct rated amount  = 90
         │
         ▼
adjustment = -10
```

This preserves history.

---

# 55. Usage and classification transitions

Suppose:

```text
INTERNAL until T1
COMMERCIAL from T1
```

Usage SHALL be classified according to the authoritative effective transition.

```text
usage before T1
    │
    ▼
INTERNAL treatment

usage from T1
    │
    ▼
COMMERCIAL treatment
```

The arrival time of the usage event SHALL not silently change this treatment.

---

# 56. COMMERCIAL to INTERNAL transition

Likewise:

```text
COMMERCIAL until T2
INTERNAL from T2
```

usage occurring before T2 may remain commercially billable according to applicable policy even if received after T2.

Usage occurring from T2 receives INTERNAL treatment.

---

# 57. Historical classification provenance

Rated usage SHALL preserve sufficient classification/policy provenance to explain why a monetary treatment was applied.

The engine SHALL not determine historical classification by simply reading the subscription's current classification.

---

# 58. Suspension

Billing suspension semantics SHALL specify whether usage:

- continues to be accepted;
- continues to be metered but not charged;
- is rejected;
- is accumulated pending resumption.

The behaviour SHALL be policy-driven.

The engine SHALL NOT assume suspension always means zero usage.

---

# 59. Termination

Usage occurring after effective termination SHALL normally not create new billable consumption unless an explicit policy permits delayed settlement of previously authorised activity.

Late-arriving usage that actually occurred before termination remains distinguishable from usage that occurred after termination.

---

# 60. Quotas

Usage quotas and billing meters are related but distinct.

A quota may answer:

> Is further consumption allowed?

A billing meter answers:

> How much consumption occurred?

Entitlement/quota enforcement SHALL remain governed by the appropriate product/Control Plane architecture.

Subscriptions MAY publish usage information used by those decisions but SHALL not silently become entitlement authority.

---

# 61. Usage reporting

Subscriptions MAY expose usage views appropriate for:

- tenant billing visibility;
- internal operations;
- product analytics;
- cost analysis.

Reporting SHALL distinguish:

```text
raw/accepted usage
aggregated usage
rated usage
payable charge
```

Users SHALL not be misled into treating one as another.

---

# 62. Estimated versus final usage

Where the platform exposes provisional usage/rating, it SHALL distinguish estimates from finalised financial results.

For example:

```text
current estimated usage charge
```

is not equivalent to:

```text
finalised billing charge
```

This distinction SHOULD be machine-readable.

---

# 63. Reconciliation

Usage processing SHALL participate in reconciliation.

At minimum, reconciliation SHOULD detect:

| Condition | Meaning |
|---|---|
| Duplicate source event | Same usage fact received more than once |
| Conflicting duplicate | Same identity, different content |
| Unknown metric | Usage references unsupported metric |
| Unauthorized producer | Producer cannot report metric |
| Tenant mismatch | Usage conflicts with subscription tenant |
| Missing subscription | Usage cannot resolve billing projection |
| Invalid unit | Unit incompatible with metric |
| Missing pricing | Commercial usage cannot be rated |
| Aggregate drift | Stored aggregate differs from recomputation |
| Rating drift | Rated value differs from applicable policy |
| INTERNAL monetary violation | INTERNAL usage produced payable amount |
| Provider usage drift | Provider usage differs where provider projection is used |

---

# 64. Provider usage

Where Kill Bill requires usage data for provider-side billing execution, the flow SHALL remain:

```text
Baobab UsageRecord
       │
       ▼
Baobab aggregation/rating policy
       │
       ▼
BillingProvider
       │
       ▼
KillBillAdapter
       │
       ▼
Kill Bill
```

Kill Bill SHALL NOT become the authoritative source of raw Baobab usage merely because usage is forwarded to it.

---

# 65. Provider usage identifiers

Usage forwarded to Kill Bill SHOULD retain deterministic correlation to Baobab usage/aggregate identity where provider capabilities permit.

This supports:

- idempotency;
- reconciliation;
- dispute investigation.

Provider IDs SHALL remain secondary identifiers.

---

# 66. Provider outage

Kill Bill unavailability SHALL NOT cause accepted usage to disappear.

The sequence may be:

```text
accept usage
    │
    ▼
persist locally
    │
    ▼
provider unavailable
    │
    ▼
durable pending provider work
    │
    ▼
retry/reconcile
```

Provider availability SHALL not determine whether Baobab observed the usage fact.

---

# 67. No synchronous provider dependency at ingestion

Where architecture permits, accepting valid canonical usage SHOULD NOT require synchronous Kill Bill availability.

Otherwise a provider outage could cause loss of source consumption data.

Provider projection SHOULD be asynchronous and recoverable.

---

# 68. Usage retention

Usage retention SHALL account for:

- financial dispute windows;
- reconciliation;
- legal requirements;
- privacy obligations;
- accounting requirements;
- operational analytics.

Retention policy SHALL be explicit.

This ADR does not prescribe a universal retention duration.

---

# 69. Data minimisation

Usage records SHALL not contain unnecessary personal information.

Where tenant/account/subscription references are sufficient, raw user identity or payload content SHOULD not be copied into billing usage.

For example, metering:

```text
documents_processed = 10
```

does not require storing the documents themselves.

---

# 70. Usage payload prohibition

The subscriptions engine SHALL not become a repository for business payloads merely because they generated usage.

Metering SHOULD store the minimum fact necessary to establish consumption.

---

# 71. Observability

Usage infrastructure SHALL expose telemetry including:

- ingestion rate;
- rejected usage;
- duplicate usage;
- conflicting duplicates;
- late-arriving usage;
- unknown metrics;
- unauthorized producers;
- aggregation lag;
- rating lag;
- rating failures;
- unpriced commercial usage;
- INTERNAL usage volumes;
- INTERNAL monetary-policy violations;
- provider usage backlog;
- reconciliation drift.

Metrics SHALL avoid uncontrolled high-cardinality tenant labels.

---

# 72. Backpressure

The ingestion architecture SHALL support controlled backpressure.

A usage surge SHALL not be allowed to exhaust subscriptions resources or destabilise unrelated billing operations.

Mechanisms MAY include:

- bounded queues;
- broker backpressure;
- rate limits;
- batch processing;
- partitioning;
- consumer scaling.

The precise infrastructure is an implementation decision.

---

# 73. Partitioning

Where event transport supports partitioning, usage ordering requirements SHOULD be scoped to the smallest necessary aggregate, such as:

```text
tenant + subscription + metric
```

rather than requiring global ordering.

The implementation SHALL still tolerate duplicate and out-of-order delivery.

---

# 74. Batch ingestion

Batch usage submission MAY be supported.

Each usage item SHALL retain independent identity and validation semantics.

A batch envelope SHALL NOT erase per-record idempotency.

Partial failure semantics SHALL be explicit.

---

# 75. Bulk financial safety

A malformed item in a large usage batch SHALL not result in ambiguous acceptance.

The API/event consumer SHOULD report which items were:

```text
accepted
duplicate
rejected
blocked
```

where the integration contract supports such feedback.

---

# 76. Usage security

Usage ingestion SHALL conform to ADR-SUB-0016.

Specifically:

- producer identity SHALL be authenticated;
- producer authorization SHALL be metric-scoped;
- tenant context SHALL be trusted;
- duplicate detection SHALL be durable;
- corrections SHALL be controlled mutations;
- audit SHALL cover financially material corrections.

---

# 77. Usage abuse

The platform SHALL consider both accidental and malicious usage manipulation.

Examples include:

```text
producer sends exaggerated quantity

producer sends usage for another tenant

producer replays usage repeatedly

producer backdates usage

producer uses unsupported metric

producer submits extreme quantity
```

Validation and anomaly detection SHOULD make such conditions observable.

---

# 78. Plausibility controls

Metric definitions MAY include reasonable validation constraints such as:

```text
quantity >= 0
maximum accepted event quantity
maximum future clock skew
allowed dimensions
allowed unit
```

Such controls SHALL be metric-specific rather than arbitrary global assumptions.

---

# 79. Rating precision

Monetary calculations SHALL use explicit precision and rounding policy.

The engine SHALL define:

- calculation precision;
- currency minor-unit handling;
- rounding mode;
- point at which rounding occurs.

Rounding SHALL be deterministic and testable.

---

# 80. Aggregate-before-rounding

Where policy requires aggregation before monetary rounding, the implementation SHALL not round every micro-event independently if doing so changes the financial result.

Example:

```text
many fractional charges
       │
       ▼
aggregate/rate
       │
       ▼
policy-defined rounding
```

The order of operations is part of billing policy.

---

# 81. Currency

Every monetary RatedUsage/Charge SHALL have an explicit currency.

Usage itself need not have currency.

This distinction is important:

```text
UsageRecord:
    100 GB

RatedUsage:
    100 GB
    ZAR 200
```

---

# 82. FX

If rating requires foreign-exchange conversion, the applicable FX source, rate, timestamp and policy SHALL be explicit and auditable.

This ADR does not establish FX authority.

FX SHALL not be silently derived from current market rates during historical re-rating.

---

# 83. Taxes

Usage rating and tax determination are distinct.

Conceptually:

```text
usage
  │
  ▼
rating
  │
  ▼
pre-tax monetary consequence
  │
  ▼
applicable tax process
```

where the commercial model requires taxation.

Classification alone SHALL not determine tax treatment.

---

# 84. Contract-specific pricing

A tenant-specific commercial agreement MAY override standard pricing where canonical policy permits.

Such overrides SHALL be explicit, versioned and auditable.

They SHALL not be hidden inside Kill Bill configuration without Baobab provenance.

---

# 85. Rating engine boundary

The initial rating implementation MAY live inside `baobab-subscriptions`.

However, its domain interface SHOULD remain sufficiently explicit that future extraction of a specialised rating component would not require changing canonical usage contracts.

This ADR does not mandate a separate rating service.

---

# 86. Event-driven pipeline

The preferred architecture is:

```text
Producer
   │
   ▼
Usage Observation
   │
   ▼
Ingestion
   │
   ▼
UsageRecord
   │
   ▼
Aggregation
   │
   ▼
Rating
   │
   ▼
Charge
```

Stages MAY execute asynchronously.

Financial correctness SHALL not depend on all stages completing in one request.

---

# 87. No distributed transaction

The platform SHALL NOT attempt a distributed ACID transaction spanning:

```text
usage producer
subscriptions
Kill Bill
payments
ERP
```

Correctness SHALL instead use:

- durable local state;
- idempotency;
- outbox/inbox;
- retries;
- revisions;
- reconciliation;
- audit.

---

# 88. Audit lineage

For any usage-derived commercial charge, Baobab SHOULD be able to answer:

```text
Who reported the usage?

What was consumed?

For which tenant?

For which ProductSubscription?

When did consumption occur?

Which metric definition applied?

How was it aggregated?

Which classification applied?

Which pricing version applied?

How was it rated?

Why is the charge this amount?

Was it corrected?

Was it sent to a provider?

Did it result in payment?
```

This is the standard of explainability required for billable consumption.

---

# 89. Domain invariants

The following invariants SHALL hold.

### INV-USG-01

Usage is distinct from monetary charge.

### INV-USG-02

Usage producers cannot determine subscription classification.

### INV-USG-03

Usage producers cannot determine authoritative price.

### INV-USG-04

Usage producers cannot directly trigger payment.

### INV-USG-05

Accepted usage has stable identity.

### INV-USG-06

Duplicate delivery cannot duplicate consumption.

### INV-USG-07

Conflicting duplicate payloads are rejected.

### INV-USG-08

Usage is tenant-scoped.

### INV-USG-09

Usage metrics and units are explicitly defined.

### INV-USG-10

Aggregation semantics are explicit.

### INV-USG-11

Financial rating is policy-versioned.

### INV-USG-12

Historical usage is not silently re-rated under current pricing.

### INV-USG-13

INTERNAL usage cannot produce a payable monetary obligation.

### INV-USG-14

INTERNAL usage may remain metered and analytically rated.

### INV-USG-15

Commercial usage with missing required pricing fails closed.

### INV-USG-16

Corrections preserve historical provenance.

### INV-USG-17

Late-arriving usage follows explicit policy.

### INV-USG-18

Provider unavailability cannot erase accepted usage.

### INV-USG-19

Kill Bill is not authoritative for canonical Baobab usage.

### INV-USG-20

Usage-derived charges are traceable to source usage and applicable policy.

### INV-USG-21

Payment remains the responsibility of `baobab-payments`.

### INV-USG-22

Accounting remains the responsibility of `baobab-erp`.

### INV-USG-23

Current classification cannot be blindly applied to historical usage.

### INV-USG-24

Financial arithmetic uses deterministic exact monetary semantics.

---

# 90. Alternatives considered

## 90.1 Send raw usage directly to Kill Bill and make it authoritative

**Rejected.**

This would make Baobab's usage domain provider-dependent and weaken reconciliation and provider portability.

---

## 90.2 Let product engines calculate charges

**Rejected.**

Product engines observe consumption but do not own billing policy.

---

## 90.3 Treat every usage event as a charge

**Rejected.**

This prevents aggregation, allowances, tiering, INTERNAL metering and policy-based rating.

---

## 90.4 Do not meter INTERNAL subscriptions

**Rejected.**

INTERNAL usage remains operationally and economically valuable even when monetary obligation is zero.

---

## 90.5 Allow producers to submit price with usage

**Rejected as authoritative billing input.**

It would distribute pricing authority across engines.

---

## 90.6 Update old usage records during correction

**Rejected.**

Destructive updates weaken auditability and dispute resolution.

---

## 90.7 Re-rate all history whenever pricing changes

**Rejected.**

New pricing does not automatically rewrite historical financial meaning.

---

## 90.8 Require Kill Bill synchronously during usage ingestion

**Rejected.**

Provider failure could cause loss of canonical usage facts.

---

## 90.9 Use floating-point arithmetic for monetary rating

**Rejected.**

It can introduce nondeterministic or financially significant precision errors.

---

# 91. Consequences

## Positive

- Usage becomes provider-independent.
- INTERNAL subscriptions remain measurable without being chargeable.
- Commercial usage is traceable from observation to charge.
- Duplicate delivery is financially safe.
- Pricing can evolve independently of metering.
- Kill Bill outages do not lose canonical usage.
- Historical billing remains explainable.
- Corrections are auditable.
- Tiered, usage-based and hybrid pricing can evolve cleanly.
- Product engines remain outside billing authority.

## Negative

- Usage ingestion requires durable storage and deduplication.
- Aggregation and rating become explicit subsystems.
- Late usage and corrections require lifecycle handling.
- Billing finalisation becomes more sophisticated.
- Reconciliation must cover usage and rating.
- High-volume products may require substantial scaling infrastructure.

These costs are accepted because usage billing without durable provenance and deterministic rating creates unacceptable financial risk.

---

# 92. Implementation requirements

An implementation conforming to this ADR SHALL provide:

1. canonical usage ingestion;
2. authenticated and authorised usage producers;
3. stable usage-event identity;
4. durable deduplication;
5. trusted tenant resolution;
6. metric and unit validation;
7. immutable accepted usage facts;
8. explicit correction semantics;
9. aggregation;
10. policy-driven rating;
11. pricing/policy version provenance;
12. classification-aware monetary treatment;
13. zero-payable INTERNAL enforcement;
14. commercial missing-price fail-closed behaviour;
15. explicit late-arrival handling;
16. deterministic monetary precision/rounding;
17. charge provenance;
18. provider-independent canonical usage storage;
19. asynchronous/recoverable Kill Bill projection where required;
20. reconciliation;
21. usage/rating observability;
22. security controls defined by ADR-SUB-0016.

---

# 93. Required tests

At minimum, automated tests SHALL cover:

### Ingestion

- valid usage;
- duplicate event;
- conflicting duplicate;
- unknown metric;
- invalid unit;
- unauthorised producer;
- tenant mismatch;
- unknown subscription;
- future-dated usage;
- late-arriving usage.

### Aggregation

- SUM;
- applicable non-SUM aggregation;
- window boundaries;
- dimensions;
- recomputation;
- duplicate protection.

### Rating

- fixed unit price;
- tiered/allowance example;
- price-version boundary;
- zero-rated commercial usage;
- missing commercial price;
- deterministic rounding.

### INTERNAL

- usage accepted;
- usage aggregated;
- zero payable amount;
- optional shadow rating;
- no payment obligation.

### Classification transitions

- INTERNAL → COMMERCIAL effective boundary;
- COMMERCIAL → INTERNAL effective boundary;
- late event crossing classification transition;
- historical treatment preserved.

### Corrections

- explicit usage correction;
- re-rating;
- resulting debit/credit adjustment;
- original record preserved.

### Provider

- Kill Bill unavailable during ingestion;
- provider usage retry;
- provider usage reconciliation;
- duplicate provider projection prevention.

---

# 94. Recommended implementation sequence

```text
1. Canonical UsageObservation contract
                │
                ▼
2. UsageRecord persistence
                │
                ▼
3. Producer authorization + tenant validation
                │
                ▼
4. Durable deduplication
                │
                ▼
5. Metric registry
                │
                ▼
6. Aggregation
                │
                ▼
7. Rating policy
                │
                ▼
8. INTERNAL enforcement
                │
                ▼
9. COMMERCIAL usage charges
                │
                ▼
10. Corrections / late usage
                │
                ▼
11. Provider projection
                │
                ▼
12. Reconciliation + observability
```

---

# 95. Relationship to ADR-SUB-0006

ADR-SUB-0004 determines:

> How much authorised consumption occurred and how it is rated.

ADR-SUB-0006 determines:

> What monetary treatment applies to the subscription.

Therefore:

```text
Usage
  │
  ▼
ADR-SUB-0004
meter + aggregate + rate
  │
  ▼
ADR-SUB-0006
classification-driven treatment
  │
  ├── INTERNAL ──► zero payable
  │
  └── COMMERCIAL ► charge according to policy
```

Neither ADR replaces the other.

---

# 96. Relationship to ADR-SUB-0015

ADR-SUB-0004 establishes canonical usage before provider projection.

ADR-SUB-0015 determines how provider participation occurs.

```text
Canonical Usage
      │
      ▼
Subscriptions domain
      │
      ▼
BillingProvider
      │
      ▼
KillBillAdapter
      │
      ▼
Kill Bill
```

Kill Bill SHALL therefore receive provider-specific representations of Baobab usage rather than becoming Baobab's source of truth for consumption.

---

# 97. Relationship to ADR-SUB-0016

Usage ingestion is a financially sensitive mutation.

ADR-SUB-0016 therefore governs:

- workload authentication;
- producer authorization;
- trusted tenant context;
- idempotency;
- audit;
- correction authority;
- reconciliation privileges.

A producer's ability to report one metric SHALL not imply authority to report arbitrary metrics or mutate billing state.

---

# 98. Final decision

Baobab SHALL treat usage billing as a staged, auditable transformation:

```text
OBSERVATION
     │
     ▼
VALIDATION
     │
     ▼
ACCEPTED USAGE
     │
     ▼
AGGREGATION
     │
     ▼
RATING
     │
     ▼
BILLING POLICY
     │
     ▼
CHARGE
     │
     ▼
PAYMENT OBLIGATION
     │
     ▼
PAYMENT
     │
     ▼
ACCOUNTING
```

Each stage has distinct authority and provenance.

The principal metering rule is:

> **A usage producer may report what was consumed; it does not decide what that consumption costs.**

The principal billing rule is:

> **Usage is evidence of consumption, not evidence of debt. A monetary obligation exists only after authorised usage has been validated, aggregated where required, rated under the applicable versioned policy, and subjected to the subscription's authoritative billing classification.**

The principal INTERNAL rule is:

> **INTERNAL usage remains visible, measurable and auditable, but no amount of INTERNAL consumption autonomously creates a payable obligation or changes the subscription to COMMERCIAL.**

The principal provider rule is:

> **Baobab records canonical usage before provider projection. Kill Bill may participate in billing execution, but provider availability or provider state does not determine whether Baobab observed the underlying consumption.**

And the principal financial-integrity rule is:

> **Every usage-derived charge must be explainable backwards—from charge, to rating, to aggregate, to accepted usage, to source observation—and forwards to any resulting payment and accounting consequence without rewriting historical facts.**