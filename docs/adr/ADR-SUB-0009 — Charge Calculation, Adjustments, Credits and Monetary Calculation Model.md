# ADR-SUB-0009 — Charge Calculation, Adjustments, Credits and Monetary Calculation Model

**Status:** Accepted\
**Date:** 2026-09-25\
**Decision Type:** Architecture / Billing Calculation / Monetary Facts\
**Repository:** `baobab-platform/baobab-subscriptions`\
**Scope:** Baobab Platform\
**Owners:** Baobab Platform Architecture\
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
- ADR-SUB-0011 — Billing Account, Payer, Invoice Recipient and Financial Responsibility Model
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts

---

# 1. Context

The preceding decisions establish:

```text
ProductSubscription
        │
        ▼
BillingSubscriptionProjection
        │
        ├── Classification
        ├── BillingTerms
        ├── PricingPlan
        ├── Usage / RatedUsage
        ├── BillingPeriod
        └── Temporal Segments
```

The platform must next transform those resolved billing inputs into monetary facts.

For example:

```text
Subscription
    │
    ├── recurring fee = ZAR 1,000
    │
    ├── usage = ZAR 275
    │
    └── service credit = -ZAR 100
    │
    ▼
?
```

Before an invoice can exist, Baobab needs an authoritative answer to:

> What monetary billing facts were calculated, from which inputs, under which terms, and why?

That answer cannot simply be:

```text
invoice = 1175
```

because doing so loses:

- pricing provenance;
- charge identity;
- billing period;
- rated usage lineage;
- proration;
- credits;
- adjustments;
- classification;
- tax basis;
- currency;
- correction history.

A charge also must not be confused with payment.

```text
Charge
    !=
Payment
```

Nor with accounting:

```text
Charge
    !=
LedgerEntry
```

Nor with an invoice:

```text
Charge
    !=
Invoice
```

This ADR therefore defines Baobab's canonical monetary calculation layer.

---

# 2. Decision

`baobab-subscriptions` SHALL own the creation and lifecycle of subscription billing **Charges**, **Credits**, and **Adjustments**.

These objects SHALL represent explainable monetary billing facts derived from authoritative billing inputs.

The canonical flow is:

```text
Pricing Terms
     │
     ├──────────────┐
     │              │
     ▼              ▼
Recurring       Rated Usage
Components
     │              │
     └──────┬───────┘
            │
            ▼
     Temporal Segments
            │
            ▼
    Charge Calculation
            │
     ┌──────┼───────┐
     ▼      ▼       ▼
  Charge  Credit  Adjustment
     │      │       │
     └──────┼───────┘
            ▼
     InvoiceProjection
            │
            ▼
   Financial Obligation
```

---

# 3. Charge definition

A `Charge` is an immutable or append-oriented monetary billing fact representing an amount calculated under a specific billing policy and set of commercial inputs.

Conceptually:

```text
Charge
────────────────────────

charge_id

billing_subscription_id
billing_account_id

charge_type

billing_period_reference
service_period_reference

pricing_plan_reference
pricing_version
billing_terms_reference

classification_reference
classification_revision

currency

quantity
unit_price
amount

calculation_reference

effective_at

status

created_at
```

The exact schema MAY evolve.

The semantic boundary SHALL remain.

---

# 4. Charge is not an invoice

A Charge answers:

> What amount was calculated and why?

An InvoiceProjection answers:

> Which calculated monetary facts were presented together as a billing document/obligation?

Therefore:

```text
Charge A ─┐
Charge B ─┼──► InvoiceProjection
Credit C ─┘
```

The charge exists conceptually before invoice grouping.

---

# 5. Charge is not payment

A Charge establishes a billing amount.

It does not prove money moved.

```text
Charge
   │
   ▼
Invoice / Obligation
   │
   ▼
baobab-payments
   │
   ▼
Payment
```

---

# 6. Charge is not accounting

A Charge is not:

- journal entry;
- accounts-receivable entry;
- revenue-recognition event;
- general-ledger posting.

Those consequences belong to ERP.

---

# 7. Charge types

Baobab SHALL support extensible charge categories equivalent to:

```text
RECURRING

USAGE

ONE_TIME

SETUP

PRORATION

ADJUSTMENT
```

The exact canonical vocabulary SHALL reside in Shared where cross-engine contracts require it.

---

# 8. Recurring charge

A recurring charge arises from a recurring pricing component.

Example:

```text
Base platform subscription
ZAR 1,000 / month
```

produces a recurring billing fact for the applicable period or segment.

---

# 9. Usage charge

A UsageCharge SHALL be derived from RatedUsage.

```text
UsageRecord
     │
     ▼
Rating
     │
     ▼
RatedUsage
     │
     ▼
UsageCharge
```

Raw usage SHALL NOT be transformed directly into a payable amount without the applicable rating process.

---

# 10. One-time charge

A one-time charge MAY represent an explicitly authorised non-recurring commercial component.

Examples MAY include:

- onboarding;
- setup;
- migration;
- contractually agreed one-time service.

It SHALL have explicit pricing provenance.

---

# 11. Setup charge

A setup charge is not automatically implied by subscription creation.

It exists only where BillingTerms explicitly establish it.

---

# 12. Proration charge

A ProrationCharge represents a monetary consequence calculated from the ProrationPolicy established by ADR-SUB-0008.

It SHALL preserve sufficient calculation provenance to reproduce the result.

---

# 13. Credit

A `Credit` represents a reduction in an amount otherwise payable or an amount available to offset eligible billing obligations.

Conceptually:

```text
Credit
────────────────────────

credit_id

billing_account_id
billing_subscription_id?

credit_type

currency

amount

source_reference

reason_code

effective_at

remaining_amount?

status
```

---

# 14. Credits are explicit

Baobab SHALL NOT represent credits by silently modifying historical charge amounts.

Example:

```text
Original Charge
ZAR 1,000
```

followed by:

```text
Service Credit
ZAR 100
```

SHALL preserve both facts.

---

# 15. Credit sign convention

The domain SHALL adopt one consistent monetary-sign convention.

For example, the implementation MAY represent:

```text
Charge amount = +1000

Credit amount = +100
```

with semantic type determining reduction,

rather than relying on arbitrary negative numbers.

The chosen representation SHALL be consistent across APIs, persistence and events.

---

# 16. Credit categories

Credits MAY include equivalents of:

```text
SERVICE_CREDIT

COMMERCIAL_CREDIT

CORRECTION_CREDIT

GOODWILL_CREDIT

PRORATION_CREDIT
```

where permitted by policy.

---

# 17. Promotional discounts are not automatically credits

A discount encoded directly in PricingPlan evaluation is normally part of charge calculation.

A separately granted value after or outside pricing evaluation may be represented as a Credit.

The distinction SHALL be explicit.

---

# 18. Adjustment

An `Adjustment` represents a controlled correction or alteration to a previously calculated monetary fact.

Conceptually:

```text
Adjustment
────────────────────────

adjustment_id

target_charge_id
target_invoice_reference?

adjustment_type

currency

amount

reason_code

authority_reference

effective_at

created_at
```

---

# 19. Adjustment does not rewrite history

If:

```text
Charge C1
ZAR 1,000
```

was later determined to be ZAR 900, Baobab SHALL NOT ordinarily mutate C1 to 900 after it has become financially material.

Instead:

```text
Charge C1
+ ZAR 1,000

Adjustment A1
- ZAR 100
```

preserves the history.

---

# 20. Append-oriented financial history

Once a Charge has entered a financially consequential state, correction SHALL be append-oriented.

Conceptually:

```text
Original Fact
     │
     ▼
Correction Decision
     │
     ▼
Adjustment / Credit
```

rather than:

```text
Original Fact
     │
     X
overwrite
```

---

# 21. Draft calculation

The engine MAY compute provisional calculation results before they become durable Charges.

These provisional results SHALL NOT be confused with final billing facts.

---

# 22. Calculation lifecycle

A conceptual lifecycle MAY include:

```text
CALCULATED
     │
     ▼
VALIDATED
     │
     ▼
COMMITTED
     │
     ▼
INVOICED
```

with separate states for:

```text
VOIDED
ADJUSTED
```

where needed.

The exact vocabulary MAY evolve.

---

# 23. Calculation determinism

Given identical:

- authoritative subscription state;
- BillingTerms;
- pricing version;
- classification;
- usage;
- temporal segment;
- proration policy;

the engine SHALL produce the same monetary result.

---

# 24. Calculation provenance

Every Charge SHALL retain enough provenance to answer:

```text
Which subscription?

Which billing account?

Which billing period?

Which service interval?

Which pricing plan?

Which pricing version?

Which BillingTerms?

Which classification?

Which usage?

Which quantity?

Which unit price?

Which proration policy?

Which currency?

Which calculation rule?
```

as applicable.

---

# 25. Calculation explanation

The platform SHOULD support an explainable representation equivalent to:

```text
Base recurring fee
ZAR 1,000.00

Usage:
250 units × ZAR 2.00
= ZAR 500.00

Service credit:
ZAR 100.00

Net pre-tax billing facts:
ZAR 1,400.00
```

without reconstructing logic from provider state.

---

# 26. Monetary precision

All authoritative monetary calculations SHALL use exact decimal arithmetic.

Binary floating-point arithmetic SHALL NOT be used for authoritative monetary values.

---

# 27. Currency

Every monetary Charge, Credit or Adjustment SHALL carry explicit currency.

No monetary value SHALL rely on implicit tenant or market currency.

---

# 28. No mixed-currency arithmetic

Amounts in different currencies SHALL NOT be directly summed.

```text
ZAR 100
+
UGX 100
```

is invalid arithmetic without an explicit FX operation.

---

# 29. FX boundary

Where currency conversion is required, Baobab SHALL preserve:

- source amount;
- source currency;
- target amount;
- target currency;
- FX rate;
- FX source;
- FX timestamp/effective date;
- rounding rule.

---

# 30. No live FX reconstruction

Historical monetary results SHALL NOT be recalculated using today's FX rate.

The applicable historical FX provenance SHALL be preserved.

---

# 31. Currency precision

Currency-specific minor-unit rules SHALL be explicit.

The engine SHALL not universally assume every currency has exactly two decimal places.

---

# 32. Calculation precision versus display precision

Internal calculation precision MAY exceed currency display precision.

Final monetary results SHALL be rounded according to explicit policy.

---

# 33. Rounding

Rounding SHALL be deterministic.

The policy SHALL specify:

- rounding mode;
- calculation precision;
- rounding stage;
- final currency precision.

---

# 34. Rounding provenance

Where rounding materially affects an amount, the calculation SHOULD remain explainable.

Example:

```text
raw:
333.333333

rounded:
333.33

rule:
specified currency/policy rounding
```

---

# 35. Classification barrier

Before creating a payable Charge, the engine SHALL revalidate the applicable classification-driven BillingDecision from ADR-SUB-0006.

---

# 36. INTERNAL subscriptions

For an INTERNAL subscription:

```text
monetary treatment = ZERO
```

where policy so establishes.

The engine MAY still calculate:

- usage quantities;
- rated shadow values;
- cost analytics;
- allocation metrics;

but SHALL NOT create a collectible commercial obligation.

---

# 37. Shadow monetary value

Where INTERNAL usage is analytically rated, the result SHALL be explicitly non-payable.

It SHALL NOT be represented in a way that can accidentally flow into payment collection.

---

# 38. COMMERCIAL subscriptions

COMMERCIAL subscriptions MAY create priced Charges where all required pricing and billing configuration exists.

---

# 39. Missing commercial pricing

If a COMMERCIAL subscription lacks required pricing:

```text
COMMERCIAL
+
pricing missing
```

SHALL NOT become:

```text
Charge = 0
```

by default.

It SHALL remain blocked/pending configuration.

---

# 40. Legitimate zero charge

A COMMERCIAL Charge MAY legitimately evaluate to zero.

Examples:

- trial;
- full discount;
- zero consumption;
- full credit;
- contractually zero-priced component.

The zero SHALL retain its provenance.

---

# 41. Zero is not missing

Baobab SHALL distinguish:

```text
amount = 0
```

from:

```text
amount unresolved
```

These are semantically different.

---

# 42. Charge calculation inputs

Conceptually:

```text
Charge =
f(
  BillingTerms,
  PricingComponent,
  Classification,
  BillingPeriod,
  ServiceSegment,
  RatedUsage,
  ProrationPolicy,
  CurrencyPolicy
)
```

where applicable.

---

# 43. Pricing component isolation

Each pricing component SHOULD generate independently traceable monetary facts.

For example:

```text
Platform base fee
       │
       ▼
Charge C1

Storage usage
       │
       ▼
Charge C2

API usage
       │
       ▼
Charge C3
```

This improves explainability and adjustment safety.

---

# 44. No opaque total-only calculation

The engine SHALL NOT retain only:

```text
invoice total = ZAR 4,735.20
```

while discarding the components that created it.

---

# 45. Quantity

Where a charge depends on quantity, the quantity SHALL retain:

- unit;
- value;
- metric reference where applicable.

---

# 46. Unit-price provenance

Unit price SHALL reference the pricing rule/version that produced it.

It SHALL not be accepted as arbitrary untrusted caller input for protected billing calculations.

---

# 47. Usage tiers

For tiered usage:

```text
0–100 units:
ZAR 1/unit

101–500:
ZAR 0.80/unit
```

the calculation SHOULD preserve tier-level explanation where necessary.

---

# 48. Tier calculation

A tiered result MAY therefore retain:

```text
Tier 1
100 × 1.00
= 100

Tier 2
150 × 0.80
= 120

Total
= 220
```

rather than only storing 220 without provenance.

---

# 49. Volume pricing

Volume pricing SHALL remain distinct from graduated tier pricing where the commercial model distinguishes them.

---

# 50. Minimum charge

Pricing MAY define a minimum charge.

Example:

```text
calculated usage:
ZAR 75

minimum:
ZAR 100

charge:
ZAR 100
```

The minimum-charge rule SHALL be explicit in the calculation provenance.

---

# 51. Maximum charge

Pricing MAY define caps.

Example:

```text
calculated:
ZAR 12,000

monthly cap:
ZAR 10,000

charge:
ZAR 10,000
```

The cap SHALL be explicit.

---

# 52. Allowances

Included allowances SHALL be applied before overage charges where pricing policy requires.

Example:

```text
usage:
1,500

included:
1,000

billable:
500
```

---

# 53. Allowance provenance

The calculation SHALL preserve:

- measured quantity;
- allowance;
- consumed allowance;
- billable remainder.

---

# 54. Credits versus allowances

An allowance reduces billable quantity according to pricing terms.

A Credit reduces monetary obligation.

They SHALL not be conflated.

---

# 55. Discounts

Discounts MAY be:

- percentage;
- fixed amount;
- component-specific;
- period-specific;
- contract-specific.

They SHALL be deterministic and versioned.

---

# 56. Discount ordering

Where several modifiers apply, their order SHALL be explicit.

For example:

```text
base price
   │
   ▼
volume rule
   │
   ▼
contract discount
   │
   ▼
proration
```

may produce a different result from another order.

Baobab SHALL not leave ordering implicit.

---

# 57. Calculation pipeline

A canonical calculation pipeline SHOULD conceptually resemble:

```text
Resolve BillingDecision
        │
        ▼
Resolve BillingTerms
        │
        ▼
Resolve Period / Segment
        │
        ▼
Resolve Pricing Component
        │
        ▼
Resolve Quantity / RatedUsage
        │
        ▼
Apply Allowance
        │
        ▼
Apply Pricing Function
        │
        ▼
Apply Modifiers
        │
        ▼
Apply Proration
        │
        ▼
Apply Monetary Rounding
        │
        ▼
Create Charge
```

The exact ordering of policy-specific stages SHALL be defined by the applicable PricingPlan.

---

# 58. Tax boundary

Tax calculation is financially significant and SHALL not be hidden inside an unexplained total.

The charge model SHALL preserve sufficient pre-tax monetary facts for applicable tax processing.

---

# 59. Charge and tax distinction

Conceptually:

```text
Charge
pre-tax monetary fact
      │
      ▼
Tax determination/calculation
      │
      ▼
Invoice monetary presentation
```

The precise tax architecture MAY be governed by a dedicated ADR.

---

# 60. Tax-inclusive pricing

Where a price is tax-inclusive, the engine SHALL preserve enough information to distinguish:

```text
gross amount

tax component

net amount
```

once tax policy is applied.

---

# 61. Tax-exclusive pricing

Where pricing is tax-exclusive:

```text
Charge
+
Tax
=
Invoice amount
```

subject to the applicable tax decision.

---

# 62. Charge finality

A Charge SHOULD become financially final for its calculation revision once consumed by an issued invoice or equivalent downstream financial process.

Corrections thereafter SHOULD use adjustment mechanisms.

---

# 63. Charge revision

Before financial finality, recalculation MAY replace a provisional calculation where safely controlled.

After financial finality, append-oriented corrections are preferred.

---

# 64. Recalculation

Recalculation SHALL be deterministic and revision-aware.

It SHALL identify what changed:

```text
usage changed

pricing changed

effective date corrected

classification corrected

proration corrected
```

---

# 65. Recalculation does not automatically alter invoices

If a Charge has already been invoiced, recalculation SHALL NOT silently rewrite the issued InvoiceProjection.

It SHALL create the appropriate correction/adjustment workflow.

---

# 66. Credit application

A Credit MAY be:

```text
subscription-specific

billing-account-wide

invoice-specific

contract-specific
```

according to its scope.

Scope SHALL be explicit.

---

# 67. Credit eligibility

A Credit SHALL only apply to eligible obligations.

For example, a ZAR credit SHALL not automatically offset a UGX obligation.

---

# 68. Credit balance

Where reusable credits are supported, the engine SHALL track:

```text
original credit

applied amount

remaining amount
```

without double application.

---

# 69. Credit application idempotency

Repeated processing SHALL NOT consume the same credit twice.

---

# 70. Credit expiry

If credits can expire, expiry semantics SHALL be explicit and effective-dated.

---

# 71. Refund versus credit

A Credit is not necessarily a Refund.

```text
Credit
    │
    ▼
reduces billing obligation
```

whereas:

```text
Refund
    │
    ▼
returns previously transferred money
```

Refund execution belongs to the payment domain.

---

# 72. Adjustment versus refund

An Adjustment changes the billing fact.

A Refund moves money.

Both may arise from the same correction but remain distinct.

---

# 73. Example correction

```text
Original invoice:
ZAR 1,000

Correct amount:
ZAR 800

Billing consequence:
Credit/Adjustment = ZAR 200

Payment consequence,
if ZAR 1,000 already paid:
possible Refund = ZAR 200
```

The billing and payment operations SHALL remain separate.

---

# 74. Debit adjustment

A correction MAY increase an amount owed.

Example:

```text
Original:
ZAR 1,000

Correct:
ZAR 1,200

Debit adjustment:
ZAR 200
```

Such adjustments SHALL be explicitly authorised and auditable.

---

# 75. No silent retroactive debit

A retroactive price correction SHALL not silently increase a customer's historical obligation without satisfying the applicable commercial and governance rules.

---

# 76. Reason codes

Credits and Adjustments SHALL carry stable reason codes.

Examples MAY include:

```text
PRICING_CORRECTION

USAGE_CORRECTION

SERVICE_CREDIT

PRORATION_CORRECTION

CONTRACT_ADJUSTMENT

DUPLICATE_CHARGE_CORRECTION
```

The vocabulary SHOULD be canonical.

---

# 77. Free-text reason

Free text MAY supplement a reason code.

It SHALL NOT replace structured reason semantics.

---

# 78. Authority

Financial adjustments SHALL record the authority under which they were made.

They SHALL not be arbitrary data edits.

---

# 79. Separation of duties

High-value or exceptional Credits/Adjustments MAY require approval according to platform policy.

For example:

```text
Actor A
requests ZAR 100,000 credit

Actor B
approves
```

where applicable.

---

# 80. Controlled mutation

The engine SHALL prefer domain commands such as:

```text
GrantCredit

CreateBillingAdjustment

CorrectUsageCharge

VoidUninvoicedCharge

RecalculateBillingPeriod
```

over unrestricted financial CRUD.

---

# 81. No arbitrary amount PATCH

The following SHALL NOT be an ordinary operation:

```text
PATCH /charges/{id}

{
  "amount": 1
}
```

after a Charge has become financially meaningful.

---

# 82. Idempotency

Charge, Credit and Adjustment creation SHALL be idempotent.

Stable business keys or operation identities SHALL prevent duplicates.

---

# 83. Charge identity

A Charge SHALL have canonical Baobab identity independent of Kill Bill.

```text
charge_id
```

SHALL not equal or depend upon:

```text
Kill Bill invoice-item ID
```

---

# 84. Provider projection

Where Kill Bill represents equivalent monetary information:

```text
Baobab Charge
      │
      ▼
BillingProvider
      │
      ▼
KillBillAdapter
      │
      ▼
Provider monetary representation
```

The provider representation remains subordinate.

---

# 85. Provider-calculated amounts

Kill Bill MAY calculate certain provider-native billing amounts where the adapter and provider configuration are proven semantically equivalent to Baobab policy.

However, Baobab SHALL retain enough canonical provenance to explain and reconcile the result.

---

# 86. No provider-only monetary truth

A monetary amount SHALL NOT be accepted as canonical merely because Kill Bill produced it.

The system SHALL know which Baobab pricing and billing decision justified the amount.

---

# 87. Provider reconciliation

The system SHALL detect:

```text
missing provider monetary item

duplicate provider item

wrong amount

wrong currency

wrong period

wrong plan

wrong classification consequence
```

where provider comparison is applicable.

---

# 88. INTERNAL provider safeguard

A provider-generated monetary item for an INTERNAL subscription is a severe policy inconsistency.

It SHALL trigger reconciliation/alerting and SHALL NOT automatically become a payable Baobab obligation.

---

# 89. Payments boundary

Subscriptions determines:

```text
what amount is owed
```

Payments determines:

```text
how money is executed
```

Therefore subscriptions SHALL NOT:

- select payment processor directly;
- capture funds directly;
- store card credentials;
- treat processor success as charge authority.

---

# 90. ERP boundary

Subscriptions communicates canonical billing facts.

ERP determines accounting consequences.

Conceptually:

```text
Charge / Credit / Adjustment
          │
          ▼
Canonical Financial Event
          │
          ▼
ERP
          │
          ▼
Receivable / Journal /
Revenue Recognition
```

---

# 91. Charge does not equal receivable

A Charge may not yet be invoiced.

Therefore:

```text
Charge
!=
Accounts Receivable
```

---

# 92. Audit

For every financially material calculation, Baobab SHOULD be able to reconstruct:

```text
input state
    │
    ▼
pricing rule
    │
    ▼
rating/proration
    │
    ▼
rounding
    │
    ▼
resulting monetary fact
```

---

# 93. Calculation record

A durable `CalculationRecord` or equivalent MAY preserve detailed computational provenance separately from the Charge aggregate.

Conceptually:

```text
CalculationRecord
────────────────────────

calculation_id

algorithm_version

input_references

pricing_version

policy_versions

intermediate_values

rounding_policy

result
```

---

# 94. Algorithm version

Where calculation behaviour changes between software releases, financially material results SHOULD be traceable to the calculation algorithm/version that produced them.

---

# 95. Reproducibility

Historical calculation SHALL remain reproducible without relying on:

- current pricing;
- current classification;
- current FX;
- current provider configuration;
- current application defaults.

---

# 96. Calculation fingerprint

The implementation MAY derive a deterministic fingerprint from financially relevant inputs to assist:

- duplicate detection;
- audit;
- reconciliation;
- recalculation comparison.

A fingerprint SHALL not replace canonical identities.

---

# 97. Reconciliation

Monetary reconciliation SHALL compare:

```text
Expected Billing Inputs
          │
          ▼
Expected Calculation
          │
          ▼
Canonical Charge
          │
          ▼
Invoice Projection
          │
          ▼
Provider Representation
```

as applicable.

---

# 98. Reconciliation anomalies

At minimum, detect equivalents of:

```text
MISSING_CHARGE

DUPLICATE_CHARGE

AMOUNT_MISMATCH

CURRENCY_MISMATCH

PRICING_VERSION_MISMATCH

USAGE_MISMATCH

PRORATION_MISMATCH

CLASSIFICATION_POLICY_VIOLATION

ORPHAN_CHARGE

CREDIT_OVERAPPLICATION
```

---

# 99. Auto-repair

Automatic repair MAY occur where:

- authoritative calculation inputs are complete;
- result is deterministic;
- no issued financial document would be silently rewritten;
- repair is idempotent;
- no monetary ambiguity exists.

---

# 100. Manual review

Manual review SHALL be required for conditions including:

- unexplained monetary discrepancy;
- ambiguous historical pricing;
- conflicting FX provenance;
- duplicate invoiced charges;
- INTERNAL payable charge;
- ambiguous credit application;
- cross-currency correction without explicit FX policy.

---

# 101. Observability

Subscriptions SHOULD expose metrics for:

- charges calculated;
- charge calculation failures;
- zero commercial charges;
- blocked commercial calculations;
- credits issued;
- adjustments issued;
- recalculations;
- reconciliation mismatches;
- duplicate-charge prevention;
- INTERNAL monetary-policy violations.

Monetary values SHOULD NOT become high-cardinality uncontrolled metric labels.

---

# 102. Security

Financial calculation and correction operations SHALL comply with ADR-SUB-0016.

Protected mutations SHALL be:

```text
authenticated
+
authorised
+
tenant-scoped
+
policy-valid
+
idempotent
+
revision-aware
+
audited
```

---

# 103. Domain invariants

### INV-CHG-01

A Charge is not an Invoice.

### INV-CHG-02

A Charge is not a Payment.

### INV-CHG-03

A Charge is not a LedgerEntry.

### INV-CHG-04

Every monetary fact has explicit currency.

### INV-CHG-05

Different currencies are never directly summed.

### INV-CHG-06

Authoritative monetary calculation uses exact decimal arithmetic.

### INV-CHG-07

Rounding is explicit and deterministic.

### INV-CHG-08

Charges preserve pricing provenance.

### INV-CHG-09

Usage Charges derive from RatedUsage rather than unexplained raw usage.

### INV-CHG-10

Financially material historical Charges are not silently overwritten.

### INV-CHG-11

Corrections are append-oriented after financial finality.

### INV-CHG-12

Credits and Adjustments preserve their reasons and authority.

### INV-CHG-13

A Credit is not a Refund.

### INV-CHG-14

An Adjustment is not a Payment.

### INV-CHG-15

INTERNAL subscriptions cannot generate collectible commercial Charges.

### INV-CHG-16

Missing COMMERCIAL pricing does not silently become zero.

### INV-CHG-17

Legitimate zero amounts retain provenance.

### INV-CHG-18

Allowances and Credits are distinct concepts.

### INV-CHG-19

Historical FX results do not depend on current FX.

### INV-CHG-20

Provider monetary state does not become canonical merely because it exists.

### INV-CHG-21

Kill Bill identifiers do not replace Baobab Charge identity.

### INV-CHG-22

Charge creation is idempotent.

### INV-CHG-23

A recalculation cannot silently rewrite an issued invoice.

### INV-CHG-24

Credit value cannot be consumed more than once.

### INV-CHG-25

Subscriptions does not execute payment.

### INV-CHG-26

Subscriptions does not determine accounting journal treatment.

### INV-CHG-27

Historical monetary results remain explainable from versioned inputs.

### INV-CHG-28

An INTERNAL provider charge cannot automatically become a Baobab payable obligation.

---

# 104. Alternatives considered

## 104.1 Calculate only invoice totals

**Rejected.**

It destroys component-level provenance and correction capability.

## 104.2 Let Kill Bill monetary objects be canonical

**Rejected.**

It creates provider lock-in and reverses Baobab authority.

## 104.3 Store all monetary values as floating point

**Rejected.**

Binary floating point is unsuitable for authoritative monetary calculation.

## 104.4 Rewrite original charges when correcting them

**Rejected.**

It destroys historical financial evidence.

## 104.5 Treat credits as negative payments

**Rejected.**

Credits change billing obligations; payments move money.

## 104.6 Treat refunds as credits

**Rejected.**

A refund is payment execution.

## 104.7 Assume missing pricing means zero

**Rejected.**

It could provide unintended free commercial service.

## 104.8 Use current FX for historical recalculation

**Rejected.**

It changes historical monetary meaning.

## 104.9 Allow unrestricted charge CRUD

**Rejected.**

Financial facts require controlled mutation and audit.

---

# 105. Consequences

## Positive

- Every billed amount becomes explainable.
- Charge calculation remains independent of invoice presentation.
- Corrections preserve financial history.
- INTERNAL monetary safeguards remain enforceable.
- Usage, pricing and temporal provenance remain connected.
- Multi-currency correctness improves.
- Kill Bill remains replaceable.
- Payments and ERP boundaries remain clean.
- Reconciliation becomes deterministic.

## Negative

- More monetary objects must be persisted.
- Calculation provenance increases storage requirements.
- Credit/adjustment lifecycle requires governance.
- Historical recalculation requires versioned inputs.
- Multi-currency and rounding tests become extensive.

These costs are accepted because unexplained or mutable monetary facts are unacceptable in production billing.

---

# 106. Implementation requirements

A conforming implementation SHALL provide:

1. canonical Charge identity;
2. recurring Charges;
3. usage Charges;
4. one-time Charges where applicable;
5. proration Charges;
6. Credits;
7. Adjustments;
8. explicit currency;
9. exact decimal arithmetic;
10. deterministic rounding;
11. pricing provenance;
12. BillingTerms provenance;
13. classification provenance;
14. temporal provenance;
15. RatedUsage lineage;
16. idempotent charge creation;
17. immutable/append-oriented financial history;
18. controlled recalculation;
19. credit balance protection;
20. provider mappings where required;
21. monetary reconciliation;
22. payment separation;
23. ERP separation;
24. audit and observability.

---

# 107. Required tests

At minimum:

## Recurring calculation

- standard recurring fee;
- legitimate zero recurring fee;
- missing COMMERCIAL price;
- INTERNAL subscription.

## Usage

- simple usage charge;
- allowance;
- tiered usage;
- volume pricing;
- minimum charge;
- maximum cap;
- zero usage;
- late usage adjustment.

## Proration

- mid-period activation;
- mid-period cancellation;
- plan transition;
- suspension;
- explicit no-proration.

## Monetary arithmetic

- decimal precision;
- rounding;
- currencies with different minor-unit conventions;
- cross-currency arithmetic rejection.

## Credits

- account credit;
- subscription credit;
- partial credit consumption;
- full consumption;
- duplicate application;
- credit expiry where supported.

## Adjustments

- credit adjustment;
- debit adjustment;
- invoiced-charge correction;
- uninvoiced recalculation;
- duplicate correction command.

## Classification

- INTERNAL cannot become collectible;
- COMMERCIAL zero retains provenance;
- classification transition preserves segment boundaries.

## Provider

- matching provider amount;
- amount mismatch;
- duplicate provider item;
- INTERNAL provider charge violation.

## Security

- unauthorised credit rejected;
- arbitrary amount mutation rejected;
- idempotent adjustment;
- audit evidence produced.

---

# 108. Recommended implementation sequence

```text
1. Monetary primitives
        │
        ▼
2. Charge aggregate
        │
        ▼
3. Recurring calculation
        │
        ▼
4. Usage-charge integration
        │
        ▼
5. Proration integration
        │
        ▼
6. Calculation provenance
        │
        ▼
7. Credits
        │
        ▼
8. Adjustments
        │
        ▼
9. Multi-currency safeguards
        │
        ▼
10. Recalculation
        │
        ▼
11. Provider reconciliation
        │
        ▼
12. Audit/security hardening
```

---

# 109. Architectural progression

The sequence now forms:

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
Classification policy
        │
        ▼
ADR-SUB-0007
Commercial lifecycle
        │
        ▼
ADR-SUB-0008
Billing time
        │
        ▼
ADR-SUB-0009
Monetary calculation
        │
        ▼
Charge / Credit /
Adjustment facts
        │
        ▼
later invoice and
obligation lifecycle
```

---

# 110. Worked example

Consider:

```text
COMMERCIAL subscription

Billing period:
01 Sep → 01 Oct

Base recurring fee:
ZAR 1,000

Included usage:
1,000 API units

Actual usage:
1,500 API units

Overage:
ZAR 0.50 / unit

Service credit:
ZAR 100
```

The engine first resolves:

```text
Recurring Charge
= ZAR 1,000
```

Then:

```text
Actual usage
1,500

less allowance
1,000

billable usage
500

500 × ZAR 0.50
=
ZAR 250
```

creating:

```text
Usage Charge
= ZAR 250
```

Then:

```text
Service Credit
= ZAR 100
```

The canonical monetary facts are therefore:

```text
Recurring Charge    +1,000
Usage Charge          +250
Service Credit        -100
──────────────────────────
Net pre-tax amount   1,150 ZAR
```

Each component retains independent identity and provenance.

ADR-SUB-0012 may subsequently group them into an InvoiceProjection.

---

# 111. Correction example

Suppose usage was later corrected:

```text
original:
1,500 units

correct:
1,400 units
```

Original overage:

```text
500 × 0.50
=
ZAR 250
```

Correct overage:

```text
400 × 0.50
=
ZAR 200
```

If the original Charge is already financially final, Baobab does not rewrite:

```text
ZAR 250
```

to:

```text
ZAR 200
```

Instead:

```text
Original Usage Charge
+ ZAR 250

Usage Correction Credit
- ZAR 50
```

preserves both the original financial fact and its correction.

---

# 112. INTERNAL example

Consider the same usage under an INTERNAL subscription:

```text
Usage:
1,500 units
```

The engine MAY determine analytically:

```text
shadow rated value:
ZAR 250
```

for:

- cost allocation;
- capacity analysis;
- internal reporting.

But ADR-SUB-0006 requires:

```text
collectible Charge:
NONE
```

and:

```text
payment execution:
PROHIBITED
```

The analytical value SHALL therefore be clearly distinguished from a commercial Charge.

---

# 113. Final Decision

Baobab SHALL maintain a first-class, provider-independent **monetary calculation layer** consisting of Charges, Credits and Adjustments.

The principal calculation rule is:

> **Every monetary billing fact must be explainable from authoritative, versioned commercial inputs.**

The principal identity rule is:

> **A Charge is a Baobab billing-domain fact. It is not an invoice item merely because a provider represents it as one.**

The principal historical rule is:

> **Once financially consequential, monetary history is corrected through explicit append-oriented adjustments rather than silent destructive mutation.**

The principal classification rule is:

> **No pricing calculation, provider result or billing-account configuration may turn an INTERNAL subscription into a collectible commercial obligation.**

The principal zero-value rule is:

> **A legitimate zero is a calculated financial result with provenance; missing or unresolved pricing is not zero.**

The principal precision rule is:

> **Authoritative monetary calculation uses explicit currency, exact decimal arithmetic and deterministic rounding.**

The principal correction rule is:

> **Credits and adjustments alter billing consequences; refunds move money. These operations must remain distinct.**

The principal provider rule is:

> **Kill Bill may execute or represent billing calculations where semantically compatible, but Baobab retains canonical monetary identity, provenance and reconciliation authority.**

And the principal accounting boundary is:

> **Subscriptions determines the billing fact, Payments executes monetary movement, and ERP determines the accounting consequence.**
