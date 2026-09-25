# ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Billing / Financial Obligations  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None

**Related:**

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-SUB-0004 — Usage Metering, Rating, Aggregation and Billable Consumption Model
- ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment
- ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-PAY-0001 — Adopt HyperSwitch as the Headless Baobab Payment Orchestration Engine
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- `shared/contracts/subscriptions/v1`
- `shared/contracts/product/v1`
- `shared/contracts/payments/v1`

---

# 1. Context

The subscription billing lifecycle eventually converts authorised subscription activity into financial obligations.

Those obligations may arise from:

- recurring subscription fees;
- usage-derived charges;
- one-time subscription charges;
- prorations;
- contractual adjustments;
- credits;
- discounts;
- allowances;
- other canonical billing-policy consequences.

However, several architectural boundaries already exist.

`baobab-subscriptions` owns:

- billing projections;
- billing terms;
- billing cycles;
- rating;
- charges;
- credits and billing adjustments;
- invoice projections;
- financial obligation creation.

It does **not** own:

- authoritative ProductSubscription existence or classification;
- payment execution;
- settlement routing;
- accounting journals;
- general ledger;
- accounts receivable authority;
- revenue recognition.

Therefore:

```text
Subscription activity
       │
       ▼
Billing consequence
       │
       ▼
Financial obligation
       │
       ├────────► baobab-payments
       │
       │          monetary execution
       │
       └────────► baobab-erp
                  accounting consequence
```

The subscriptions engine must preserve this separation while still maintaining enough billing state to explain:

> What does this subscriber owe, why, for which period, under which policy, and what happened to that obligation?

This ADR defines that boundary.

---

# 2. Decision

`baobab-subscriptions` SHALL implement a canonical billing lifecycle composed of:

```text
BillingTerms
     │
     ▼
BillingCycle
     │
     ├─────────────┐
     │             │
     ▼             ▼
Recurring       Usage
Charge          Rating
     │             │
     └──────┬──────┘
            ▼
          Charges
            │
       ┌────┴────┐
       ▼         ▼
    Credits   Adjustments
       └────┬────┘
            ▼
     InvoiceProjection
            │
            ▼
   FinancialObligation
            │
       ┌────┴─────┐
       ▼          ▼
   Payments      ERP
```

Each concept SHALL remain distinct.

An invoice projection SHALL NOT become the accounting ledger.

A financial obligation SHALL NOT become a payment.

A payment SHALL NOT retroactively redefine the billing facts that created the obligation.

---

# 3. Terminology

For this ADR:

### Billing Cycle

A bounded period over which applicable subscription billing consequences are accumulated and finalised.

### Charge

A positive monetary billing consequence produced under authorised billing policy.

### Credit

A monetary value reducing an amount otherwise owed.

### Adjustment

A controlled correction or modification to a previously established billing consequence.

### Invoice Projection

The subscriptions engine's canonical operational representation of grouped billing facts presented as an amount due or billing statement.

### Financial Obligation

A canonical instruction/fact representing an amount that is legitimately due for monetary execution or settlement processing.

### Payment

Actual monetary execution owned by `baobab-payments`.

### Accounting Consequence

Journal, receivable, revenue or other accounting treatment owned by `baobab-erp`.

---

# 4. Separation of financial concepts

The following SHALL remain separate:

```text
Charge
  !=
InvoiceProjection
  !=
FinancialObligation
  !=
Payment
  !=
ERP Receivable
  !=
Ledger Entry
```

They may be causally related.

They are not interchangeable.

---

# 5. BillingTerms

`BillingTerms` SHALL capture the applicable billing arrangement for a BillingSubscriptionProjection.

Conceptually:

```text
BillingTerms
──────────────────────────

billing_terms_id

billing_subscription_id

classification

billing_policy_id
billing_policy_version

pricing_reference
pricing_version

currency

billing_frequency
billing_anchor

effective_from
effective_until

payment_terms

created_at
```

The exact persistence representation MAY vary.

The semantic distinction is normative.

---

# 6. BillingTerms are version-aware

Billing terms SHALL not be destructively overwritten when materially changed.

For example:

```text
Terms v1
effective Jan–Jun

Terms v2
effective Jul onward
```

Historical charges created under v1 SHALL remain explainable under v1.

Current terms SHALL not silently reinterpret historical billing.

---

# 7. BillingCycle

A `BillingCycle` SHALL represent a bounded billing period.

Conceptually:

```text
BillingCycle
─────────────────────────

billing_cycle_id

billing_subscription_id

period_start
period_end

billing_terms_version

status

opened_at
closing_started_at
finalized_at
```

---

# 8. Billing-cycle states

The semantic lifecycle SHOULD include equivalent states to:

```text
OPEN
  │
  ▼
CLOSING
  │
  ▼
FINALIZED
```

Additional operational states MAY be introduced where required.

---

# 9. OPEN

During `OPEN`:

- recurring charges may be scheduled or accrued;
- usage may be accepted;
- usage aggregates may evolve;
- provisional rating may occur;
- estimates may be presented.

Financial results SHALL not be represented as immutable final billing solely because the period is currently open.

---

# 10. CLOSING

`CLOSING` SHALL represent controlled finalisation.

During closing, subscriptions SHOULD:

1. establish the applicable period boundary;
2. ingest permitted late usage;
3. complete aggregation;
4. apply rating;
5. apply recurring charges;
6. resolve applicable credits;
7. reconcile expected billing facts;
8. validate classification;
9. validate policy and pricing versions;
10. calculate the final billing position.

---

# 11. FINALIZED

A finalized cycle SHALL represent the established billing result for that cycle.

Finalisation SHALL not imply that:

- payment succeeded;
- ERP posted accounting entries;
- no future correction can occur.

It means that ordinary billing calculation for that period has completed.

Later changes require controlled adjustment semantics.

---

# 12. Billing period versus calendar period

Billing periods SHALL be explicit.

They SHALL not be assumed to equal calendar months.

Valid models may include:

- monthly anniversary;
- calendar month;
- weekly;
- quarterly;
- annual;
- contract-defined;
- other supported periods.

The billing anchor SHALL be explicit.

---

# 13. Billing-cycle time zone

Billing-period boundaries SHALL use defined temporal semantics.

The engine SHALL not infer a billing boundary from the application server's local time zone.

Applicable time-zone/calendar rules SHALL be explicit where they affect period boundaries.

Persisted timestamps SHALL remain unambiguous.

---

# 14. Recurring charges

Recurring charges SHALL be generated from applicable BillingTerms.

Conceptually:

```text
BillingTerms
     │
     ▼
BillingCycle
     │
     ▼
RecurringCharge
```

The charge SHALL retain the terms/pricing version that produced it.

---

# 15. Usage charges

Usage-derived charges SHALL originate through ADR-SUB-0004:

```text
UsageObservation
      │
      ▼
UsageRecord
      │
      ▼
UsageAggregate
      │
      ▼
RatedUsage
      │
      ▼
Charge
```

Invoice construction SHALL not re-invent usage rating.

---

# 16. Charge model

Conceptually:

```text
Charge
────────────────────────

charge_id

billing_subscription_id
billing_cycle_id

charge_type

source_reference

description_reference

amount
currency

policy_reference
pricing_reference

effective_at

status

created_at
```

A charge SHALL retain sufficient provenance to explain why it exists.

---

# 17. Charge types

Charge types MAY include:

```text
RECURRING
USAGE
ONE_TIME
PRORATION
ADJUSTMENT
```

or canonical equivalents.

The precise enumeration SHALL be governed through Shared/domain contracts.

---

# 18. Charge immutability

Once a charge has become part of a finalized financial result, its financial meaning SHALL not normally be destructively modified.

Corrections SHALL use:

- credit;
- debit adjustment;
- replacement/correction relationship;

rather than rewriting history.

---

# 19. Charge identity

Each charge SHALL have stable Baobab identity.

Provider invoice-item IDs SHALL NOT become canonical charge IDs.

The relationship may be:

```text
Baobab Charge
      │
      ▼
ProviderReference
      │
      ▼
Kill Bill InvoiceItem
```

where provider projection requires it.

---

# 20. Credits

A `Credit` SHALL represent an authorised reduction in monetary obligation.

Credits may arise from:

- contractual allowance;
- service correction;
- promotional entitlement;
- billing dispute resolution;
- prior overcharge;
- commercial adjustment.

Credits SHALL be explicit financial facts.

---

# 21. Credits are not negative payments

A credit SHALL NOT be modelled merely as a fake negative payment.

Billing correction and monetary movement are distinct.

For example:

```text
Charge = ZAR 1,000
Credit = ZAR   200
────────────────────
Net billing = ZAR 800
```

No payment of negative ZAR 200 occurred.

---

# 22. Credit authorization

Credits SHALL be controlled mutations under ADR-SUB-0016.

A credit SHALL require:

- authorised actor/workload;
- tenant context;
- affected billing resource;
- amount and currency;
- reason code;
- idempotency identity;
- audit evidence.

---

# 23. Credit provenance

Conceptually:

```text
Credit
─────────────────────────

credit_id

billing_subscription_id
billing_cycle_id

amount
currency

reason_code

source_reference

effective_at

actor/workload

created_at
```

The exact implementation MAY differ.

---

# 24. Adjustments

An adjustment SHALL represent an explicit correction to billing history or billing calculation.

Adjustments MAY be positive or negative where domain policy permits.

They SHALL preserve the original billing facts.

---

# 25. Example correction

Original:

```text
Usage charge = ZAR 500
```

Correction establishes:

```text
Correct amount = ZAR 450
```

Preferred:

```text
Original charge = ZAR 500
Adjustment      = ZAR -50
```

rather than:

```text
UPDATE charge
SET amount = 450
```

after finalisation.

---

# 26. Adjustment reasons

Adjustments SHOULD use machine-readable reason codes such as:

```text
USAGE_CORRECTION
PRICING_CORRECTION
SERVICE_CREDIT
CONTRACT_CORRECTION
DUPLICATE_CHARGE
MANUAL_BILLING_CORRECTION
```

Canonical vocabulary SHALL be governed appropriately.

---

# 27. Currency integrity

Charges, credits and adjustments combined in one monetary total SHALL use compatible currency semantics.

The engine SHALL NOT compute:

```text
ZAR 100 + USD 20
```

without an explicit authorised FX conversion process.

---

# 28. InvoiceProjection

`InvoiceProjection` SHALL be the Baobab subscription engine's operational grouping of billing facts.

Conceptually:

```text
InvoiceProjection
────────────────────────────

invoice_projection_id

billing_account_id
billing_cycle_id

tenant_id
platform_account_reference

currency

subtotal
credits
adjustments
tax_reference/result where applicable
total

status

issued_at
due_at

provider_references[]

created_at
```

The exact structure may evolve.

---

# 29. Why “InvoiceProjection”

The term deliberately prevents the subscriptions service from claiming accounting authority.

An InvoiceProjection represents:

> Baobab's billing view of what should be presented or collected.

It does not itself constitute:

> the ERP general ledger or authoritative accounting receivable.

---

# 30. Invoice line provenance

Every invoice line SHALL trace to its billing source.

For example:

```text
InvoiceProjection
       │
       ├── Recurring Charge
       │       └── BillingTerms
       │
       ├── Usage Charge
       │       └── RatedUsage
       │              └── UsageAggregate
       │                     └── UsageRecords
       │
       └── Credit
               └── Reason / source
```

An unexplained monetary line is unacceptable.

---

# 31. Invoice aggregation

An InvoiceProjection MAY group multiple charges for presentation.

Grouping SHALL not destroy individual charge identity or provenance.

For example:

```text
AI usage                  ZAR 200
Trade transactions        ZAR 300
Recurring platform fee    ZAR 500
────────────────────────────────
Subtotal                 ZAR 1,000
```

Each line remains traceable.

---

# 32. Billing account relationship

An InvoiceProjection SHALL be associated with a `BillingAccountProjection`.

That account may represent a payer arrangement without redefining tenant or PlatformAccount identity.

This permits structures such as:

```text
Corporate Group Payer
        │
        ├── Subscription A
        ├── Subscription B
        └── Subscription C
```

where commercial policy permits central billing.

---

# 33. Payer is not necessarily consumer

Baobab SHALL NOT assume:

```text
payer == consuming tenant
```

or:

```text
payer == legal entity receiving capability
```

The payer relationship SHALL be explicit.

This is important for group structures and external customer groups.

---

# 34. Cross-subscription invoice grouping

Multiple BillingSubscriptionProjections MAY be grouped into one billing presentation only where an authorised BillingAccountProjection and billing policy permit it.

The engine SHALL NOT group subscriptions merely because they share:

- parent organisation;
- brand;
- tenant name;
- corporate group.

Payer authority must be explicit.

---

# 35. Cross-tenant grouping

Where central billing legitimately spans consuming tenants, the architecture SHALL preserve each source tenant's identity and authorization boundary.

A shared payer does not merge tenant data domains.

---

# 36. INTERNAL subscriptions

INTERNAL subscriptions SHALL not create payable monetary InvoiceProjections.

They MAY participate in:

- usage statements;
- internal cost reports;
- shadow-value reporting;

but those SHALL be clearly distinguished from collectible commercial invoices.

---

# 37. INTERNAL safety invariant

The following is prohibited:

```text
classification = INTERNAL
       +
collectible invoice
```

or:

```text
classification = INTERNAL
       +
payment obligation
```

A non-monetary statement is not a payment obligation.

---

# 38. COMMERCIAL subscriptions

COMMERCIAL subscriptions MAY generate monetary InvoiceProjections where policy and billing terms require.

However:

```text
COMMERCIAL
```

does not automatically imply:

```text
amount > 0
```

A commercial invoice may legitimately total zero because of:

- credits;
- trial;
- allowances;
- zero usage;
- contract pricing.

It remains COMMERCIAL.

---

# 39. Zero-total commercial invoice

A COMMERCIAL cycle whose net amount is zero SHALL retain commercial provenance.

It SHALL NOT be rewritten as INTERNAL.

Where no collection is necessary:

```text
payment_required = false
```

for that obligation.

---

# 40. Negative net billing

If credits exceed charges, the resulting negative position SHALL NOT automatically trigger a payment/refund.

Policy SHALL determine whether the value becomes:

- account credit;
- future balance offset;
- refund candidate;
- manual review;
- another supported treatment.

Refund execution remains a payment concern.

---

# 41. Taxes

Tax calculation is a distinct financial concern.

Where applicable, an InvoiceProjection MAY include tax results or references supplied by the appropriate tax authority/component.

Subscription classification SHALL not itself determine tax treatment.

The architecture SHALL preserve:

```text
pre-tax charges
      │
      ▼
tax determination
      │
      ▼
invoice total
```

where tax applies.

---

# 42. Tax provenance

Where tax contributes to an invoice total, the result SHALL retain sufficient provenance to identify:

- jurisdiction/context;
- taxable basis;
- rate/rule reference;
- amount;
- applicable policy/version.

This ADR does not designate a tax engine.

---

# 43. Invoice states

The InvoiceProjection lifecycle SHOULD distinguish equivalent states such as:

```text
DRAFT
  │
  ▼
FINALIZED
  │
  ▼
ISSUED
```

with additional states such as:

```text
VOID
ADJUSTED
```

where required.

Payment state SHALL not be overloaded into the invoice lifecycle.

---

# 44. Payment state is separate

An invoice may be:

```text
invoice_status = ISSUED
payment_status = UNPAID
```

or:

```text
invoice_status = ISSUED
payment_status = PAID
```

The invoice does not become a different billing fact merely because payment status changes.

---

# 45. FinancialObligation

A `FinancialObligation` SHALL represent the monetary amount authorised for downstream payment handling.

Conceptually:

```text
FinancialObligation
──────────────────────────

obligation_id

invoice_projection_id
billing_account_id

tenant/context references

amount
currency

due_at

classification

idempotency_reference

status

created_at
```

The exact schema belongs in the appropriate canonical contract.

---

# 46. Obligation creation

A payment obligation SHALL only be created when:

```text
invoice/billing result valid
        AND
classification permits payment
        AND
amount requires collection
        AND
payment policy requires execution
```

Otherwise no payment obligation SHALL be emitted.

---

# 47. INTERNAL payment barrier

Immediately before creating a payment obligation:

```text
effective classification
        │
        ▼
billing policy
        │
        ├── INTERNAL ─────► STOP
        │
        └── COMMERCIAL ───► continue if payable
```

This barrier SHALL remain even if prior stages already performed classification validation.

---

# 48. Payment handoff

The canonical direction is:

```text
FinancialObligation
        │
        ▼
baobab-payments
        │
        ▼
payment orchestration
        │
        ▼
HyperSwitch
        │
        ▼
payment provider
```

`baobab-subscriptions` SHALL NOT select the underlying payment processor.

---

# 49. Payment idempotency

Every payment obligation SHALL have stable identity suitable for idempotent downstream execution.

Repeated delivery of:

```text
obligation_id = OBL-123
```

must not create multiple independent payment obligations.

---

# 50. Obligation amount immutability

Once submitted for payment execution, the obligation's financial meaning SHALL not be silently mutated.

If the amount changes:

```text
original obligation = ZAR 1,000
correct obligation  = ZAR   900
```

the system SHALL use controlled cancellation/adjustment/replacement semantics rather than rewriting the in-flight obligation.

---

# 51. Payment result

`baobab-payments` SHALL report payment execution outcomes through canonical payment contracts/events.

Subscriptions MAY project relevant payment state for billing visibility.

It SHALL not become the authoritative payment processor.

---

# 52. Payment failure

Payment failure SHALL NOT rewrite the original charge.

Example:

```text
valid charge = ZAR 500
payment = FAILED
```

does not imply:

```text
charge = ZAR 0
```

The financial obligation remains governed by collection policy.

---

# 53. Payment retry

Payment retry policy belongs to the payment/collection architecture.

Subscriptions MAY express:

- obligation;
- due date;
- billing context;

but SHALL NOT directly orchestrate processor-specific retry logic.

---

# 54. Entitlement after payment failure

Payment failure SHALL NOT directly cause subscriptions to revoke a `CapabilityGrant`.

The architecture remains:

```text
Payment/Billing condition
        │
        ▼
canonical lifecycle signal
        │
        ▼
Control Plane
        │
        ▼
entitlement decision
```

This preserves authority boundaries.

---

# 55. ERP handoff

Billing facts required for accounting SHALL be communicated to `baobab-erp` through canonical contracts/events.

The intended boundary is:

```text
Subscriptions
     │
     │ billing facts
     ▼
ERP
     │
     ▼
accounting interpretation
     │
     ▼
journal / receivable / revenue
```

Subscriptions SHALL NOT post directly into ERP database tables.

---

# 56. Accounting authority

`baobab-erp` owns:

- accounting entries;
- receivables;
- journals;
- general ledger;
- revenue recognition;
- financial accounting consequences.

Subscriptions owns the billing facts that may cause those accounting consequences.

---

# 57. Billing amount versus receivable

The following SHALL not be assumed equivalent at the data-model level:

```text
InvoiceProjection.total
```

and:

```text
ERP accounts receivable balance
```

ERP may apply accounting operations outside the subscriptions domain.

Reconciliation may compare them without merging their authorities.

---

# 58. Provider invoice

Kill Bill may create provider-side invoice resources.

Those SHALL be represented as provider projections.

The relationship is:

```text
Baobab InvoiceProjection
        │
        ▼
BillingProvider
        │
        ▼
KillBillAdapter
        │
        ▼
Kill Bill Invoice
```

Kill Bill's invoice ID SHALL not become the canonical Baobab invoice identity.

---

# 59. Provider invoice divergence

If Kill Bill calculates an amount that conflicts with Baobab's expected billing result:

```text
Baobab expected = 1,000
Kill Bill       = 1,100
```

the system SHALL NOT silently accept the provider result as authoritative.

It SHALL create reconciliation failure.

---

# 60. Provider reconciliation

Reconciliation SHOULD detect:

| Condition | Example |
|---|---|
| Missing provider invoice | Baobab expects provider projection but none exists |
| Duplicate provider invoice | Multiple provider invoices for one expected billing result |
| Amount drift | Provider amount differs |
| Currency drift | Provider currency differs |
| Line drift | Provider items differ materially |
| State drift | Provider lifecycle differs |
| Mapping drift | Provider reference missing or incorrect |

Financially ambiguous differences SHOULD require manual review.

---

# 61. Provider-generated numbers

Provider-generated invoice numbers MAY be retained for provider operations.

Customer-facing or statutory invoice numbering SHALL follow the appropriate Baobab/legal/accounting policy.

This ADR does not assume Kill Bill's identifier satisfies every jurisdiction's statutory invoice requirements.

---

# 62. Document rendering

InvoiceProjection is structured billing data.

Presentation may produce:

- web invoice;
- PDF;
- statement;
- API representation.

Rendering SHALL not redefine the underlying billing facts.

The canonical source remains structured data.

---

# 63. Invoice snapshot

Once an invoice representation is formally issued, Baobab SHOULD preserve sufficient snapshot/version information to reproduce what was presented.

Subsequent product-name or address changes SHALL not silently rewrite historical issued documents where legal or financial policy requires historical preservation.

---

# 64. Billing-account details

Invoice rendering may require:

- payer legal name;
- billing address;
- tax identifiers;
- contact information.

Such data SHOULD be obtained from authoritative sources and snapshotted/reference-versioned as necessary for historical reproducibility.

Subscriptions SHALL not become the master source for organisation identity.

---

# 65. Finalisation transaction

Where practical, finalisation SHALL atomically persist:

```text
final cycle state
      +
final charges/credits references
      +
invoice projection
      +
audit evidence
      +
outbox intent
```

within the subscriptions service's local transactional boundary.

External payment/provider/ERP calls SHALL remain outside the local database transaction.

---

# 66. No distributed financial transaction

The platform SHALL NOT attempt:

```text
BEGIN DISTRIBUTED TRANSACTION

Subscriptions DB
Kill Bill DB
Payments
ERP DB

COMMIT ALL
```

Correctness SHALL instead use:

- local transactions;
- outbox/inbox;
- idempotency;
- stable identifiers;
- retries;
- reconciliation;
- audit.

---

# 67. Transactional outbox

Finalised billing facts requiring downstream propagation SHOULD use the transactional outbox pattern.

Conceptually:

```text
BEGIN

finalize billing cycle

persist invoice projection

persist financial obligation

persist audit

persist outbox events

COMMIT
```

Then asynchronously:

```text
outbox
  │
  ├──► payments
  ├──► ERP
  └──► other authorised consumers
```

---

# 68. Event semantics

Subscriptions MAY emit canonical events such as equivalent forms of:

```text
billing.cycle.finalized
billing.invoice.finalized
billing.invoice.issued
billing.obligation.created
billing.credit.created
billing.adjustment.created
```

Exact names and schemas SHALL be governed in Shared.

Provider-native event names SHALL not become canonical merely because Kill Bill emits them.

---

# 69. Event contents

Financial events SHOULD contain references rather than excessive duplication of sensitive data.

A consumer requiring full detail SHOULD resolve it through an authorised canonical interface where appropriate.

---

# 70. Event idempotency

Every emitted material event SHALL have stable event identity.

Downstream consumers SHALL be able to process at-least-once delivery safely.

---

# 71. Billing finalisation idempotency

Repeated attempts to finalise the same billing cycle SHALL not create:

- duplicate invoice projections;
- duplicate obligations;
- duplicate provider invoices;
- duplicate ERP consequences.

Finalisation SHALL have deterministic business identity.

---

# 72. Concurrent finalisation

The system SHALL prevent two workers from independently finalising the same billing cycle.

Appropriate concurrency controls SHALL be used.

---

# 73. Cancellation before finalisation

Where policy permits cancellation before finalisation, the cycle SHALL calculate only legitimate obligations up to the effective cancellation boundary.

Proration semantics SHALL be governed explicitly by BillingTerms.

---

# 74. Cancellation after finalisation

Termination of a ProductSubscription SHALL not erase already finalised legitimate charges.

Historical financial obligations remain.

Future billing stops according to effective cancellation semantics.

---

# 75. Suspension

Suspension billing behaviour SHALL follow explicit policy.

Possible treatments may include:

- continue recurring billing;
- pause recurring billing;
- prorate;
- meter usage but prohibit new service consumption;
- another contractual rule.

The engine SHALL not infer monetary suspension semantics solely from the word `SUSPENDED`.

---

# 76. Proration

Where proration applies, the algorithm SHALL be:

- explicit;
- deterministic;
- versioned;
- auditable.

Proration SHALL define:

- applicable period;
- numerator/denominator basis;
- rounding;
- effective boundary;
- pricing version.

---

# 77. No implicit proration

The engine SHALL NOT assume that every mid-cycle subscription change requires proration.

The applicable BillingTerms determine this.

---

# 78. Billing disputes

A dispute SHALL not require destructive alteration of billing history.

The architecture SHOULD support:

```text
issued billing fact
       │
       ▼
dispute
       │
       ├── no change
       ├── credit
       ├── adjustment
       └── replacement/correction
```

according to governed resolution.

---

# 79. Void

An InvoiceProjection MAY be voided where legally and commercially appropriate.

Void SHALL be an explicit state transition with:

- reason;
- authority;
- audit;
- downstream consequences.

Deleting the invoice record is prohibited as the normal void mechanism.

---

# 80. Corrected invoice

Where an issued invoice requires correction, the architecture SHOULD preserve:

```text
original invoice
      │
      ▼
correction relationship
      │
      ▼
corrected invoice / adjustment
```

rather than silently rewriting what was previously issued.

---

# 81. Billing disputes and payment

A disputed invoice does not necessarily imply that payment processing should continue or stop.

Collection treatment SHALL be explicit policy.

Subscriptions may communicate the billing/dispute state.

Payments owns execution behaviour within its authority.

---

# 82. Multi-market billing

Billing policy SHALL permit market-specific:

- currency;
- pricing;
- tax;
- payment terms;
- invoice requirements.

Market SHALL remain separate from subscription classification.

For example:

```text
classification = COMMERCIAL
market = ZA
currency = ZAR
```

and:

```text
classification = COMMERCIAL
market = UG
currency = UGX
```

are both valid.

---

# 83. Cross-border payer arrangements

A payer may reside in a different market from the consuming tenant where platform policy and legal requirements permit.

Therefore:

```text
service market
      != necessarily
billing market
      != necessarily
payment rail jurisdiction
```

These contexts SHALL be explicit where financially relevant.

---

# 84. FX conversion

Where an obligation requires currency conversion, Baobab SHALL retain:

- source currency;
- source amount;
- target currency;
- FX rate;
- FX source/reference;
- effective timestamp;
- rounding result.

FX conversion SHALL not silently overwrite the original monetary fact.

---

# 85. Partial payment

A FinancialObligation MAY be only partially settled.

Subscriptions MAY project:

```text
amount_due
amount_paid
amount_outstanding
```

based on canonical payment outcomes.

Payment execution remains owned by Payments.

---

# 86. Overpayment

An overpayment SHALL NOT be silently transformed into a billing credit without explicit policy.

Payments reports the monetary result.

The appropriate credit/refund/accounting treatment SHALL be governed.

---

# 87. Refund

Refund execution belongs to `baobab-payments`.

A billing correction may create a refund requirement or candidate.

The distinction is:

```text
billing decision:
    customer should receive ZAR 100

payment execution:
    refund ZAR 100
```

These are related but separate.

---

# 88. Write-off

A write-off is not equivalent to deleting a charge.

Where write-offs are supported, accounting authority belongs primarily to ERP and applicable financial governance.

Subscriptions MAY project relevant collection/billing status without pretending the original charge never existed.

---

# 89. Aging and collections

Detailed receivables aging and accounting collection management belong to ERP/financial operations unless a separate platform capability is established.

Subscriptions may expose due dates and billing obligation status required for billing workflows.

---

# 90. Retention

Billing cycles, charges, credits, adjustments, invoice projections and obligation history SHALL be retained according to applicable:

- financial requirements;
- tax requirements;
- dispute requirements;
- audit requirements;
- privacy/data-minimisation rules.

This ADR does not prescribe one universal retention period across jurisdictions.

---

# 91. Observability

The service SHALL expose telemetry for:

- cycles opened;
- cycles closing;
- finalisation latency;
- finalisation failures;
- invoices finalised;
- zero-value commercial invoices;
- credits;
- adjustments;
- obligations created;
- payment handoff failures;
- ERP handoff failures;
- provider invoice drift;
- duplicate finalisation attempts;
- INTERNAL monetary violations;
- reconciliation backlog.

Tenant identifiers SHOULD not become uncontrolled high-cardinality metric labels.

---

# 92. Audit requirements

Audit evidence SHALL cover at minimum:

- cycle finalisation;
- manual credits;
- manual adjustments;
- invoice void;
- invoice correction;
- financial-obligation creation;
- provider reconciliation repair;
- classification-sensitive billing decisions;
- privileged billing intervention.

---

# 93. Reconciliation domains

Billing reconciliation SHALL distinguish at least:

```text
Billing Facts
     │
     ├── local charge reconciliation
     ├── provider reconciliation
     ├── payment reconciliation
     └── ERP/accounting reconciliation
```

A discrepancy in one domain SHALL not automatically rewrite another.

---

# 94. Four-state comparison

For a commercial obligation, Baobab may need to compare:

```text
1. Expected billing state
   baobab-subscriptions

2. Provider billing state
   Kill Bill

3. Payment execution state
   baobab-payments

4. Accounting state
   baobab-erp
```

These states are intentionally separate.

---

# 95. Example reconciliation

```text
Subscriptions:
    invoice = ZAR 1,000

Kill Bill:
    invoice = ZAR 1,000

Payments:
    paid = ZAR 1,000

ERP:
    receivable = ZAR 1,000
    settlement = ZAR 1,000

Result:
    reconciled
```

A mismatch becomes an explicit reconciliation finding.

---

# 96. Mismatch example

```text
Subscriptions = ZAR 1,000
Kill Bill     = ZAR 1,000
Payments      = ZAR   900
ERP           = ZAR 1,000
```

This does not justify changing the invoice to ZAR 900.

It indicates a payment/settlement discrepancy requiring appropriate handling.

---

# 97. Security

All financial mutations SHALL conform to ADR-SUB-0016.

In particular:

- credits are controlled;
- adjustments are controlled;
- invoice voiding is controlled;
- finalisation is idempotent;
- tenant scope is trusted;
- privileged corrections are audited;
- arbitrary amount mutation is prohibited.

---

# 98. Domain invariants

The following invariants SHALL hold.

### INV-BIL-01

A Charge is not a Payment.

### INV-BIL-02

An InvoiceProjection is not an ERP ledger entry.

### INV-BIL-03

A FinancialObligation is not payment execution.

### INV-BIL-04

Every charge has billing provenance.

### INV-BIL-05

Finalised charges are not silently rewritten.

### INV-BIL-06

Corrections use explicit credit/adjustment semantics.

### INV-BIL-07

Billing terms are version-aware.

### INV-BIL-08

Historical billing remains explainable under the terms that created it.

### INV-BIL-09

INTERNAL subscriptions cannot create payable obligations.

### INV-BIL-10

A zero-total COMMERCIAL invoice remains COMMERCIAL.

### INV-BIL-11

Payment failure does not erase a valid charge.

### INV-BIL-12

Provider invoice state cannot override canonical Baobab billing policy.

### INV-BIL-13

Kill Bill invoice IDs never replace canonical Baobab invoice identity.

### INV-BIL-14

Payment execution remains owned by `baobab-payments`.

### INV-BIL-15

Accounting remains owned by `baobab-erp`.

### INV-BIL-16

Billing-cycle finalisation is idempotent.

### INV-BIL-17

Concurrent finalisation cannot produce duplicate obligations.

### INV-BIL-18

Cross-subscription billing requires explicit payer authority.

### INV-BIL-19

A shared payer does not merge tenant boundaries.

### INV-BIL-20

Currency is explicit for every monetary billing fact.

### INV-BIL-21

Corrections preserve original financial history.

### INV-BIL-22

Payment state and invoice state remain separate.

### INV-BIL-23

Provider, payment and ERP discrepancies are reconciled rather than silently overwritten.

### INV-BIL-24

Material billing mutations are auditable.

---

# 99. Alternatives considered

## 99.1 Make Kill Bill invoices canonical

**Rejected.**

This would make Baobab billing identity and semantics provider-dependent.

---

## 99.2 Let ERP generate all subscription billing

**Rejected.**

ERP owns accounting; subscriptions owns subscription billing policy and billing projections.

---

## 99.3 Let Payments own invoices

**Rejected.**

Payments executes monetary obligations; it does not own the billing facts that created them.

---

## 99.4 Rewrite invoice amounts after corrections

**Rejected.**

This destroys financial history and weakens auditability.

---

## 99.5 Treat payment failure as invoice cancellation

**Rejected.**

A failed payment does not invalidate the underlying billing obligation.

---

## 99.6 Allow INTERNAL invoices and simply skip payment

**Rejected for collectible monetary invoices.**

INTERNAL treatment requires zero monetary obligation, not merely suppression of payment execution.

---

## 99.7 Group subscriptions by corporate parent automatically

**Rejected.**

Corporate relationship does not itself establish payer authority.

---

## 99.8 Use one distributed transaction across billing, payment and ERP

**Rejected.**

The architecture is polyrepo, polyglot and independently authoritative.

Correctness is achieved through durable state, idempotency and reconciliation.

---

# 100. Consequences

## Positive

- Billing facts remain explainable.
- Payments and accounting stay decoupled.
- Kill Bill remains replaceable.
- Corrections preserve history.
- INTERNAL monetary safety is maintained.
- Central payer arrangements are supported without collapsing tenancy.
- Multi-market billing remains possible.
- Provider/payment/ERP drift can be reconciled explicitly.
- Billing-cycle finalisation becomes deterministic and idempotent.

## Negative

- More explicit financial entities must be maintained.
- Credits and adjustments require dedicated workflows.
- Reconciliation spans several engines.
- Invoice rendering requires historical snapshot discipline.
- Cross-border billing requires careful currency/tax context.
- Billing finalisation is more complex than simply accepting a provider invoice.

These costs are accepted because financial correctness and authority separation are foundational platform requirements.

---

# 101. Implementation requirements

An implementation conforming to this ADR SHALL provide:

1. version-aware BillingTerms;
2. explicit BillingCycles;
3. recurring-charge generation;
4. integration with usage-derived charges from ADR-SUB-0004;
5. canonical Charge identity;
6. controlled Credit and Adjustment models;
7. InvoiceProjection;
8. explicit payer/BillingAccountProjection association;
9. classification-aware monetary validation;
10. INTERNAL payment-obligation prohibition;
11. zero-total COMMERCIAL support;
12. stable FinancialObligation identity;
13. idempotent payment handoff;
14. canonical ERP billing-fact handoff;
15. Kill Bill provider projection without canonical leakage;
16. idempotent billing-cycle finalisation;
17. concurrency protection;
18. transactional outbox;
19. reconciliation across provider/payment/ERP boundaries;
20. audit and controlled mutation under ADR-SUB-0016.

---

# 102. Required tests

At minimum:

### Billing cycle

- open cycle;
- closing transition;
- finalisation;
- duplicate finalisation;
- concurrent finalisation;
- non-calendar billing period;
- effective BillingTerms version.

### Charges

- recurring charge;
- usage charge;
- one-time charge;
- applicable proration;
- no-proration policy;
- charge provenance.

### Credits and adjustments

- authorised credit;
- duplicate credit request;
- usage correction;
- pricing correction;
- post-finalisation adjustment;
- original history preserved.

### INTERNAL

- zero monetary billing;
- no payment obligation;
- usage statement permitted;
- provider resource cannot create collectible invoice.

### COMMERCIAL

- positive invoice;
- zero-total invoice;
- credits reduce total;
- missing pricing blocks finalisation where required.

### Payments

- obligation emitted once;
- duplicate event safe;
- payment failure does not change charge;
- partial payment;
- no payment for zero obligation.

### Provider

- provider invoice matches;
- amount drift;
- currency drift;
- duplicate provider invoice;
- provider unavailable;
- reconciliation after recovery.

### ERP

- billing fact emitted once;
- ERP failure does not erase invoice;
- reconciliation mismatch remains explicit.

### Tenant/payer

- same-tenant payer;
- authorised central payer;
- unauthorised cross-tenant grouping rejected.

---

# 103. Recommended implementation sequence

```text
1. BillingTerms
       │
       ▼
2. BillingCycle
       │
       ▼
3. Recurring charges
       │
       ▼
4. Usage-charge integration
       │
       ▼
5. Credits / adjustments
       │
       ▼
6. InvoiceProjection
       │
       ▼
7. FinancialObligation
       │
       ▼
8. Payment handoff
       │
       ▼
9. ERP handoff
       │
       ▼
10. Kill Bill invoice projection
       │
       ▼
11. Reconciliation
       │
       ▼
12. Issued-document rendering
```

---

# 104. Complete financial flow

```text
                   CONTROL PLANE
                        │
                        ▼
               ProductSubscription
                        │
                        ▼
              BillingSubscription
                  Projection
                        │
                        ▼
                   BillingTerms
                        │
                        ▼
                   BillingCycle
                   /          \
                  /            \
                 ▼              ▼
          Recurring Charge   Usage Records
                                │
                                ▼
                            Aggregation
                                │
                                ▼
                              Rating
                                │
                                ▼
                           Usage Charge
                  \             /
                   \           /
                    ▼         ▼
                       Charges
                          │
                ┌─────────┴─────────┐
                ▼                   ▼
             Credits            Adjustments
                └─────────┬─────────┘
                          ▼
                 InvoiceProjection
                          │
                          ▼
                FinancialObligation
                    /            \
                   /              \
                  ▼                ▼
          baobab-payments      baobab-erp
                  │                │
                  ▼                ▼
              Payment          Accounting
```

Kill Bill remains parallel provider infrastructure behind the provider boundary:

```text
             baobab-subscriptions
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

It does not sit between Baobab billing and Baobab domain authority.

---

# 105. Relationship to ADR-SUB-0004

ADR-SUB-0004 ends at the creation of an explainable usage-derived billing consequence.

ADR-SUB-0012 incorporates that consequence into the billing-cycle and invoice lifecycle.

```text
ADR-SUB-0004
Usage → Rating → Charge
                    │
                    ▼
ADR-SUB-0012
Cycle → Invoice → Obligation
```

---

# 106. Relationship to ADR-SUB-0006

ADR-SUB-0006 determines the monetary treatment.

ADR-SUB-0012 executes that treatment through billing cycles and financial obligations.

Most importantly:

```text
INTERNAL
   │
   ▼
metering/reporting
   │
   X
   ▼
payment obligation
```

while:

```text
COMMERCIAL
   │
   ▼
billing policy
   │
   ▼
charge/invoice
   │
   ▼
obligation when payable
```

---

# 107. Relationship to ADR-SUB-0015

ADR-SUB-0015 makes Kill Bill a provider projection of this billing model.

Therefore:

```text
Baobab InvoiceProjection
         │
         ▼
BillingProvider
         │
         ▼
Kill Bill representation
```

not the reverse.

---

# 108. Relationship to ADR-SUB-0016

Every material financial mutation defined here is governed by ADR-SUB-0016.

In particular:

```text
credit
adjustment
void
correction
finalisation
financial obligation
provider repair
```

must be:

```text
authenticated
     +
authorised
     +
tenant-scoped
     +
idempotent
     +
revision-safe
     +
auditable
```

---

# 109. Final decision

Baobab SHALL model the subscription financial lifecycle as a sequence of **distinct, traceable financial facts**:

```text
BILLING TERMS
      │
      ▼
BILLING CYCLE
      │
      ▼
CHARGES
      │
      ▼
CREDITS / ADJUSTMENTS
      │
      ▼
INVOICE PROJECTION
      │
      ▼
FINANCIAL OBLIGATION
      │
      ├────────► PAYMENT
      │
      └────────► ACCOUNTING
```

The principal billing rule is:

> **Subscriptions determines what is billable and why; Payments determines how authorised money is moved; ERP determines how the financial consequence is accounted for.**

The principal invoice rule is:

> **A Baobab InvoiceProjection is a canonical billing representation, not a Kill Bill object and not the ERP ledger. Provider, payment and accounting representations may project or consume it without becoming its authority.**

The principal correction rule is:

> **Financial history is corrected by new, linked financial facts—not by silently rewriting the facts that were previously established.**

The principal payer rule is:

> **The payer, subscriber, consuming tenant, PlatformAccount and legal entity may be related but are not assumed to be identical. Central billing requires explicit authority and never collapses tenant isolation.**

The principal INTERNAL rule is:

> **INTERNAL subscriptions may be measured, rated analytically and reported, but they do not create collectible monetary obligations.**

And the principal reconciliation rule is:

> **Baobab billing state, Kill Bill provider state, payment state and ERP accounting state are deliberately separate. Differences are detected and reconciled; no downstream system silently rewrites another system's authority.**