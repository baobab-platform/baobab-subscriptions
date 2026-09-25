# ADR-SUB-0010 — Tax Jurisdiction, Tax Determination, Exemptions and Tax Calculation Boundary

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Tax / Billing Compliance  
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
- ADR-SUB-0011 — Billing Account, Payer, Invoice Recipient and Financial Responsibility Model
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0015 — Kill Bill Adapter, BillingProvider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts

---

# 1. Context

ADR-SUB-0009 establishes canonical pre-tax monetary facts:

```text
BillingTerms
      │
      ▼
Charge Calculation
      │
      ▼
Charge
```

An invoice, however, may require additional monetary treatment:

```text
Base Charge
ZAR 1,000

Tax
ZAR 150

Total
ZAR 1,150
```

The tax result cannot safely be inferred merely from:

- tenant;
- market;
- currency;
- billing provider;
- payer name;
- IP address;
- product price.

Baobab is designed for multi-tenant, multi-market and eventually multi-region operation.

The same Product may therefore participate in transactions involving different:

- supplier jurisdictions;
- customer jurisdictions;
- billing addresses;
- tax registrations;
- tax classifications;
- exemptions;
- place-of-supply rules;
- business/customer classifications;
- tax-inclusive or tax-exclusive prices.

Tax also changes over time.

A rate valid today may not have been valid when a historical Charge became effective.

Therefore:

```text
current tax configuration
```

cannot be treated as the authoritative explanation of:

```text
historical tax result
```

Baobab requires an explicit tax architecture between monetary calculation and invoice construction.

---

# 2. Decision

Baobab SHALL model tax as a distinct, versioned and explainable **tax determination and calculation stage** applied to eligible canonical billing facts.

Conceptually:

```text
Charge
  │
  ▼
Tax Context
  │
  ▼
Tax Determination
  │
  ├── jurisdiction
  ├── tax treatment
  ├── exemption
  ├── tax category
  └── effective rule
  │
  ▼
Tax Calculation
  │
  ▼
Tax Result
  │
  ▼
InvoiceProjection
```

Tax SHALL NOT be hidden inside an unexplained invoice total.

---

# 3. Architectural boundary

`baobab-subscriptions` SHALL own:

- integration of billing facts with tax determination;
- tax context used for subscription billing;
- association of tax results with Charges;
- preservation of tax provenance;
- tax-related invoice projection inputs;
- tax reconciliation of subscription billing facts.

It SHALL NOT become a universal tax authority for the entire Baobab Platform.

---

# 4. Tax authority

Baobab SHALL distinguish:

```text
Tax Configuration
Tax Determination
Tax Calculation
Tax Evidence
Tax Accounting
```

These are related but not identical responsibilities.

---

# 5. Tax is not pricing

Pricing answers:

> What commercial amount does the product/service cost?

Tax answers:

> What tax treatment applies to that taxable transaction?

Therefore:

```text
Price
!=
Tax
```

---

# 6. Tax is not payment

Tax calculation does not execute collection.

```text
Tax Result
     │
     ▼
Financial Obligation
     │
     ▼
baobab-payments
```

---

# 7. Tax is not accounting

A calculated tax amount is not itself a tax-ledger posting.

ERP remains responsible for accounting consequences.

```text
Tax Result
     │
     ▼
Canonical Financial Fact
     │
     ▼
ERP
```

---

# 8. TaxContext

Tax determination SHALL operate on explicit contextual facts.

Conceptually:

```text
TaxContext
────────────────────────

billing_subscription_id

billing_account_id

payer_reference

seller_reference

product_reference

charge_reference

tax_category

customer_type

seller_jurisdiction

customer_jurisdiction

billing_address_reference

supply_location

tax_registration_references

exemption_references

currency

effective_at
```

The exact schema MAY evolve.

---

# 9. Trusted context

Tax-sensitive attributes SHALL originate from trusted canonical sources.

A client SHALL NOT be permitted simply to submit:

```text
"tax_exempt": true
```

and thereby eliminate tax.

---

# 10. Tax jurisdiction

Tax jurisdiction SHALL be explicitly determined.

It SHALL NOT be inferred solely from:

```text
tenant_id
```

or:

```text
market_id
```

---

# 11. Market and tax jurisdiction

A Market may contribute relevant context.

However:

```text
Market
!=
TaxJurisdiction
```

A single market can contain several tax jurisdictions or special rules.

---

# 12. Currency and tax jurisdiction

Likewise:

```text
Currency
!=
TaxJurisdiction
```

Using ZAR does not by itself prove South African tax treatment.

---

# 13. Billing address

Billing address MAY be relevant evidence.

It SHALL NOT automatically be the only determinant of tax jurisdiction.

---

# 14. Seller identity

Tax determination SHALL identify the relevant supplying/selling legal entity where required.

This is particularly important because Baobab tenants are legally independent.

For example:

```text
ZuriBeans
    !=
Thamani Global
```

even if both consume the same platform capabilities.

Their registrations and tax obligations SHALL not be merged.

---

# 15. Legal entity isolation

Tax registrations belong to the appropriate legal entity.

A registration belonging to one tenant/legal entity SHALL NOT automatically apply to another.

---

# 16. Payer is not necessarily customer

ADR-SUB-0011 may establish:

```text
subscriber
consumer
payer
invoice recipient
```

as distinct roles.

Tax determination SHALL not assume they are identical.

---

# 17. Customer classification

Tax treatment MAY depend on customer characteristics such as:

```text
BUSINESS
CONSUMER
GOVERNMENT
OTHER
```

where applicable.

Such classification SHALL be explicit and evidence-backed where legally relevant.

---

# 18. Tax registration

Tax registrations SHALL be represented as governed references.

Conceptually:

```text
TaxRegistration
────────────────────────

registration_id

legal_entity_reference

jurisdiction

registration_type

registration_number

valid_from

valid_until

verification_status
```

Sensitive identifiers SHALL be appropriately protected.

---

# 19. Registration validity

A registration SHALL only be applied for periods in which it is valid.

Historical tax calculations SHALL preserve the registration context used.

---

# 20. Product tax category

Products/services SHALL support explicit tax classification.

Conceptually:

```text
Product
   │
   ▼
TaxCategory
```

TaxCategory SHALL remain distinct from PricingPlan.

---

# 21. Tax category examples

Canonical categories MAY eventually represent concepts such as:

```text
STANDARD_TAXABLE

ZERO_RATED

EXEMPT

OUT_OF_SCOPE

DIGITAL_SERVICE

PROFESSIONAL_SERVICE
```

but exact legal classifications SHALL be defined by authoritative tax configuration rather than guessed from these illustrative names.

---

# 22. Product tax classification is versioned

A change in product tax treatment SHALL be effective-dated.

Historical Charges retain the tax classification applicable at their effective time.

---

# 23. TaxTreatment

Tax determination SHALL produce an explicit treatment.

Conceptually:

```text
TaxTreatment
────────────────────────

taxable

zero-rated

exempt

out-of-scope

reverse-charge

other governed treatment
```

Exact vocabulary SHALL be defined through canonical contracts where shared.

---

# 24. Zero-rated versus exempt

Baobab SHALL NOT conflate:

```text
zero-rated
```

with:

```text
exempt
```

even when both produce zero tax payable on a particular Charge.

Their legal meanings may differ.

---

# 25. Out-of-scope versus zero tax

Likewise:

```text
tax amount = 0
```

does not establish why no tax was charged.

The reason SHALL be preserved.

---

# 26. TaxDecision

The tax determination stage SHALL produce a durable or reproducible decision equivalent to:

```text
TaxDecision
────────────────────────

tax_decision_id

charge_reference

jurisdiction

tax_category

tax_treatment

tax_rule_reference

tax_rule_version

effective_at

exemption_reference?

evidence_references

decision_reason
```

---

# 27. TaxResult

Calculation then produces:

```text
TaxResult
────────────────────────

tax_result_id

tax_decision_id

charge_reference

taxable_amount

currency

tax_rate

tax_amount

tax_inclusive

calculation_reference

created_at
```

The precise structure MAY vary.

---

# 28. Tax result provenance

Every material TaxResult SHALL answer:

```text
Which Charge?

Which jurisdiction?

Which seller?

Which customer/payer context?

Which product tax category?

Which tax rule?

Which rate?

Which effective date?

Which exemption?

Which taxable base?

Which rounding policy?
```

as applicable.

---

# 29. Effective dating

Tax rules SHALL be effective-dated.

Example:

```text
TaxRule v1
effective until T

TaxRule v2
effective from T
```

A historical transaction SHALL use the rule applicable to its relevant tax point/effective time.

---

# 30. Current rates do not rewrite history

If a tax rate changes:

```text
15% → 16%
```

historical invoices subject to the earlier valid rate SHALL not be recalculated merely because the current configuration is 16%.

---

# 31. Tax point

Where applicable, tax determination SHALL preserve the relevant tax-point/time semantics.

The tax point MAY differ from:

- subscription creation;
- billing-period start;
- invoice generation;
- payment date.

The applicable jurisdictional rule determines the meaning.

---

# 32. No universal tax-point assumption

Baobab SHALL NOT establish one global rule such as:

```text
tax point = invoice date
```

for every jurisdiction and transaction.

---

# 33. Tax-exclusive pricing

A PricingPlan MAY define a price as tax-exclusive.

Example:

```text
Base price:
ZAR 1,000

Tax:
ZAR 150

Invoice amount:
ZAR 1,150
```

The distinction SHALL remain explicit.

---

# 34. Tax-inclusive pricing

Pricing MAY instead be tax-inclusive.

Example:

```text
Displayed/contract price:
ZAR 1,150

Net:
ZAR 1,000

Tax:
ZAR 150
```

The calculation SHALL preserve:

```text
gross
net
tax
```

rather than treating 1,150 as an opaque amount.

---

# 35. Inclusive/exclusive flag

Tax inclusion semantics SHALL be part of authoritative pricing/billing terms.

They SHALL NOT be inferred from UI formatting.

---

# 36. Mixed tax treatment

A subscription MAY contain several Charges with different tax treatment.

Example:

```text
Charge A
standard taxable

Charge B
zero-rated

Charge C
out-of-scope
```

Tax SHALL therefore be determined at a sufficiently granular charge/component level.

---

# 37. No invoice-level-only tax assumption

Baobab SHALL NOT assume one tax rate necessarily applies to an entire invoice.

---

# 38. Taxable base

Tax calculation SHALL explicitly identify its taxable base.

For example:

```text
Charge
1,000

eligible discount
-100

taxable base
900
```

if applicable policy requires that ordering.

---

# 39. Credits and tax

Credits and Adjustments MAY have tax consequences.

The system SHALL preserve their relationship to the original taxable transaction where required.

---

# 40. Credit-note semantics

Where a billing correction reduces a previously taxed Charge, the correction workflow SHALL preserve enough information to support the appropriate tax correction/document semantics.

It SHALL NOT merely reduce a current invoice total without lineage.

---

# 41. Debit adjustments

Likewise, an upward adjustment MAY require additional tax calculation.

The adjustment SHALL reference the relevant original or corrected tax context.

---

# 42. Discounts

Whether a discount changes the taxable base SHALL be determined by applicable tax policy.

Baobab SHALL NOT universally assume:

```text
discount always reduces tax base
```

without jurisdictional support.

---

# 43. Credits versus discounts

A commercial discount incorporated into pricing and a post-sale service credit may have different tax treatment.

They SHALL remain distinguishable.

---

# 44. Usage charges

Usage Charges SHALL inherit or resolve tax treatment according to the applicable product/service tax category and jurisdiction.

Raw usage itself is not taxable money.

The calculated Charge is the relevant billing fact.

---

# 45. Proration

Prorated Charges SHALL retain the tax classification applicable to the underlying commercial component.

Proration SHALL not independently redefine tax treatment.

---

# 46. Multi-currency tax

Tax calculation SHALL use an explicit currency.

Where tax reporting requires currency conversion, the conversion SHALL preserve:

- original amount;
- original currency;
- reporting currency;
- FX rate;
- FX source;
- applicable effective time.

---

# 47. No implicit FX

Tax calculation SHALL NOT silently convert currency using a current or unspecified rate.

---

# 48. Exemption

Tax exemption SHALL be an explicit governed decision.

Conceptually:

```text
ExemptionEvidence
────────────────────────

exemption_id

subject_reference

jurisdiction

exemption_type

evidence_reference

valid_from

valid_until

verification_status
```

---

# 49. Exemption evidence

Where exemption requires evidence, the platform SHALL preserve evidence reference and validity.

It SHALL not rely solely on:

```text
tax_exempt = true
```

---

# 50. Expired exemption

An expired exemption SHALL not automatically apply to later Charges.

---

# 51. Retroactive exemption

Retroactive application of exemption SHALL be controlled.

If permitted, historical tax consequences SHALL be corrected through explicit adjustment mechanisms rather than silent rewriting.

---

# 52. Exemption isolation

An exemption belonging to one legal entity/customer SHALL not be applied to another merely because they share:

- parent organisation;
- PlatformAccount;
- tenant group;
- billing contact.

---

# 53. Reverse-charge or recipient-accounted treatment

Where a jurisdiction supports mechanisms under which the supplier does not collect tax but the recipient may account for it, Baobab SHALL represent that as an explicit TaxTreatment.

It SHALL NOT represent it merely as:

```text
tax = 0
```

without explanation.

---

# 54. Cross-border transactions

Cross-border billing may require additional determination inputs.

Conceptually:

```text
Seller jurisdiction
        │
        ├─────────────┐
        │             │
        ▼             ▼
Customer          Supply
jurisdiction      location/type
        │             │
        └──────┬──────┘
               ▼
        Tax Determination
```

No single field SHALL substitute for the determination.

---

# 55. Organisation hierarchy is not tax authority

A parent/subsidiary relationship established under Control Plane organisation relationships does not imply shared tax registration.

Therefore:

```text
same corporate group
!=
same taxpayer
```

unless authoritative legal/tax configuration explicitly establishes otherwise.

---

# 56. PlatformAccount is not taxpayer identity

`PlatformAccount` SHALL NOT be treated automatically as a legal taxpayer identity.

It is a platform construct.

Tax determination SHALL use the appropriate legal-entity/customer context.

---

# 57. Tenant is not taxpayer identity

Likewise, a tenant boundary may correspond closely to a legal entity but the tax architecture SHALL still use explicit legal/tax references rather than relying on an accidental identifier equivalence.

---

# 58. Tax provider abstraction

If Baobab uses an external tax engine/provider, it SHALL be placed behind a Baobab-owned abstraction.

Conceptually:

```text
baobab-subscriptions
       │
       ▼
TaxProvider
       │
       ├── Native/Rules Adapter
       │
       └── External Tax Adapter
```

---

# 59. Provider neutrality

An external tax provider SHALL NOT become the canonical owner of:

- Product;
- Charge;
- tenant;
- legal entity;
- payer;
- tax identity;
- invoice identity.

---

# 60. Provider-specific IDs

Provider IDs SHALL be stored only as external/provider references.

They SHALL NOT replace Baobab canonical identifiers.

---

# 61. TaxProvider port

A conceptual port MAY expose operations equivalent to:

```text
determineTax(context)

calculateTax(context, monetaryFacts)

validateRegistration(...)

validateExemption(...)

health()
```

Exact APIs SHALL be determined during implementation.

---

# 62. Determination versus calculation

The architecture SHOULD preserve the distinction:

```text
Determine:
What tax treatment applies?

Calculate:
Given that treatment, what amount results?
```

A provider MAY perform both internally, but Baobab's conceptual model SHALL retain both.

---

# 63. External-provider response

A provider response SHALL be translated into canonical Baobab TaxDecision and TaxResult structures.

Provider-native objects SHALL not leak into Shared canonical contracts.

---

# 64. Unsupported tax scenario

If required tax treatment cannot be safely determined:

```text
UNKNOWN
```

SHALL NOT silently become:

```text
tax = 0
```

The billing process SHALL fail closed or enter a governed review/blocking state.

---

# 65. Tax determination failure

A COMMERCIAL subscription requiring tax determination SHALL NOT issue a falsely complete invoice when mandatory tax determination has failed.

---

# 66. Failure taxonomy

Tax processing SHOULD distinguish conditions equivalent to:

```text
TAX_CONTEXT_INCOMPLETE

JURISDICTION_UNRESOLVED

TAX_CATEGORY_MISSING

REGISTRATION_INVALID

EXEMPTION_INVALID

RULE_NOT_FOUND

PROVIDER_UNAVAILABLE

CALCULATION_FAILED

RESULT_CONFLICT

MANUAL_REVIEW_REQUIRED
```

---

# 67. Provider outage

A tax-provider outage SHALL not cause the system to invent zero tax.

Depending on policy, the affected billing operation SHALL:

- retry;
- remain pending;
- use an approved deterministic local rule set;
- require manual review.

---

# 68. Caching

Tax configuration MAY be cached where safe.

Caches SHALL respect:

- rule version;
- effective date;
- jurisdiction;
- registration validity;
- exemption validity.

---

# 69. Tax rule versioning

Tax rule changes SHALL create new effective versions rather than silently modifying historical rule meaning.

---

# 70. Rule provenance

A TaxResult SHALL retain a stable reference to the tax rule/version used.

---

# 71. Historical reproducibility

A historical tax result SHALL be explainable without relying on:

- today's tax rate;
- today's customer address;
- today's registration status;
- today's product tax category;
- today's provider defaults.

---

# 72. Rounding

Tax calculation SHALL use deterministic decimal arithmetic and explicit rounding policy.

Binary floating point SHALL NOT determine authoritative tax amounts.

---

# 73. Tax rounding granularity

Jurisdictional rules may require rounding:

- per line;
- per tax component;
- per invoice;
- by another governed method.

Baobab SHALL NOT impose one universal rounding stage without policy.

---

# 74. Invoice reconciliation

InvoiceProjection SHALL preserve enough information to reconcile:

```text
Pre-tax Charges
       │
       ▼
Tax Results
       │
       ▼
Credits / Adjustments
       │
       ▼
Invoice Total
```

---

# 75. Tax result identity

TaxResult SHALL have Baobab canonical identity independent of provider identifiers.

---

# 76. Idempotency

Repeated tax processing of the same canonical calculation revision SHALL not create duplicate TaxResults.

---

# 77. Calculation revision

If a Charge changes through a legitimate recalculation before financial finality, the corresponding tax calculation SHALL be revision-aware.

---

# 78. Issued financial documents

Once tax has participated in an issued financial document, later correction SHALL use explicit correction/adjustment semantics.

The platform SHALL not silently mutate issued tax history.

---

# 79. Tax correction flow

Conceptually:

```text
Original Charge
      │
      ▼
Original TaxResult
      │
      ▼
Issued Invoice
      │
      ▼
Correction discovered
      │
      ▼
Billing Adjustment
      │
      ▼
Tax Recalculation
      │
      ▼
Corrective financial document/fact
```

---

# 80. Invoice responsibility

ADR-SUB-0011 determines payer/invoice-recipient responsibility.

This ADR determines tax treatment.

Neither SHALL silently override the other.

---

# 81. Tax address versus invoice delivery address

The address relevant to tax determination MAY differ from the address used to deliver an invoice.

The data model SHALL permit this distinction.

---

# 82. Tax registration versus payment instrument

A tax registration has no authority over payment execution.

Likewise a payment method does not establish tax status.

---

# 83. ERP integration

ERP SHALL receive sufficient canonical tax facts to perform:

- tax accounting;
- payable/receivable classification;
- statutory reporting support;
- ledger posting.

Subscriptions SHALL not write ERP tax ledgers directly.

---

# 84. Accounting authority

If ERP accounting policy differs in representation from billing-domain TaxResult, ERP remains authoritative for accounting representation.

It SHALL not retroactively redefine the billing calculation.

---

# 85. Kill Bill boundary

Kill Bill MAY support tax plugins or tax-related invoice behaviour.

Such functionality MAY be used only through the Baobab adapter architecture and where it conforms to Baobab tax semantics.

---

# 86. Kill Bill is not tax authority

The existence of a tax amount in Kill Bill SHALL NOT by itself establish canonical Baobab tax correctness.

---

# 87. Provider reconciliation

Where Kill Bill or another provider represents tax information, reconciliation SHALL detect:

```text
missing tax item

duplicate tax item

wrong rate

wrong taxable base

wrong currency

wrong jurisdiction

wrong effective rule
```

where applicable.

---

# 88. Tax event contracts

Cross-engine tax-related events SHALL use canonical Baobab contracts.

They SHALL NOT expose external tax-provider or Kill Bill native schemas as platform contracts.

---

# 89. Sensitive information

Tax records MAY contain sensitive business information.

Access SHALL be restricted according to:

- tenant;
- legal entity;
- role;
- operational purpose.

---

# 90. Tax registration numbers

Registration identifiers SHALL not be indiscriminately emitted into:

- logs;
- telemetry;
- error messages;
- public APIs.

Where displayed on legally required documents, disclosure SHALL be purposeful.

---

# 91. Audit

Material tax decisions SHALL preserve audit evidence sufficient to answer:

```text
Who/what was taxed?

Who supplied it?

Which jurisdiction applied?

Which product tax category applied?

Which tax rule applied?

Which registration was used?

Was an exemption applied?

What was the taxable base?

What rate was applied?

What rounding rule was applied?

What tax amount resulted?

Which effective date governed it?
```

---

# 92. Manual override

Tax overrides SHALL be exceptional.

An override SHALL require:

- explicit permission;
- structured reason;
- affected transaction;
- original determination;
- replacement treatment;
- actor/workload;
- timestamp;
- audit.

---

# 93. No arbitrary tax-rate PATCH

The following SHALL NOT be an ordinary operation:

```text
PATCH /tax-result/{id}

{
  "tax_rate": 0
}
```

---

# 94. Separation of duties

Where appropriate, high-risk tax overrides MAY require approval separate from the initiating actor.

---

# 95. Reconciliation

Tax reconciliation SHALL compare:

```text
Canonical Charge
      │
      ▼
Expected Tax Context
      │
      ▼
Expected Tax Decision
      │
      ▼
Canonical TaxResult
      │
      ▼
InvoiceProjection
      │
      ▼
Provider Representation
```

---

# 96. Reconciliation anomalies

At minimum, detect equivalents of:

```text
MISSING_TAX_RESULT

DUPLICATE_TAX_RESULT

JURISDICTION_MISMATCH

RATE_MISMATCH

TAXABLE_BASE_MISMATCH

EXEMPTION_MISMATCH

REGISTRATION_MISMATCH

RULE_VERSION_MISMATCH

CURRENCY_MISMATCH

PROVIDER_TAX_DRIFT
```

---

# 97. Automatic repair

Auto-repair MAY occur where:

- canonical context is complete;
- applicable rule is deterministic;
- no issued financial history is destructively changed;
- operation is idempotent;
- result is unambiguous.

---

# 98. Manual review

Manual review SHALL be required for ambiguous cases such as:

- conflicting jurisdictions;
- conflicting registration evidence;
- uncertain exemption validity;
- historical provider mismatch;
- issued invoice with material tax discrepancy;
- unresolved cross-border treatment.

---

# 99. Observability

Subscriptions SHOULD expose operational telemetry for:

- tax determinations;
- tax calculation failures;
- unresolved jurisdictions;
- missing tax categories;
- invalid registrations;
- exemption failures;
- provider failures;
- reconciliation mismatches;
- manual overrides.

Sensitive tax identifiers SHALL not be used as uncontrolled metric labels.

---

# 100. Domain invariants

### INV-TAX-01

Tax is distinct from pricing.

### INV-TAX-02

Tax calculation is distinct from payment execution.

### INV-TAX-03

Tax calculation is distinct from accounting.

### INV-TAX-04

Market does not automatically equal TaxJurisdiction.

### INV-TAX-05

Currency does not determine TaxJurisdiction.

### INV-TAX-06

PlatformAccount is not automatically taxpayer identity.

### INV-TAX-07

Corporate-group membership does not imply shared tax registration.

### INV-TAX-08

Tax registration belongs to the appropriate legal entity.

### INV-TAX-09

Tax treatment is effective-dated.

### INV-TAX-10

Historical tax results are not recalculated from current rules.

### INV-TAX-11

Zero-rated, exempt and out-of-scope remain semantically distinct.

### INV-TAX-12

A zero TaxResult retains its reason/provenance.

### INV-TAX-13

Unknown tax treatment does not silently become zero tax.

### INV-TAX-14

Tax-inclusive and tax-exclusive pricing remain explicit.

### INV-TAX-15

One invoice may contain several tax treatments.

### INV-TAX-16

Exemption requires governed evidence where applicable.

### INV-TAX-17

Expired exemption cannot silently apply to future Charges.

### INV-TAX-18

Tax corrections preserve historical evidence.

### INV-TAX-19

Tax calculations use exact decimal arithmetic.

### INV-TAX-20

Tax rounding policy is explicit.

### INV-TAX-21

Provider tax objects do not become canonical Baobab authority.

### INV-TAX-22

TaxResult has Baobab canonical identity.

### INV-TAX-23

Tax processing is idempotent.

### INV-TAX-24

Issued tax history cannot be silently destructively rewritten.

### INV-TAX-25

Tax-sensitive context must originate from trusted sources.

### INV-TAX-26

Tax overrides are controlled and auditable.

### INV-TAX-27

Tax determination failure cannot silently create a falsely complete invoice.

### INV-TAX-28

ERP remains authoritative for accounting treatment of tax.

---

# 101. Alternatives considered

## 101.1 Embed tax directly in pricing

**Rejected.**

Pricing and tax change under different authorities and rules.

## 101.2 Infer tax entirely from market

**Rejected.**

Market is not sufficient tax-jurisdiction evidence.

## 101.3 Infer tax from currency

**Rejected.**

Currency does not establish jurisdiction.

## 101.4 Trust a tenant-provided tax-exempt flag

**Rejected.**

Tax exemptions require governed evidence and authorization.

## 101.5 Let Kill Bill define tax truth

**Rejected.**

This would make provider behaviour canonical.

## 101.6 Treat every zero-tax result identically

**Rejected.**

Zero-rated, exempt, out-of-scope and reverse-charge treatments have different meanings.

## 101.7 Use today's tax rate for historical recalculation

**Rejected.**

It destroys historical correctness.

## 101.8 Mutate issued tax results when corrections occur

**Rejected.**

Corrections require append-oriented financial history.

## 101.9 Make ERP calculate subscription invoice tax without canonical billing context

**Rejected.**

It would collapse billing and accounting boundaries and risk divergence.

---

# 102. Consequences

## Positive

- Tax becomes explainable and auditable.
- Multi-market operation remains extensible.
- Legal-entity boundaries remain intact.
- Historical invoices remain reproducible.
- Cross-border billing can evolve without redesigning Charge.
- Tax providers remain replaceable.
- Kill Bill remains subordinate.
- ERP receives canonical tax facts rather than provider-specific records.
- Tax exemptions and overrides become governed.

## Negative

- Tax requires substantial configuration.
- Jurisdiction determination can become complex.
- External tax-provider integration may eventually be necessary.
- Tax evidence requires lifecycle management.
- Cross-border scenarios require specialist validation.
- Country-specific rules cannot safely be hard-coded from assumptions.

These costs are accepted because tax ambiguity directly creates compliance and financial risk.

---

# 103. Implementation requirements

A conforming implementation SHALL provide:

1. TaxContext;
2. explicit seller/legal-entity context;
3. explicit customer/payer tax context;
4. TaxJurisdiction resolution;
5. Product TaxCategory;
6. TaxTreatment;
7. TaxDecision;
8. TaxResult;
9. effective-dated tax rules;
10. tax-inclusive/exclusive semantics;
11. tax-registration references;
12. exemption evidence;
13. exact decimal calculation;
14. explicit tax rounding;
15. correction semantics;
16. credit/adjustment tax integration;
17. multi-currency provenance where applicable;
18. idempotency;
19. provider abstraction if external tax calculation is used;
20. provider reconciliation;
21. ERP integration boundary;
22. audit;
23. observability;
24. controlled override mechanisms.

---

# 104. Required tests

At minimum:

## Jurisdiction

- seller and customer same jurisdiction;
- cross-border context;
- market differing from tax jurisdiction;
- currency differing from jurisdiction;
- unresolved jurisdiction.

## Product treatment

- standard taxable;
- zero-rated;
- exempt;
- out-of-scope;
- missing TaxCategory.

## Rates

- current rate;
- future rate;
- historical rate;
- rate change at effective boundary.

## Pricing

- tax-exclusive price;
- tax-inclusive price;
- multiple tax treatments on one billing account;
- legitimate zero tax.

## Registration

- valid registration;
- expired registration;
- registration belonging to another legal entity;
- missing required registration.

## Exemption

- valid exemption;
- expired exemption;
- invalid exemption;
- exemption belonging to another subject;
- retroactive correction.

## Adjustments

- taxable credit;
- tax correction;
- debit adjustment;
- issued-invoice correction.

## Precision

- exact decimal calculation;
- line-level rounding;
- alternative configured rounding;
- currency precision.

## Provider

- provider unavailable;
- duplicate provider result;
- provider rate mismatch;
- provider jurisdiction mismatch.

## Security

- untrusted `tax_exempt=true` rejected;
- unauthorised tax override rejected;
- cross-tenant registration access rejected;
- override audit generated.

---

# 105. Recommended implementation sequence

```text
1. Tax domain primitives
        │
        ▼
2. TaxContext
        │
        ▼
3. TaxCategory
        │
        ▼
4. Jurisdiction determination
        │
        ▼
5. Registration model
        │
        ▼
6. Exemption model
        │
        ▼
7. TaxDecision
        │
        ▼
8. TaxResult
        │
        ▼
9. Charge integration
        │
        ▼
10. Credits / adjustments
        │
        ▼
11. Provider abstraction
        │
        ▼
12. Invoice integration
        │
        ▼
13. ERP integration
        │
        ▼
14. Reconciliation / audit
```

---

# 106. Architectural progression

The subscription billing pipeline now becomes:

```text
ADR-SUB-0002
Domain / authority
        │
        ▼
ADR-SUB-0003
Projection lifecycle
        │
        ▼
ADR-SUB-0004
Usage / rating
        │
        ▼
ADR-SUB-0005
Catalogue / pricing
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
Charges / monetary facts
        │
        ▼
ADR-SUB-0010
Tax determination
        │
        ▼
ADR-SUB-0011
Financial responsibility
        │
        ▼
ADR-SUB-0012
Invoice / obligation
lifecycle
```

---

# 107. Worked example — tax exclusive

Suppose:

```text
Seller:
Legal Entity A

Customer:
Business B

Charge:
ZAR 1,000

Tax treatment:
standard taxable

Applicable rate:
15%

Pricing:
tax exclusive
```

The canonical result becomes:

```text
Charge
ZAR 1,000
     │
     ▼
TaxDecision
jurisdiction = J
treatment = STANDARD
rule = R-v3
     │
     ▼
TaxResult
taxable base = ZAR 1,000
tax = ZAR 150
     │
     ▼
InvoiceProjection

Net       1,000
Tax         150
────────────────
Total     1,150 ZAR
```

The invoice total is therefore explainable from separate canonical facts.

---

# 108. Worked example — tax inclusive

Suppose BillingTerms establish:

```text
Customer price:
ZAR 1,150

tax inclusive
```

and the applicable rule yields the corresponding tax component.

Baobab SHALL preserve:

```text
Gross
1,150

Net
1,000

Tax
150
```

rather than representing:

```text
Charge = 1,150
Tax = unknown
```

---

# 109. Worked example — zero tax with different meanings

These three outcomes may all result in:

```text
Tax Amount = 0
```

but remain distinct:

```text
Charge A
ZERO_RATED
rate = 0%

Charge B
EXEMPT
no tax collected because exemption treatment applies

Charge C
OUT_OF_SCOPE
transaction outside applicable tax scope
```

Baobab SHALL preserve those differences.

---

# 110. Worked example — tax rule change

Consider:

```text
Tax Rule v1
effective before 01 Jan
rate = X

Tax Rule v2
effective from 01 Jan
rate = Y
```

Two Charges:

```text
Charge A
effective 20 Dec
     │
     ▼
Tax Rule v1

Charge B
effective 10 Jan
     │
     ▼
Tax Rule v2
```

Generating both invoices in January SHALL NOT cause Charge A automatically to use Rule v2.

---

# 111. Worked example — corporate group

Suppose:

```text
Parent Group
   │
   ├── Subsidiary A
   │      tax registration A
   │
   └── Subsidiary B
          tax registration B
```

Baobab SHALL NOT infer:

```text
registration A
=
registration B
```

from their shared parent.

This remains important for Nabhold Group Africa and any future Baobab customer whose PlatformAccount contains multiple legal entities.

---

# 112. Final Decision

Baobab SHALL maintain a first-class, effective-dated and provider-independent **tax determination and calculation boundary** between canonical monetary Charges and invoice construction.

The principal tax rule is:

> **Tax is a governed consequence of a taxable billing fact; it is not an implicit attribute of price, market, currency, tenant or billing provider.**

The principal jurisdiction rule is:

> **Tax jurisdiction must be explicitly determined from authoritative transaction context rather than inferred from a single convenience identifier.**

The principal legal-entity rule is:

> **Corporate relationship, tenant grouping or shared PlatformAccount does not merge the tax identities or registrations of legally distinct entities.**

The principal historical rule is:

> **Historical tax results remain bound to the effective rules, registrations, exemptions and evidence that applied to the original transaction.**

The principal zero-value rule is:

> **Zero-rated, exempt, out-of-scope and otherwise zero-tax outcomes retain distinct semantic provenance even where their monetary tax amount is identical.**

The principal failure rule is:

> **Unresolved tax treatment is not zero tax. Mandatory tax uncertainty blocks or escalates billing rather than manufacturing a convenient financial result.**

The principal provider rule is:

> **Kill Bill or any future external tax provider may calculate or represent tax through a Baobab-owned adapter, but provider objects and decisions never replace Baobab canonical identity, provenance and governance.**

The principal correction rule is:

> **Tax corrections preserve original financial history and proceed through explicit adjustments rather than silent mutation of issued financial facts.**

And the principal accounting boundary is:

> **Subscriptions determines and preserves the tax consequence of subscription billing; Payments handles monetary movement; ERP owns the resulting accounting and statutory ledger treatment.**