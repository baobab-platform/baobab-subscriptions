# ADR-SUB-0008 — Billing Periods, Anchors, Calendars, Proration and Temporal Semantics

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Billing Time / Billing Periods  
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
- ADR-SUB-0011 — Billing Account, Payer, Invoice Recipient and Financial Responsibility Model
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts

---

# 1. Context

Subscription billing is fundamentally temporal.

A price such as:

```text
ZAR 1,000 / month
```

is incomplete without defining what:

```text
month
```

means.

Possible interpretations include:

```text
calendar month

subscription anniversary month

30-day period

contractual accounting period
```

These are not equivalent.

For example, a subscription beginning on:

```text
31 January
```

cannot safely implement monthly billing by repeatedly adding a fixed number of seconds or assuming every month has 30 days.

Likewise:

```text
annual
```

does not necessarily mean:

```text
365 days
```

because leap years exist and commercial contracts may use calendar anniversaries.

Baobab operates across multiple markets and time zones. Billing therefore also encounters:

- calendar boundaries;
- leap years;
- variable month lengths;
- daylight-saving transitions;
- local commercial dates;
- UTC timestamps;
- future-effective changes;
- usage windows;
- plan changes;
- suspension intervals;
- partial periods;
- invoice schedules.

A billing engine that handles these inconsistently can produce:

- duplicate charges;
- missing charges;
- overlapping periods;
- usage charged twice;
- usage omitted from billing;
- incorrect proration;
- incorrect renewals.

Therefore Baobab requires a canonical temporal model before invoice generation.

---

# 2. Decision

Baobab SHALL model billing time explicitly using:

1. **BillingAnchor**
2. **BillingCalendar**
3. **BillingPeriod**
4. **ServicePeriod**
5. **UsageWindow**
6. **ProrationPolicy**
7. **effective-time semantics**

These concepts SHALL remain distinct.

The conceptual model is:

```text
BillingTerms
     │
     ▼
BillingAnchor
     │
     ▼
BillingCalendar
     │
     ▼
BillingPeriod
     │
     ├─────────────┐
     ▼             ▼
ServicePeriod   UsageWindow
     │             │
     └──────┬──────┘
            ▼
     Charge Calculation
            │
            ▼
   InvoiceProjection
```

---

# 3. Fundamental temporal distinction

Baobab SHALL distinguish:

```text
Billing Period
      !=
Service Period
      !=
Usage Window
      !=
Invoice Date
      !=
Payment Due Date
```

They may coincide.

They SHALL NOT be assumed to coincide.

---

# 4. BillingAnchor

A `BillingAnchor` establishes the recurring reference point from which billing periods are derived.

Conceptually:

```text
BillingAnchor
─────────────────────────

anchor_id

billing_subscription_id

anchor_type

anchor_date_or_rule

timezone

effective_from

source
```

The exact implementation MAY evolve.

---

# 5. Anchor types

Baobab SHALL support or remain extensible to anchor policies equivalent to:

```text
SUBSCRIPTION_ANNIVERSARY

CALENDAR_MONTH

CONTRACT_DEFINED

FIXED_DAY_OF_MONTH
```

Additional policies MAY be introduced through explicit canonical contracts.

---

# 6. Subscription-anniversary anchor

Example:

```text
Subscription starts:
15 January

Billing periods:

15 Jan → 15 Feb
15 Feb → 15 Mar
15 Mar → 15 Apr
```

This differs from calendar-month billing.

---

# 7. Calendar-month anchor

Calendar-month billing may produce:

```text
01 Jan → 01 Feb
01 Feb → 01 Mar
01 Mar → 01 Apr
```

A subscription beginning mid-month may therefore require an initial partial period.

---

# 8. Contract-defined anchor

Enterprise agreements MAY specify an explicit billing anchor.

Example:

```text
All subscriptions under agreement:
bill from the 1st of each month
```

Baobab SHALL support this without changing tenant identity or ProductSubscription authority.

---

# 9. Billing anchor provenance

The system SHALL preserve why an anchor exists.

Possible provenance includes:

```text
subscription commencement

PricingPlan

CommercialAgreement

approved amendment
```

---

# 10. Anchor immutability

Once financially used, historical anchor semantics SHALL not be destructively rewritten.

An anchor change creates a temporal transition.

---

# 11. Anchor transition

Conceptually:

```text
Old Anchor
    │
    ▼
Effective Boundary
    │
    ▼
New Anchor
```

The transition SHALL define treatment of the bridging interval.

---

# 12. No overlapping periods

A billing-anchor transition SHALL NOT produce overlapping billing periods.

---

# 13. No accidental gaps

A billing-anchor transition SHALL NOT unintentionally create an unbilled interval.

If a gap is commercially intended, that fact SHALL be explicit.

---

# 14. BillingCalendar

A BillingCalendar defines how recurring periods are constructed.

It SHALL provide deterministic semantics for frequencies such as:

```text
MONTHLY
QUARTERLY
ANNUAL
```

---

# 15. Calendar periods, not seconds

Baobab SHALL NOT implement:

```text
MONTHLY = 2,592,000 seconds
```

or:

```text
ANNUAL = 31,536,000 seconds
```

as universal commercial semantics.

Calendar billing SHALL use calendar arithmetic.

---

# 16. Monthly semantics

Monthly billing means progression by calendar month according to anchor policy.

Example:

```text
15 Jan
   │
   ▼
15 Feb
   │
   ▼
15 Mar
```

not:

```text
15 Jan + 30 days
```

---

# 17. Variable month lengths

Billing logic SHALL correctly handle:

```text
28-day February

29-day February

30-day months

31-day months
```

without creating drift.

---

# 18. End-of-month anchors

End-of-month behaviour SHALL be deterministic.

For example, an anchor established on January 31 requires explicit policy for February.

A policy MAY use:

```text
LAST_DAY_OF_MONTH
```

semantics.

Thus:

```text
31 Jan
  │
  ▼
28 Feb
  │
  ▼
31 Mar
```

in a non-leap year.

---

# 19. Leap years

Calendar calculations SHALL correctly support leap years.

For example:

```text
29 February 2028
```

requires explicit anniversary policy when the corresponding date does not exist in another year.

---

# 20. Quarterly billing

Quarterly billing SHALL mean three calendar months according to the applicable anchor/calendar policy unless the contract explicitly defines otherwise.

---

# 21. Annual billing

Annual billing SHALL follow calendar-anniversary semantics or explicit contract semantics.

It SHALL NOT universally mean 365 days.

---

# 22. Custom billing periods

Baobab MAY support contract-defined billing periods.

Such periods SHALL be:

- explicit;
- deterministic;
- versioned;
- auditable.

---

# 23. BillingPeriod

A BillingPeriod SHALL be represented by an unambiguous interval.

Conceptually:

```text
BillingPeriod
────────────────────────

billing_period_id

billing_subscription_id

period_start

period_end

anchor_reference

billing_terms_reference

status
```

---

# 24. Half-open intervals

Baobab SHOULD model temporal billing intervals using half-open semantics:

```text
[start, end)
```

Meaning:

```text
start inclusive
end exclusive
```

Example:

```text
[2026-09-01T00:00,
 2026-10-01T00:00)
```

---

# 25. Why half-open intervals

Half-open intervals permit adjacent periods:

```text
Period A
[start A, end A)

Period B
[end A, end B)
```

without overlap or ambiguity at the boundary.

---

# 26. Period continuity

For uninterrupted recurring billing:

```text
period_n.end
=
period_n+1.start
```

SHALL normally hold.

---

# 27. Billing period identity

A BillingPeriod SHALL have stable identity.

Repeated processing SHALL not create a second period for the same logical interval.

---

# 28. Billing period uniqueness

The system SHOULD enforce an invariant equivalent to:

```text
billing_subscription
+
period_start
+
period_end
+
billing_terms_revision
```

being uniquely identifiable according to the selected persistence model.

---

# 29. ServicePeriod

The ServicePeriod describes the interval during which the relevant subscribed service was authorised/provided for billing purposes.

It MAY differ from BillingPeriod.

Example:

```text
BillingPeriod:
01 Sep → 01 Oct

ServicePeriod:
15 Sep → 01 Oct
```

for mid-period activation.

---

# 30. UsageWindow

UsageWindow determines which usage facts belong to a particular billing/rating interval.

Example:

```text
UsageWindow
[01 Sep, 01 Oct)
```

Usage at exactly:

```text
01 Oct 00:00
```

belongs to the next window.

---

# 31. Usage attribution

Usage SHALL be attributed according to the authoritative usage occurrence/effective timestamp established by ADR-SUB-0004.

Arrival time SHALL not ordinarily determine the billing period.

---

# 32. Late usage

A usage event may arrive after its UsageWindow has closed.

Example:

```text
occurred:
29 Sep

received:
03 Oct
```

The system SHALL retain the September occurrence semantics.

---

# 33. Late usage policy

Late usage MAY result in:

```text
current invoice adjustment

next invoice adjustment

supplemental charge

manual review
```

according to policy.

It SHALL NOT simply be moved into October usage because it arrived in October.

---

# 34. Usage cut-off

Billing MAY define a processing cut-off after period end.

Example:

```text
Period closes:
30 Sep

Usage cut-off:
02 Oct

Invoice generated:
03 Oct
```

The distinction SHALL be explicit.

---

# 35. Event time versus processing time

Baobab SHALL distinguish:

```text
occurred_at

received_at

processed_at

rated_at

invoiced_at
```

where applicable.

These timestamps SHALL not be conflated.

---

# 36. Time zones

All persisted instants SHALL be unambiguous.

Where practical, internal event instants SHOULD use UTC/offset-aware representations.

However, commercial calendar rules MAY require a named local time zone.

---

# 37. Named time zones

Where local calendar semantics matter, Baobab SHALL retain a named time-zone identifier rather than only a numeric UTC offset.

Conceptually:

```text
Africa/Johannesburg
Africa/Kampala
```

A fixed offset alone may be insufficient for jurisdictions with daylight-saving rules.

---

# 38. Market is not time zone

Baobab SHALL NOT assume:

```text
Market
=
Time Zone
```

A market may span several time zones, and contractual billing may use a different commercial time zone.

---

# 39. Tenant is not time zone

Likewise:

```text
Tenant
=
Time Zone
```

SHALL NOT be assumed.

Billing time zone comes from authoritative billing terms/policy.

---

# 40. Daylight-saving transitions

Where a billing time zone observes daylight saving, calendar billing SHALL preserve local commercial boundaries correctly.

A local billing day may contain:

```text
23 hours
```

or:

```text
25 hours
```

without ceasing to be one commercial calendar day.

---

# 41. DST and usage

Usage windows SHALL be converted into unambiguous instants before event attribution.

Ambiguous/repeated local times SHALL be handled deterministically.

---

# 42. Proration

Proration determines the monetary treatment of a partial service period.

It SHALL be explicit policy.

---

# 43. Proration triggers

Potential triggers include:

```text
mid-period activation

mid-period termination

plan change

billing-anchor change

suspension

resumption

price amendment
```

Not every trigger necessarily results in proration.

---

# 44. ProrationPolicy

Conceptually:

```text
ProrationPolicy
────────────────────────

policy_id
version

basis

rounding_policy

component_scope

minimum_charge_rule

effective_from
```

---

# 45. Proration basis

Supported policies MAY include equivalents of:

```text
NO_PRORATION

ACTUAL_DAYS

CALENDAR_FRACTION

CONTRACT_DEFINED
```

The exact canonical vocabulary SHALL be explicitly defined.

---

# 46. No universal day-count rule

Baobab SHALL NOT assume:

```text
monthly price / 30
```

for every monthly subscription.

---

# 47. Actual-days example

If policy explicitly uses actual calendar days:

```text
Monthly price:
ZAR 3,100

Period:
31 days

Service:
10 days
```

then conceptually:

```text
3100 × 10 / 31
```

subject to monetary rounding policy.

---

# 48. Calendar-fraction policy

A contract MAY define a commercial fraction independent of actual day count.

Such a policy SHALL be explicit and versioned.

---

# 49. Component-specific proration

Proration MAY differ by pricing component.

For example:

```text
Recurring fee:
prorated

Setup fee:
not prorated

Usage:
rated from actual consumption
```

---

# 50. Usage is not recurring-fee proration

Usage-based charges SHALL ordinarily arise from actual rated consumption.

They SHALL NOT be treated as prorated recurring fees.

---

# 51. Allowance proration

Included usage allowances MAY require proration.

Example:

```text
Full month:
100,000 requests

half-period:
50,000 requests
```

only where the applicable policy explicitly requires it.

---

# 52. No automatic allowance proration

Baobab SHALL NOT assume that partial service automatically means partial allowance.

Some contracts may grant the full allowance.

---

# 53. Proration precision

Proration calculations SHALL use exact decimal arithmetic.

Binary floating point SHALL NOT determine authoritative monetary results.

---

# 54. Rounding policy

Every monetary proration policy SHALL define deterministic rounding.

It SHALL specify:

- intermediate precision;
- final currency precision;
- rounding mode;
- calculation stage.

---

# 55. Round once where possible

The implementation SHOULD avoid repeated intermediate monetary rounding where doing so would introduce systematic drift.

The applicable policy remains authoritative.

---

# 56. Proration provenance

Every prorated financial result SHALL be explainable.

The engine SHOULD preserve:

```text
full-period amount

full-period boundaries

partial-service boundaries

proration policy

fraction/basis

unrounded result

rounded result
```

where required for audit.

---

# 57. Mid-period activation

Example:

```text
BillingPeriod:
01 Sep → 01 Oct

Activation:
16 Sep
```

Possible policy:

```text
ServicePeriod:
16 Sep → 01 Oct

Recurring charge:
prorated
```

The result SHALL derive from explicit policy.

---

# 58. Mid-period cancellation

Similarly:

```text
BillingPeriod:
01 Sep → 01 Oct

Cancellation:
20 Sep
```

may produce:

```text
ServicePeriod:
01 Sep → 20 Sep
```

if immediate termination and applicable policy require it.

---

# 59. End-of-period cancellation

Where cancellation is effective at period end:

```text
ServicePeriod
=
full BillingPeriod
```

No partial period exists merely because the cancellation request occurred earlier.

---

# 60. Suspension interval

A suspension can divide a BillingPeriod:

```text
ACTIVE
01–10

SUSPENDED
10–20

ACTIVE
20–30
```

Billing policy determines whether the suspended interval is:

- billable;
- non-billable;
- prorated;
- contract-specific.

---

# 61. Multiple segments

A single BillingPeriod MAY therefore contain several effective commercial segments.

Conceptually:

```text
BillingPeriod
     │
     ├── Segment A
     │    Terms v1
     │
     ├── Segment B
     │    Suspended
     │
     └── Segment C
          Terms v2
```

---

# 62. Temporal segmentation

Whenever financially relevant terms change inside a BillingPeriod, Baobab SHALL segment the period sufficiently to preserve correct calculation.

---

# 63. Segment immutability

Once financially used, historical segments SHALL not be destructively rewritten.

Corrections use explicit adjustment semantics.

---

# 64. Plan change example

```text
Billing Period
01 Sep ─────────────────── 01 Oct
          │
          │ 15 Sep
          ▼
       Plan Change
```

The period becomes:

```text
Segment A
01 Sep → 15 Sep
Plan A

Segment B
15 Sep → 01 Oct
Plan B
```

Each segment uses its applicable terms.

---

# 65. Classification transition segmentation

An INTERNAL → COMMERCIAL transition inside a period SHALL similarly establish a boundary.

Example:

```text
01 Sep → 15 Sep
INTERNAL
payable = zero

15 Sep → 01 Oct
COMMERCIAL
commercial billing
```

Valid INTERNAL history SHALL not become retrospectively billable.

---

# 66. COMMERCIAL → INTERNAL segmentation

Likewise:

```text
01 Sep → 15 Sep
COMMERCIAL

15 Sep → 01 Oct
INTERNAL
```

Existing valid commercial obligations before the boundary remain legitimate.

---

# 67. Price-change segmentation

Where price changes mid-period:

```text
Segment A
price v1

Segment B
price v2
```

The invoice calculation SHALL retain both price-version references.

---

# 68. Payer changes

A payer transition MAY occur inside a billing period.

Billing responsibility SHALL follow ADR-SUB-0011.

Where necessary, financial obligations SHALL be segmented or otherwise represented so that responsibility remains unambiguous.

---

# 69. Billing period versus invoice grouping

One BillingPeriod does not necessarily equal one invoice.

For example:

```text
several BillingPeriods
        │
        ▼
single consolidated invoice
```

may be permitted.

Likewise a billing period may generate adjustments appearing on a later invoice.

---

# 70. Invoice date

Invoice issuance time SHALL be distinct from BillingPeriod end.

Example:

```text
BillingPeriod ends:
30 Sep

Invoice issued:
03 Oct
```

---

# 71. Payment due date

Payment due date SHALL derive from payment terms, not from BillingPeriod semantics.

Example:

```text
Invoice issued:
03 Oct

Net 30

Due:
02 Nov
```

or the appropriate contractually calculated date.

---

# 72. Billing in advance

Recurring charges MAY be billed in advance.

Example:

```text
Invoice on 01 Sep

Service period:
01 Sep → 01 Oct
```

---

# 73. Billing in arrears

Charges MAY instead be billed in arrears.

Example:

```text
Service:
01 Sep → 01 Oct

Invoice:
03 Oct
```

---

# 74. Mixed timing

A subscription MAY contain:

```text
recurring fee:
in advance

usage:
in arrears
```

The temporal model SHALL support both simultaneously.

---

# 75. Billing timing belongs to component

Billing timing MAY therefore be a property of a pricing component rather than the entire subscription.

---

# 76. Future periods

Future BillingPeriods MAY be deterministically derivable.

The system SHALL NOT need to persist an unlimited future calendar in advance.

---

# 77. Materialisation

Billing periods MAY be materialised when required for:

- billing execution;
- scheduling;
- forecasting;
- lifecycle transition;
- reconciliation.

The implementation SHALL preserve deterministic generation.

---

# 78. Idempotent period creation

Repeated attempts to materialise the same period SHALL converge on the same logical BillingPeriod.

---

# 79. Period locking

Once a BillingPeriod has produced final financial consequences, its financially relevant inputs SHOULD be treated as immutable except through controlled adjustment/correction workflows.

---

# 80. Open period

An open period MAY continue accepting eligible usage and lifecycle segmentation.

---

# 81. Closing period

Period closure SHALL verify required billing inputs.

Conceptually:

```text
OPEN
 │
 ▼
CLOSING
 │
 ├── usage cut-off
 ├── rating complete
 ├── lifecycle segments complete
 ├── pricing resolved
 └── reconciliation checks
 │
 ▼
CLOSED
```

---

# 82. Closed period

A closed period represents a stable historical billing interval.

Late facts SHALL not silently mutate it.

They SHALL enter controlled adjustment/reopening policy.

---

# 83. Reopening

Reopening a financially closed period SHOULD be exceptional.

Where supported, it SHALL be:

- authorised;
- reasoned;
- audited;
- revisioned.

Prefer adjustments over destructive rewriting.

---

# 84. Temporal corrections

A correction to an effective date SHALL preserve:

```text
original effective time

corrected effective time

reason

authority

affected calculations

resulting adjustments
```

---

# 85. Clock source

Production billing SHALL rely on trusted server/platform clock sources.

Client-provided `now` SHALL never be authoritative.

---

# 86. Clock skew

Distributed services SHOULD tolerate bounded clock skew for technical processing.

Clock-skew tolerance SHALL NOT alter contractual effective times.

---

# 87. Database time

Database timestamps MAY assist persistence ordering.

They SHALL NOT replace domain effective-time semantics.

---

# 88. Kill Bill boundary

Kill Bill may implement:

- billing alignment;
- billing cycle dates;
- plan phases;
- invoice scheduling.

Those provider features MAY be used where semantically compatible.

However:

```text
Kill Bill billing calendar
        !=
canonical Baobab temporal model
```

---

# 89. Provider translation

The adapter SHALL translate:

```text
Baobab BillingTerms
+
BillingAnchor
+
BillingCalendar
+
BillingPeriod policy
        │
        ▼
Kill Bill configuration
```

where required.

---

# 90. Provider incompatibility

If Kill Bill cannot represent a required Baobab temporal policy exactly, the adapter SHALL NOT silently approximate it.

The system SHALL:

- retain calculation in Baobab;
- compose supported provider functionality;
- or mark the configuration unsupported/blocked.

---

# 91. Provider temporal drift

Reconciliation SHALL detect material drift such as:

```text
wrong billing anchor

wrong billing cycle day

wrong plan effective date

wrong suspension boundary

wrong cancellation date
```

---

# 92. Payments boundary

Billing periods determine when obligations arise.

They SHALL NOT determine whether a payment processor successfully collects them.

That belongs to `baobab-payments`.

---

# 93. ERP boundary

Billing period is not accounting period.

ERP MAY apply separate accounting-period and revenue-recognition rules.

Therefore:

```text
BillingPeriod
      !=
AccountingPeriod
```

---

# 94. Revenue recognition

Subscriptions SHALL communicate sufficient canonical financial facts for ERP to perform accounting.

Subscriptions SHALL NOT infer accounting treatment solely from billing calendar.

---

# 95. Reconciliation

Temporal reconciliation SHALL compare:

```text
BillingTerms
     │
     ▼
Expected Periods
     │
     ▼
Materialised Periods
     │
     ▼
Lifecycle Segments
     │
     ▼
Provider State
```

---

# 96. Reconciliation anomalies

At minimum:

```text
MISSING_PERIOD

DUPLICATE_PERIOD

OVERLAPPING_PERIOD

UNINTENDED_GAP

WRONG_ANCHOR

WRONG_TIMEZONE

STALE_TERMS

UNAPPLIED_TRANSITION

PROVIDER_TEMPORAL_DRIFT
```

or canonical equivalents.

---

# 97. Automatic repair

Auto-repair MAY generate a missing deterministic period where:

- authoritative terms are unambiguous;
- no financial conflict exists;
- operation is idempotent.

---

# 98. Manual review

Manual review SHALL be required where:

- periods overlap with financial history;
- competing anchors exist;
- historical effective dates conflict;
- payer responsibility is ambiguous;
- provider and Baobab both contain inconsistent billed history.

---

# 99. Observability

Subscriptions SHOULD expose telemetry for:

- periods opened;
- periods closed;
- period-generation failures;
- overlapping-period detection;
- gap detection;
- late usage;
- proration operations;
- proration failures;
- scheduled transitions;
- temporal reconciliation drift;
- provider anchor drift.

---

# 100. Security and controlled mutation

Billing-anchor and effective-date changes are financially privileged.

ADR-SUB-0016 SHALL govern authorization and audit.

A tenant SHALL NOT be able to manipulate:

```text
effective_at

period_start

period_end

billing_anchor
```

merely to reduce charges.

---

# 101. Audit

For material temporal decisions, Baobab SHOULD be able to answer:

```text
Which billing period?

Which anchor?

Which calendar?

Which time zone?

Which BillingTerms?

Which lifecycle segments?

Which proration policy?

Which effective times?

What changed?

Who/what authorised it?
```

---

# 102. Domain invariants

### INV-TIME-01

BillingPeriod, ServicePeriod, UsageWindow, InvoiceDate and PaymentDueDate are distinct concepts.

### INV-TIME-02

Recurring calendar periods are not represented as universal fixed-second durations.

### INV-TIME-03

Monthly billing uses explicit calendar semantics.

### INV-TIME-04

Annual billing does not universally mean 365 days.

### INV-TIME-05

Billing periods use unambiguous boundaries.

### INV-TIME-06

Half-open `[start,end)` intervals are preferred for period attribution.

### INV-TIME-07

Adjacent uninterrupted periods do not overlap.

### INV-TIME-08

Unintended billing gaps are prohibited.

### INV-TIME-09

Usage is attributed by authoritative occurrence/effective time rather than arrival time.

### INV-TIME-10

Late usage does not silently move into a different consumption period.

### INV-TIME-11

Time zone is explicit where local commercial calendar semantics matter.

### INV-TIME-12

Market does not implicitly determine billing time zone.

### INV-TIME-13

Proration is explicit policy.

### INV-TIME-14

Monthly proration does not universally mean price divided by 30.

### INV-TIME-15

Usage billing is distinct from recurring-fee proration.

### INV-TIME-16

Allowance proration is explicit rather than assumed.

### INV-TIME-17

Financially material mid-period changes create temporal segmentation.

### INV-TIME-18

Historical segments are preserved.

### INV-TIME-19

Classification transitions preserve their effective temporal boundary.

### INV-TIME-20

Payer transitions cannot create ambiguous financial responsibility.

### INV-TIME-21

Period creation is idempotent.

### INV-TIME-22

Closed periods cannot be silently destructively rewritten.

### INV-TIME-23

Provider temporal configuration never becomes canonical Baobab authority.

### INV-TIME-24

BillingPeriod does not equal AccountingPeriod.

### INV-TIME-25

Client-supplied current time is never authoritative.

### INV-TIME-26

Historical billing can be reconstructed from its temporal inputs.

---

# 103. Alternatives considered

## 103.1 Treat every month as 30 days

**Rejected.**

Calendar months vary.

## 103.2 Represent annual billing as 365 days

**Rejected.**

Leap years and contractual calendar anniversaries invalidate the assumption.

## 103.3 Use UTC exclusively for all commercial calendar rules

**Rejected as a universal rule.**

UTC is appropriate for unambiguous instants, but commercial periods may require named local-calendar semantics.

## 103.4 Infer time zone from market

**Rejected.**

Market and billing time zone are separate dimensions.

## 103.5 Attribute usage by ingestion time

**Rejected.**

Late delivery would misstate consumption periods.

## 103.6 Always prorate partial periods

**Rejected.**

Proration is contractual policy.

## 103.7 Always divide monthly price by 30

**Rejected.**

It imposes an unjustified commercial calculation.

## 103.8 Let Kill Bill define Baobab's canonical billing calendar

**Rejected.**

It would create provider lock-in.

## 103.9 Mutate closed periods when late data arrives

**Rejected.**

Historical financial facts require controlled adjustment semantics.

---

# 104. Consequences

## Positive

- Calendar billing becomes deterministic.
- Month-length and leap-year errors are avoided.
- Multi-market time semantics remain explicit.
- Usage attribution becomes reliable.
- Mid-cycle lifecycle changes can be calculated correctly.
- Proration becomes auditable.
- Historical charges become reproducible.
- Kill Bill remains replaceable.
- Invoice generation receives stable temporal inputs.

## Negative

- Time handling becomes a first-class domain concern.
- Period segmentation increases implementation complexity.
- Late usage requires adjustment policy.
- Calendar testing becomes extensive.
- Provider temporal mappings require reconciliation.

These costs are accepted because time errors in recurring billing directly create monetary errors.

---

# 105. Implementation requirements

A conforming implementation SHALL provide:

1. BillingAnchor;
2. BillingCalendar;
3. BillingPeriod;
4. ServicePeriod;
5. UsageWindow;
6. explicit billing time zone;
7. calendar-aware recurrence;
8. end-of-month handling;
9. leap-year handling;
10. deterministic interval semantics;
11. effective-time attribution;
12. late-usage handling;
13. explicit ProrationPolicy;
14. exact monetary proration;
15. lifecycle segmentation;
16. classification segmentation;
17. price-version segmentation;
18. durable period materialisation;
19. idempotent period creation;
20. period closure;
21. controlled correction;
22. provider temporal mapping;
23. temporal reconciliation;
24. audit and observability.

---

# 106. Required tests

At minimum:

### Calendar

- 28-day February;
- leap-year February;
- 30-day month;
- 31-day month;
- January 31 anchor;
- annual leap-date anniversary;
- quarterly recurrence.

### Boundaries

- event exactly at period start;
- event immediately before period end;
- event exactly at period end;
- adjacent periods;
- overlap rejection;
- unintended-gap detection.

### Time zones

- explicit local calendar;
- UTC conversion;
- DST forward transition;
- DST backward transition;
- market differing from billing time zone.

### Usage

- usage in current window;
- late-arriving usage;
- duplicate usage;
- usage exactly at boundary;
- closed-period late usage.

### Proration

- mid-period activation;
- mid-period termination;
- no-proration policy;
- actual-days policy;
- allowance proration;
- allowance non-proration;
- monetary rounding.

### Lifecycle

- mid-cycle plan change;
- suspension;
- resumption;
- INTERNAL → COMMERCIAL;
- COMMERCIAL → INTERNAL;
- billing-anchor change.

### Provider

- correct anchor mapping;
- provider anchor drift;
- provider cancellation-date drift;
- unsupported temporal policy.

---

# 107. Recommended implementation sequence

```text
1. Canonical temporal primitives
          │
          ▼
2. BillingAnchor
          │
          ▼
3. BillingCalendar
          │
          ▼
4. BillingPeriod generation
          │
          ▼
5. UsageWindow attribution
          │
          ▼
6. ServicePeriod segmentation
          │
          ▼
7. ProrationPolicy
          │
          ▼
8. Lifecycle integration
          │
          ▼
9. Period closure
          │
          ▼
10. Late-data handling
          │
          ▼
11. Provider mapping
          │
          ▼
12. Temporal reconciliation
```

---

# 108. Architectural progression

The early subscription architecture now reads coherently:

```text
ADR-SUB-0002
Domain and authority
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
        │
        ▼
ADR-SUB-0008
Billing periods,
calendars and time
        │
        ▼
later decisions
Charges / invoices /
obligations / payments
```

---

# 109. Example: mid-month plan transition

Consider:

```text
Billing Period:
01 Sep → 01 Oct

Plan A:
ZAR 1,000/month

Plan transition:
15 Sep

Plan B:
ZAR 1,500/month
```

The temporal model first produces:

```text
BillingPeriod
01 Sep ───────────────────── 01 Oct
             │
             ▼
           15 Sep

Segment A:
[01 Sep, 15 Sep)
Plan A

Segment B:
[15 Sep, 01 Oct)
Plan B
```

Only then does the applicable ProrationPolicy calculate monetary consequences.

The engine does not begin with an arbitrary monetary formula and attempt to reconstruct the temporal facts afterwards.

---

# 110. Example: usage crossing a plan transition

```text
10 Sep
100 API calls
Plan A

15 Sep
Plan A → Plan B

20 Sep
200 API calls
Plan B
```

The system retains:

```text
100 calls
    │
    ▼
Plan A rating terms

200 calls
    │
    ▼
Plan B rating terms
```

even if all usage is processed on 2 October.

---

# 111. Example: INTERNAL to COMMERCIAL

```text
Billing Period
01 Sep ───────────────────── 01 Oct
             │
             ▼
           15 Sep

01–15 Sep:
INTERNAL

15 Sep–01 Oct:
COMMERCIAL
```

The temporal segmentation yields:

```text
Segment A
classification = INTERNAL
monetary treatment = ZERO

Segment B
classification = COMMERCIAL
monetary treatment = PRICED
```

The September invoice therefore cannot accidentally commercialise the first half of the month merely because the subscription was COMMERCIAL when the invoice was generated.

---

# 112. Final Decision

Baobab SHALL treat **time as an explicit billing-domain dimension rather than an implementation detail**.

The principal period rule is:

> **A billing frequency has no safe financial meaning without an explicit anchor, calendar, interval and effective-time policy.**

The principal calendar rule is:

> **Calendar months and years are calendar concepts, not fixed numbers of seconds or days.**

The principal boundary rule is:

> **Billing periods, service periods, usage windows, invoice dates and payment due dates are separate temporal concepts and must not be conflated.**

The principal usage rule is:

> **Usage belongs to the commercial period in which it authoritatively occurred, not whichever period happened to be open when the event arrived.**

The principal proration rule is:

> **Proration is an explicit, versioned commercial policy; Baobab does not universally divide monthly prices by 30 or automatically prorate every partial period.**

The principal lifecycle rule is:

> **Any financially meaningful change within a billing period creates an explicit temporal boundary sufficient to preserve the terms applicable on each side of that boundary.**

The principal historical rule is:

> **Closed financial periods are historical facts. Late data and corrections produce governed adjustments or revisions rather than silent destructive rewriting.**

And the principal provider rule is:

> **Kill Bill may execute provider-specific billing-calendar behaviour, but Baobab's canonical temporal semantics remain independent of Kill Bill.**