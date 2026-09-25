# ADR-SUB-0007 — Subscription Commercial Lifecycle, Amendments, Renewal, Suspension and Termination

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Subscription Lifecycle / Commercial State  
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
- ADR-SUB-0011 — Billing Account, Payer, Invoice Recipient and Financial Responsibility Model
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts

---

# 1. Context

A subscription is not financially static.

After initial creation it may undergo:

- activation;
- delayed commencement;
- trial;
- renewal;
- price migration;
- plan change;
- commercial amendment;
- suspension;
- resumption;
- cancellation;
- termination;
- expiry;
- classification transition;
- payer change;
- product-version migration.

Each transition can have billing consequences.

For example:

```text
COMMERCIAL subscription
        │
        ▼
ZAR 1,000/month
        │
        ▼
upgrade mid-cycle
        │
        ▼
ZAR 1,500/month
```

Baobab must determine:

- when the new terms become effective;
- whether the current period is prorated;
- whether old usage remains under the old price;
- whether allowances reset;
- whether an invoice adjustment is required;
- whether the provider must be updated;
- whether payment obligations change.

Similarly:

```text
ACTIVE
   │
   ▼
SUSPENDED
```

does not inherently answer:

> Does billing stop?

Nor does:

```text
CANCELLED
```

answer:

> Is the cancellation immediate, end-of-period, or future-dated?

Those are commercial policy decisions.

Another authority boundary is critical.

Control Plane remains authoritative for the existence and authoritative lifecycle of the ProductSubscription.

`baobab-subscriptions` owns the **billing consequence** of that lifecycle.

Therefore:

```text
Control Plane
    decides authoritative
    subscription lifecycle
          │
          ▼
baobab-subscriptions
    determines corresponding
    billing lifecycle
```

Subscriptions SHALL NOT create a parallel authoritative subscription lifecycle.

---

# 2. Decision

Baobab SHALL model subscription commercial lifecycle through **effective-dated, revision-aware billing transitions** derived from authoritative ProductSubscription changes.

The conceptual lifecycle is:

```text
Authoritative ProductSubscription
              │
              ▼
BillingSubscriptionProjection
              │
              ▼
Current BillingTerms
              │
              ▼
Commercial Lifecycle Event
              │
              ▼
Transition Evaluation
              │
       ┌──────┼─────────┐
       ▼      ▼         ▼
   Terms   Billing    Provider
   Change  Consequence Consequence
       │      │         │
       └──────┼─────────┘
              ▼
     New Billing Revision
```

Every financially material lifecycle change SHALL preserve:

- prior state;
- new state;
- effective time;
- authoritative source;
- source revision;
- applicable billing policy;
- applicable pricing version;
- monetary consequence;
- provider consequence;
- audit evidence.

---

# 3. Authority boundary

Control Plane owns:

```text
ProductSubscription existence
ProductSubscription authoritative status
classification
classification provenance
ProductVersion relationship
entitlement decisions
CapabilityGrant
```

Subscriptions owns:

```text
BillingSubscriptionProjection
BillingTerms
billing-cycle consequences
proration
commercial amendment consequences
provider billing projection
financial adjustments
```

Kill Bill owns only provider-local execution state.

---

# 4. Three lifecycle models

Baobab SHALL distinguish:

```text
AUTHORITATIVE LIFECYCLE
Control Plane

        │
        ▼

BILLING LIFECYCLE
baobab-subscriptions

        │
        ▼

PROVIDER LIFECYCLE
Kill Bill
```

These state machines SHALL NOT be treated as identical.

---

# 5. Lifecycle projection principle

A ProductSubscription transition is an authoritative input.

It is not itself the final billing instruction.

For example:

```text
ProductSubscription
SUSPENDED
```

may resolve through commercial policy to:

```text
BillingSubscriptionProjection
SUSPENDED

billing:
CONTINUE
```

or:

```text
billing:
PAUSE
```

depending on applicable BillingTerms.

---

# 6. Effective time

Every financially material transition SHALL have an effective time.

The system SHALL distinguish:

```text
decision_at
effective_at
received_at
processed_at
```

where relevant.

These timestamps SHALL NOT be assumed identical.

---

# 7. Late lifecycle events

An authoritative lifecycle event may arrive after its effective time.

Example:

```text
effective_at:
2026-09-01

received_at:
2026-09-03
```

Subscriptions SHALL evaluate the consequence from the authoritative effective time rather than pretending the transition began on September 3.

---

# 8. Revision ordering

Lifecycle changes SHALL carry or resolve authoritative revision/version information where available.

A stale event SHALL NOT regress newer state.

Example:

```text
revision 12:
ACTIVE → SUSPENDED

revision 13:
SUSPENDED → ACTIVE
```

A late replay of revision 12 SHALL not suspend revision 13.

---

# 9. Billing lifecycle states

The BillingSubscriptionProjection SHOULD support semantic states equivalent to:

```text
PENDING_CONFIGURATION
        │
        ▼
PROVISIONING
        │
        ▼
ACTIVE
        │
        ▼
SUSPENDED
        │
        ▼
TERMINATING
        │
        ▼
TERMINATED
```

These remain consistent with ADR-SUB-0003.

---

# 10. Commercial conditions separate from lifecycle state

Billing lifecycle state SHALL NOT carry every commercial meaning.

For example:

```text
state = SUSPENDED
```

and:

```text
billing_treatment = CONTINUE
```

may coexist.

This avoids overloaded state values.

---

# 11. Activation

Activation establishes that billing may begin according to applicable terms.

Conceptually:

```text
ProductSubscription
      ACTIVE
        │
        ▼
Billing readiness
        │
        ▼
BillingTerms valid?
        │
      YES
        │
        ▼
BillingSubscription
      ACTIVE
```

---

# 12. Activation is not necessarily immediate charging

Activation SHALL NOT automatically imply:

```text
charge immediately
```

The applicable BillingTerms may specify:

- immediate billing;
- billing in arrears;
- future billing anchor;
- trial;
- zero initial period;
- usage-only billing.

---

# 13. Billing commencement

Billing commencement SHALL be explicit.

Conceptually:

```text
service_effective_at
billing_effective_at
```

MAY differ where authoritative commercial policy permits.

Such divergence SHALL be explainable and audited.

---

# 14. Trial lifecycle

A COMMERCIAL subscription MAY begin with a trial.

Example:

```text
COMMERCIAL
    │
    ▼
TRIAL
ZAR 0
    │
    ▼
STANDARD
ZAR 500/month
```

The subscription remains COMMERCIAL throughout.

Trial is a pricing phase, not a classification.

---

# 15. Trial expiry

Trial expiry SHALL produce a deterministic pricing-phase transition.

The system SHALL know in advance:

```text
trial ends when?
next phase?
next price?
billing anchor?
```

A missing post-trial configuration SHALL block unsafe monetary billing rather than inventing terms.

---

# 16. Renewal

Renewal SHALL be a first-class commercial lifecycle event where applicable.

Renewal may:

```text
preserve current terms
```

or:

```text
resolve new terms
```

depending on commercial policy.

---

# 17. Renewal is not silent mutation

The engine SHALL preserve:

```text
prior term
renewal boundary
renewed term
```

where renewal changes financially relevant conditions.

---

# 18. Renewal models

Baobab SHALL permit models including:

```text
AUTO_RENEW
FIXED_TERM
MANUAL_RENEWAL
NON_RENEWING
```

or canonical equivalents.

The exact vocabulary SHALL be governed centrally.

---

# 19. Renewal date

Renewal date SHALL be explicit.

It SHALL NOT be inferred solely from invoice issuance date.

---

# 20. Renewal pricing

Renewal MAY:

- retain grandfathered pricing;
- adopt current catalogue pricing;
- adopt contractually predetermined pricing;
- resolve a negotiated renewal price.

The applicable rule SHALL be explicit.

---

# 21. Renewal failure

Where a renewal requires configuration that cannot be resolved, the engine SHALL fail closed.

It SHALL not silently renew under arbitrary pricing.

---

# 22. Commercial amendment

A material change to commercial terms SHALL be represented as an amendment or new BillingTerms revision.

Examples:

- price change;
- frequency change;
- allowance change;
- payer-related commercial terms;
- contract change;
- billing anchor change.

---

# 23. BillingTerms revision

Conceptually:

```text
BillingTerms v1
     │
     ▼
Commercial Amendment
     │
     ▼
BillingTerms v2
```

v1 remains historical.

---

# 24. No destructive terms update

The following is prohibited for financially used terms:

```text
UPDATE billing_terms
SET monthly_price = 1500
WHERE monthly_price = 1000
```

where that operation destroys historical meaning.

---

# 25. Amendment effective date

Every amendment SHALL define when it takes effect.

Example:

```text
Old terms:
until 2026-09-14 23:59:59

New terms:
from 2026-09-15 00:00:00
```

The boundary SHALL be unambiguous.

---

# 26. Prospective changes

Prospective change SHALL be the normal model.

```text
old terms
   │
   ▼
effective boundary
   │
   ▼
new terms
```

---

# 27. Retroactive amendments

Retroactive commercial amendments SHALL be exceptional and controlled.

They SHALL require:

- explicit authority;
- reason;
- affected interval;
- prior terms;
- corrected terms;
- recalculation;
- adjustment;
- audit.

---

# 28. Historical correction

Retroactive correction SHALL NOT erase original financial facts.

The sequence SHOULD be:

```text
Original Billing
       │
       ▼
Correction Decision
       │
       ▼
Re-rating / recalculation
       │
       ▼
Credit / Debit Adjustment
```

---

# 29. Plan transition

Changing PricingPlan SHALL be a first-class domain transition.

Conceptually:

```text
Plan A
  │
  ▼
Transition
  │
  ├── effective time
  ├── proration policy
  ├── allowance policy
  ├── billing-cycle policy
  └── provider consequence
  │
  ▼
Plan B
```

---

# 30. Upgrade and downgrade

Terms such as `upgrade` and `downgrade` MAY be useful commercial labels.

They SHALL NOT be inferred merely from price.

Canonical semantics remain:

```text
source plan
target plan
effective time
transition policy
```

---

# 31. Immediate plan transition

A plan change MAY become effective immediately where permitted.

The current billing cycle may then contain:

```text
Period A
old plan

Period B
new plan
```

The boundary SHALL be explicit.

---

# 32. End-of-cycle plan transition

A plan change MAY instead be scheduled:

```text
Current Cycle
      │
      ▼
cycle end
      │
      ▼
New Plan
```

This often avoids proration but remains a policy choice.

---

# 33. Proration

Where required, proration SHALL be deterministic and policy-driven.

Conceptually:

```text
Full Period Price
        │
        ▼
Proration Policy
        │
        ▼
Applicable Fraction
        │
        ▼
Prorated Charge/Credit
```

---

# 34. Proration inputs

A proration policy SHALL define at least:

- period start;
- period end;
- effective transition boundary;
- calculation basis;
- rounding;
- affected components.

---

# 35. No universal proration assumption

Baobab SHALL NOT assume every mid-cycle transition is prorated.

Possible policy outcomes include:

```text
NO_PRORATION
PRORATE_OLD_AND_NEW
CREDIT_UNUSED_OLD
CHARGE_NEW_IMMEDIATELY
NEXT_CYCLE_ONLY
```

or canonical equivalents.

---

# 36. Recurring-component proration

Recurring fees MAY be prorated where policy permits.

Usage already consumed SHALL not be treated as unused recurring service.

---

# 37. Usage during plan transition

Usage SHALL remain attributable to the pricing terms effective when the usage occurred, subject to ADR-SUB-0004.

Conceptually:

```text
09:00 usage
Plan A

12:00 plan changes

14:00 usage
Plan B
```

The system SHALL preserve this distinction.

---

# 38. Usage event time

Pricing SHALL use the appropriate authoritative usage/effective timestamp, not merely the time at which the event happened to arrive.

---

# 39. Allowance transition

A plan change may alter usage allowances.

The transition policy SHALL explicitly define whether:

- allowance resets;
- allowance is prorated;
- prior consumption carries forward;
- new allowance begins next cycle.

---

# 40. No implicit allowance reset

The engine SHALL NOT automatically grant a fresh full allowance merely because a subscriber changes plan mid-cycle.

That could enable allowance gaming.

---

# 41. Billing-frequency change

Changing:

```text
monthly
```

to:

```text
annual
```

SHALL be a controlled BillingTerms transition.

The engine SHALL explicitly determine:

- effective boundary;
- remaining current-period treatment;
- new billing anchor;
- proration;
- provider update.

---

# 42. Billing-anchor change

Changing the billing anchor SHALL not silently create overlapping or missing billing periods.

The transition SHALL ensure continuous, non-duplicated billing coverage.

---

# 43. Suspension

Suspension SHALL be treated as an authoritative service lifecycle condition whose billing consequence is determined separately.

Possible billing policies include:

```text
CONTINUE_BILLING

PAUSE_RECURRING_BILLING

STOP_NEW_USAGE_BILLING

PRORATE_SUSPENSION

CONTRACT_SPECIFIC
```

---

# 44. Suspension does not imply cancellation

A suspended subscription remains a subscription unless authoritative Control Plane state says otherwise.

Historical billing identity SHALL remain.

---

# 45. Suspension effective time

Suspension SHALL have an effective boundary.

Charges and usage before that boundary remain governed by the prior state.

---

# 46. Usage during suspension

Usage received during a suspension interval SHALL be evaluated according to policy.

It SHALL not simply disappear.

Possible outcomes include:

- valid billable usage;
- valid non-billable usage;
- rejected usage;
- anomalous usage requiring investigation.

---

# 47. Suspension and entitlement

Subscriptions SHALL NOT directly revoke CapabilityGrant because billing is suspended.

The authority flow remains:

```text
Billing condition
       │
       ▼
Canonical lifecycle signal
       │
       ▼
Control Plane
       │
       ▼
Entitlement decision
```

---

# 48. Resumption

Resumption SHALL restore billing according to the applicable terms and effective time.

It SHALL define whether:

- previous cycle continues;
- new cycle starts;
- recurring billing resumes prospectively;
- proration applies.

---

# 49. Suspension interval preservation

The suspension interval SHALL remain historical.

The engine SHALL NOT collapse:

```text
ACTIVE
SUSPENDED
ACTIVE
```

into a false uninterrupted ACTIVE history.

---

# 50. Cancellation request versus cancellation effective time

Baobab SHALL distinguish:

```text
cancel_requested_at
```

from:

```text
cancel_effective_at
```

A subscription may be scheduled for cancellation at period end.

---

# 51. Cancellation modes

Commercial policy MAY support:

```text
IMMEDIATE

END_OF_CURRENT_PERIOD

FIXED_FUTURE_DATE
```

or canonical equivalents.

---

# 52. Immediate cancellation

Immediate cancellation SHALL define:

- final recurring treatment;
- usage cut-off;
- proration/refund policy;
- final billing cycle;
- provider action.

---

# 53. End-of-period cancellation

For end-of-period cancellation:

```text
cancel requested
       │
       ▼
subscription remains active
through current period
       │
       ▼
effective cancellation
```

The provider SHALL not be terminated prematurely.

---

# 54. Scheduled cancellation

A scheduled cancellation SHALL be represented explicitly.

It SHALL survive process restarts and provider outages.

---

# 55. Cancellation reversal

Where policy permits cancellation reversal before the effective boundary, it SHALL be a controlled operation.

The history SHALL retain:

```text
cancellation requested
cancellation revoked
```

---

# 56. Termination

Termination represents completion of the subscription's billing lifecycle.

Conceptually:

```text
ACTIVE/SUSPENDED
       │
       ▼
TERMINATING
       │
       ├── close usage window
       ├── final rating
       ├── final charges
       ├── final adjustments
       ├── provider termination
       └── reconciliation
       │
       ▼
TERMINATED
```

---

# 57. Termination does not erase debt

A terminated subscription MAY still have:

- unpaid invoices;
- outstanding obligations;
- credits;
- disputes;
- accounting history.

Therefore:

```text
subscription terminated
```

does not imply:

```text
financial balance = zero
```

---

# 58. Termination does not erase history

The engine SHALL preserve:

- BillingTerms;
- usage;
- charges;
- invoices;
- provider mappings;
- payer history;
- audit.

---

# 59. Final usage

Termination SHALL define a final usage acceptance boundary.

Usage whose authoritative occurrence precedes termination but arrives later SHALL be handled according to late-usage policy.

---

# 60. Final invoice

Where required, termination SHALL permit a final billing cycle or final invoice.

This MAY include:

- final recurring amount;
- final usage;
- prorated amount;
- credits;
- adjustments.

---

# 61. Expiry

A fixed-term subscription may expire without an explicit cancellation request.

Expiry SHALL be represented as an authoritative lifecycle outcome.

Billing SHALL terminate according to applicable terms.

---

# 62. Expiry versus cancellation

Expiry and cancellation SHALL remain distinguishable where commercially meaningful.

```text
expiry:
contract naturally ended

cancellation:
contract ended through explicit action
```

---

# 63. Non-renewal

Non-renewal SHALL mean:

```text
current term remains valid
but no subsequent term begins
```

It SHALL not prematurely terminate the current authorised period.

---

# 64. Classification transition

Classification changes SHALL be treated as financially significant lifecycle transitions.

Examples:

```text
INTERNAL → COMMERCIAL
COMMERCIAL → INTERNAL
```

These are governed primarily by ADR-SUB-0006 but must integrate with lifecycle processing.

---

# 65. INTERNAL to COMMERCIAL

An INTERNAL → COMMERCIAL transition SHALL have an explicit effective boundary.

Historical INTERNAL periods SHALL not be retroactively billed merely because the subscription later becomes COMMERCIAL.

```text
INTERNAL period
     │
     X
retroactive billing
     │
effective boundary
     ▼
COMMERCIAL billing
```

unless a separately authorised correction establishes that the earlier classification itself was erroneous.

---

# 66. COMMERCIAL to INTERNAL

A COMMERCIAL → INTERNAL transition SHALL stop new commercial obligations according to the effective boundary.

Legitimate obligations established before that boundary remain valid.

---

# 67. Payer transition

Payer changes are governed by ADR-SUB-0011.

Lifecycle processing SHALL preserve the distinction between:

```text
subscription terms transition
```

and:

```text
financial responsibility transition
```

They may occur together but are not the same event.

---

# 68. ProductVersion migration

A ProductSubscription MAY migrate to a different ProductVersion where Control Plane authorises it.

Subscriptions SHALL determine whether the migration affects:

- offering;
- PricingPlan;
- metrics;
- allowances;
- BillingTerms;
- provider representation.

---

# 69. ProductVersion does not automatically change price

A ProductVersion transition MAY retain existing BillingTerms.

Likewise a price transition may occur without a ProductVersion change.

The dimensions remain independent.

---

# 70. Provider projection

Lifecycle changes requiring provider participation SHALL flow:

```text
Authoritative change
       │
       ▼
Billing lifecycle decision
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

The provider SHALL not determine the transition's Baobab meaning.

---

# 71. Provider plan change

Kill Bill plan-change functionality MAY be used where its semantics match the Baobab transition.

The adapter SHALL verify semantic compatibility.

It SHALL NOT blindly expose provider plan-change semantics as Baobab domain semantics.

---

# 72. Provider suspension

A provider suspension SHALL be an execution consequence of a Baobab billing decision.

The existence of a suspended Kill Bill resource SHALL NOT by itself establish that the authoritative ProductSubscription is suspended.

---

# 73. Provider cancellation

Likewise:

```text
Kill Bill subscription cancelled
```

does not automatically mean:

```text
Control Plane ProductSubscription cancelled
```

Unexpected provider cancellation is drift.

---

# 74. Desired versus observed provider state

Lifecycle processing SHALL maintain:

```text
desired provider state
```

separately from:

```text
observed provider state
```

Differences SHALL trigger reconciliation.

---

# 75. Ambiguous provider outcome

If a provider transition times out after the provider may have committed:

```text
request
   │
   ▼
timeout
   │
   ▼
UNKNOWN
```

subscriptions SHALL reconcile before blindly retrying a non-safe operation.

---

# 76. Payment boundary

A lifecycle change may create:

- new obligation;
- adjustment;
- credit;
- refund candidate.

Subscriptions SHALL determine the billing consequence.

`baobab-payments` SHALL execute authorised monetary movement.

---

# 77. Refund consequence

For example:

```text
Immediate cancellation
       │
       ▼
unused prepaid value
       │
       ▼
billing policy says refund
       │
       ▼
refund obligation/candidate
       │
       ▼
baobab-payments
```

Subscriptions SHALL NOT directly execute the refund.

---

# 78. ERP boundary

Lifecycle transitions may produce accounting consequences.

Those SHALL be communicated as canonical financial facts.

ERP determines:

- journal entries;
- receivable adjustments;
- revenue recognition;
- accounting treatment.

---

# 79. State transition validation

Subscriptions SHALL reject invalid lifecycle transitions.

For example, unless a separately defined recovery workflow permits otherwise:

```text
TERMINATED
    │
    X
    ▼
ACTIVE
```

SHALL not occur through an ordinary resume command.

---

# 80. Transition matrix

A conceptual transition matrix is:

| Current | Requested consequence | Normal result |
|---|---|---|
| PENDING_CONFIGURATION | configuration completed | PROVISIONING |
| PROVISIONING | provider ready | ACTIVE |
| ACTIVE | suspend | SUSPENDED |
| SUSPENDED | resume | ACTIVE |
| ACTIVE | cancel future | ACTIVE + scheduled termination |
| ACTIVE | cancel immediate | TERMINATING |
| SUSPENDED | cancel | TERMINATING |
| TERMINATING | finalisation complete | TERMINATED |
| TERMINATED | resume | Reject |

Exact authoritative transitions remain aligned with Control Plane.

---

# 81. Transition command model

Subscriptions SHALL prefer domain commands over arbitrary state mutation.

Examples:

```text
ApplyAuthoritativeActivation

ApplyTermsAmendment

SchedulePlanTransition

ApplyAuthoritativeSuspension

ApplyAuthoritativeResumption

ScheduleCancellation

ApplyAuthoritativeTermination

ReconcileLifecycle
```

Exact API names MAY vary.

---

# 82. No arbitrary status PATCH

The following pattern is prohibited:

```text
PATCH /billing-subscription

{
  "status": "ACTIVE"
}
```

where the caller can bypass lifecycle validation.

---

# 83. Idempotency

Every material lifecycle mutation SHALL be idempotent.

The same authoritative transition delivered repeatedly SHALL converge to one billing result.

---

# 84. Transition identity

Lifecycle processing SHOULD retain a stable transition identity including equivalent fields to:

```text
transition_id
source_event_id
product_subscription_id
source_revision
effective_at
```

---

# 85. Duplicate events

At-least-once event delivery SHALL be assumed.

Duplicate authoritative events SHALL not create:

- duplicate amendments;
- duplicate prorations;
- duplicate provider changes;
- duplicate credits;
- duplicate invoices.

---

# 86. Out-of-order events

Out-of-order lifecycle events SHALL be detected through revision/effective-time semantics.

The engine SHALL not merely apply events in arrival order.

---

# 87. Transaction boundary

Within subscriptions, a material lifecycle transition SHOULD atomically persist:

```text
new billing revision
+
new BillingTerms where applicable
+
billing consequences
+
audit
+
outbox intent
```

External calls remain outside the local transaction.

---

# 88. Transactional outbox

Provider/payment/ERP consequences SHALL be delivered asynchronously through durable outbox mechanisms where appropriate.

---

# 89. No distributed transaction

The platform SHALL NOT attempt one transaction across:

```text
Control Plane
Subscriptions
Kill Bill
Payments
ERP
```

Correctness comes from:

- local transactions;
- revision control;
- idempotency;
- outbox/inbox;
- retries;
- reconciliation.

---

# 90. Lifecycle reconciliation

Subscriptions SHALL reconcile:

```text
Control Plane authoritative state
             │
             ▼
BillingSubscriptionProjection
             │
             ▼
BillingTerms / scheduled transitions
             │
             ▼
Provider state
```

---

# 91. Reconciliation conditions

Reconciliation SHALL detect at least:

```text
missed activation

missed suspension

missed resumption

missed cancellation

incorrect effective time

stale BillingTerms

provider plan drift

provider suspension drift

provider cancellation drift

duplicate transition

unfinished termination
```

---

# 92. Automatic repair

Auto-repair MAY occur where the desired state is unambiguous and the repair is:

- idempotent;
- non-destructive;
- financially safe;
- tenant-authorised.

---

# 93. Manual review

Manual review SHALL be preferred where reconciliation discovers:

- conflicting effective dates;
- ambiguous pricing history;
- unexplained monetary discrepancy;
- duplicate provider resources with financial history;
- uncertain retroactive transition;
- ambiguous obligation/refund consequence.

---

# 94. Security

Lifecycle transitions SHALL conform to ADR-SUB-0016.

Material operations SHALL be:

```text
authenticated
+
authorised
+
tenant-scoped
+
revision-safe
+
idempotent
+
audited
```

---

# 95. Authoritative caller protection

An arbitrary tenant-facing client SHALL NOT be permitted to manufacture authoritative lifecycle facts such as:

```text
classification = INTERNAL
```

or:

```text
authoritative subscription = TERMINATED
```

where Control Plane owns those decisions.

---

# 96. Audit

Audit evidence SHALL capture:

```text
transition
source authority
old state
new state
old BillingTerms
new BillingTerms
effective time
source revision
pricing policy
proration policy
monetary consequence
provider consequence
correlation ID
causation ID
```

where applicable.

---

# 97. Observability

Subscriptions SHOULD expose telemetry for:

- lifecycle transitions;
- scheduled transitions;
- failed transitions;
- stale events;
- duplicate events;
- out-of-order events;
- plan migrations;
- suspension/resumption;
- cancellations;
- unfinished terminations;
- proration failures;
- provider drift;
- lifecycle reconciliation backlog.

---

# 98. Scheduled transitions

Future-effective transitions SHALL be durable.

They SHALL NOT rely solely on an in-memory timer.

For example:

```text
cancel_effective_at =
2026-10-01
```

must survive:

- deployment;
- restart;
- provider outage;
- worker failure.

---

# 99. Scheduler authority

A scheduler MAY trigger evaluation of a due transition.

It SHALL NOT become the authority for why the transition exists.

The durable scheduled transition record remains authoritative within the subscriptions domain.

---

# 100. Clock discipline

Financial transition logic SHALL use trusted server/platform time.

Client-supplied current time SHALL NOT determine whether a transition is due.

---

# 101. Time-zone semantics

Effective boundaries SHALL be unambiguous.

Where commercial rules use local calendar boundaries, the relevant time zone SHALL be explicit.

Internally persisted timestamps SHOULD remain offset-aware/unambiguous.

---

# 102. Domain invariants

### INV-LCY-01

Control Plane remains authoritative for ProductSubscription lifecycle.

### INV-LCY-02

Billing lifecycle and authoritative subscription lifecycle are distinct.

### INV-LCY-03

Provider lifecycle is not canonical Baobab lifecycle.

### INV-LCY-04

Every financially material transition has an effective time.

### INV-LCY-05

Arrival time does not replace effective time.

### INV-LCY-06

Stale revisions cannot regress newer billing state.

### INV-LCY-07

BillingTerms changes preserve historical versions.

### INV-LCY-08

Published historical terms are not destructively rewritten.

### INV-LCY-09

Plan transitions have explicit effective semantics.

### INV-LCY-10

Proration is policy-driven, not assumed.

### INV-LCY-11

Usage remains attributable to the terms applicable at occurrence.

### INV-LCY-12

Allowance changes have explicit transition semantics.

### INV-LCY-13

Suspension does not inherently mean billing stops.

### INV-LCY-14

Suspension does not inherently revoke entitlement.

### INV-LCY-15

Cancellation request time and cancellation effective time are distinct.

### INV-LCY-16

Termination does not erase legitimate financial obligations.

### INV-LCY-17

Termination does not erase financial history.

### INV-LCY-18

INTERNAL → COMMERCIAL does not retroactively monetise legitimate INTERNAL history.

### INV-LCY-19

COMMERCIAL → INTERNAL does not erase legitimate prior COMMERCIAL obligations.

### INV-LCY-20

Provider state cannot redefine authoritative lifecycle.

### INV-LCY-21

Payment execution remains outside subscriptions.

### INV-LCY-22

Lifecycle transitions are idempotent.

### INV-LCY-23

Duplicate events cannot create duplicate financial consequences.

### INV-LCY-24

Out-of-order events cannot silently regress state.

### INV-LCY-25

Future-effective transitions are durably persisted.

### INV-LCY-26

Lifecycle reconciliation cannot manufacture upstream authority.

### INV-LCY-27

Retroactive financial corrections preserve original history.

### INV-LCY-28

A terminated subscription cannot be ordinarily reactivated through arbitrary state mutation.

---

# 103. Alternatives considered

## 103.1 Mirror Control Plane state exactly

**Rejected.**

Billing lifecycle has different semantics and may require intermediate states and commercial consequences.

## 103.2 Let Kill Bill own lifecycle authority

**Rejected.**

This would invert Baobab authority and couple the platform to the provider.

## 103.3 Treat suspension as automatic billing pause

**Rejected.**

Commercial contracts may require different behaviour.

## 103.4 Always prorate mid-cycle changes

**Rejected.**

Proration is a commercial policy decision.

## 103.5 Rewrite BillingTerms in place

**Rejected.**

It destroys historical financial reproducibility.

## 103.6 Apply lifecycle events in arrival order

**Rejected.**

Distributed systems produce duplicates, retries and out-of-order delivery.

## 103.7 Delete financial history on cancellation

**Rejected.**

Cancellation ends future service/billing according to policy; it does not erase history.

## 103.8 Let a scheduler own future lifecycle decisions

**Rejected.**

A scheduler executes a due decision; it does not establish its authority.

---

# 104. Consequences

## Positive

- Subscription changes remain financially explainable.
- Control Plane authority remains intact.
- Provider state remains subordinate.
- Historical pricing and billing are preserved.
- Mid-cycle changes can be handled deterministically.
- Suspension semantics become contract-aware.
- Cancellation and termination remain distinct.
- Renewal becomes explicit.
- Retroactive corrections are auditable.
- Event duplication and reordering can be handled safely.

## Negative

- Lifecycle implementation requires temporal/versioned state.
- Proration requires explicit policy.
- Future transitions require durable scheduling.
- Provider reconciliation becomes more sophisticated.
- Historical BillingTerms increase storage requirements.
- Retroactive corrections require adjustment workflows.

These costs are accepted because lifecycle ambiguity in a billing system creates direct financial risk.

---

# 105. Implementation requirements

A conforming implementation SHALL provide:

1. revision-aware lifecycle processing;
2. effective-dated transitions;
3. BillingTerms versioning;
4. activation semantics;
5. trial-phase transition support where applicable;
6. renewal semantics;
7. commercial amendments;
8. plan transitions;
9. explicit proration policy;
10. allowance-transition policy;
11. suspension;
12. resumption;
13. scheduled cancellation;
14. immediate cancellation;
15. termination;
16. expiry/non-renewal;
17. classification-transition integration;
18. ProductVersion-transition integration;
19. provider lifecycle projection;
20. durable scheduled transitions;
21. idempotency;
22. transactional outbox;
23. lifecycle reconciliation;
24. security and audit.

---

# 106. Required tests

At minimum:

### Activation

- immediate activation;
- future billing commencement;
- trial activation;
- missing billing configuration.

### Renewal

- unchanged renewal;
- renewal to new price;
- grandfathered renewal;
- non-renewal;
- failed renewal configuration.

### Plan transition

- immediate change;
- end-of-cycle change;
- prorated change;
- non-prorated change;
- allowance transition;
- duplicate transition.

### Suspension

- suspend with billing continuing;
- suspend with billing paused;
- usage during suspension;
- resume;
- repeated suspend event.

### Cancellation

- immediate cancellation;
- end-of-period cancellation;
- future cancellation;
- cancellation reversal;
- final usage;
- final invoice.

### Termination

- termination with unpaid obligation;
- termination with final usage;
- provider termination failure;
- reconciliation after provider recovery.

### Classification

- INTERNAL → COMMERCIAL;
- COMMERCIAL → INTERNAL;
- no retroactive monetisation of valid INTERNAL history;
- preservation of prior COMMERCIAL obligations.

### Distributed behaviour

- duplicate event;
- stale event;
- out-of-order event;
- timeout after provider commit;
- restart before future transition;
- reconciliation of missed event.

---

# 107. Recommended implementation sequence

```text
1. Lifecycle transition model
          │
          ▼
2. Revision/effective-time handling
          │
          ▼
3. BillingTerms revisioning
          │
          ▼
4. Activation / renewal
          │
          ▼
5. Plan transitions
          │
          ▼
6. Proration
          │
          ▼
7. Suspension / resumption
          │
          ▼
8. Cancellation
          │
          ▼
9. Termination / expiry
          │
          ▼
10. Classification transitions
          │
          ▼
11. Provider lifecycle projection
          │
          ▼
12. Durable scheduled transitions
          │
          ▼
13. Reconciliation
          │
          ▼
14. Security / audit hardening
```

---

# 108. Architectural progression

The subscription ADR sequence now establishes:

```text
ADR-SUB-0002
Billing domain and authority
        │
        ▼
ADR-SUB-0003
Projection lifecycle
        │
        ▼
ADR-SUB-0004
Usage and rating
        │
        ▼
ADR-SUB-0005
Catalogue and pricing
        │
        ▼
ADR-SUB-0006
Classification-driven
monetary treatment
        │
        ▼
ADR-SUB-0007
Commercial lifecycle
and temporal change
        │
        ▼
ADR-SUB-0011
Financial responsibility
        │
        ▼
ADR-SUB-0012
Invoice and obligation
lifecycle
```

---

# 109. End-to-end lifecycle example

Consider a COMMERCIAL subscription:

```text
01 Jan
Subscription activated
Plan A = ZAR 1,000/month

        │
        ▼

15 Mar
Plan change requested

        │
        ▼

01 Apr
Plan B effective
ZAR 1,500/month

        │
        ▼

10 Jun
Subscription suspended
billing policy = continue

        │
        ▼

20 Jun
Subscription resumed

        │
        ▼

05 Aug
Cancellation requested
effective 31 Aug

        │
        ▼

31 Aug
Final usage window closes
final billing calculated

        │
        ▼

01 Sep
TERMINATED
```

Baobab SHALL be able to reconstruct every commercial period:

```text
Jan–Mar
Plan A

Apr–Aug
Plan B

Jun 10–20
suspension interval

Aug 31
termination boundary
```

and explain every charge from the terms applicable to its period.

---

# 110. Final Decision

Baobab SHALL model subscription commercial lifecycle as **effective-dated, revision-aware transitions over immutable historical billing terms**, derived from authoritative Control Plane subscription state.

The principal authority rule is:

> **Control Plane determines what happens to the authoritative ProductSubscription; Subscriptions determines the billing consequence of that authoritative change.**

The principal temporal rule is:

> **Financial meaning follows effective time, not message-arrival time.**

The principal amendment rule is:

> **Commercial changes create new billing terms or explicit adjustments; they do not silently rewrite historical terms.**

The principal suspension rule is:

> **Suspension is a lifecycle condition, not a universal billing rule. Whether billing continues, pauses or prorates is determined by explicit commercial policy.**

The principal cancellation rule is:

> **A cancellation request and its effective termination are distinct. Future-dated cancellation must remain durable, observable and reversible where policy permits.**

The principal termination rule is:

> **Termination stops future subscription billing according to policy; it does not erase usage, invoices, debt, provider history or accounting consequences already legitimately established.**

The principal provider rule is:

> **Kill Bill executes provider-level lifecycle consequences but never determines the canonical meaning of activation, amendment, suspension, renewal or termination.**

And the principal distributed-systems rule is:

> **Lifecycle correctness must survive duplicate events, stale events, out-of-order delivery, retries, provider uncertainty, process restarts and partial failure without creating duplicate or contradictory financial consequences.**