# ADR-SUB-0005 — Subscription Catalogue, Pricing, Plan Versioning and Billing Terms Resolution

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Subscription Pricing / Catalogue  
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
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-018 — Canonical Organisation and Tenant Relationships
- ADR-SHARED-007 — Capability Contracts
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- `shared/contracts/product/v1`
- `shared/contracts/subscriptions/v1`

---

# 1. Context

Baobab requires subscription pricing that can support:

- multiple products;
- multiple capabilities;
- multiple markets;
- multiple currencies;
- recurring subscriptions;
- usage-based subscriptions;
- hybrid recurring-and-usage pricing;
- contractual pricing;
- internal subscriptions;
- external commercial subscriptions;
- trials and allowances;
- future pricing models.

The platform must initially support markets such as Uganda and South Africa while remaining extensible to additional jurisdictions and commercial models.

Pricing therefore cannot be represented merely as:

```text
product.price = 100
```

A meaningful subscription price may depend upon:

```text
Product
   +
Product Version
   +
Subscription Plan
   +
Price Components
   +
Market
   +
Currency
   +
Billing Frequency
   +
Usage Rules
   +
Effective Time
   +
Commercial Agreement
```

Furthermore, ADR-SUB-0006 establishes that subscription **classification** and subscription **pricing** are separate concepts.

For example:

```text
classification = COMMERCIAL
```

does not itself mean:

```text
price = ZAR 500
```

Likewise:

```text
classification = INTERNAL
```

does not mean that the catalogue or commercial-equivalent price ceases to exist.

The platform may legitimately know that an internally consumed capability has a commercial-equivalent price while still producing:

```text
payable amount = 0
```

because classification policy controls monetary treatment.

Another important boundary exists around Kill Bill.

Kill Bill has its own catalogue, plans, phases, price lists and billing concepts.

Those provider concepts are useful implementation mechanisms, but they SHALL NOT become the canonical Baobab product-pricing model.

Baobab therefore requires its own provider-independent subscription catalogue and pricing architecture.

---

# 2. Decision

Baobab SHALL define subscription pricing through canonical, versioned, effective-dated commercial definitions independent of Kill Bill.

The conceptual model SHALL be:

```text
Product
   │
   ▼
ProductVersion
   │
   ▼
SubscriptionOffering
   │
   ▼
PricingPlan
   │
   ├──────────────┐
   ▼              ▼
Recurring       Usage
Component       Component
   │              │
   └──────┬───────┘
          ▼
    PriceDefinition
          │
          ▼
 Market / Currency
          │
          ▼
Commercial Override
   where authorised
          │
          ▼
ResolvedBillingTerms
          │
          ▼
BillingSubscriptionProjection
```

Provider translation occurs only afterwards:

```text
ResolvedBillingTerms
        │
        ▼
BillingProvider
        │
        ▼
KillBillAdapter
        │
        ▼
Kill Bill Catalogue / Plan
```

The provider representation SHALL NOT become canonical pricing authority.

---

# 3. Core distinction

Baobab SHALL distinguish:

```text
Product
    !=
SubscriptionOffering
    !=
PricingPlan
    !=
PriceDefinition
    !=
BillingTerms
    !=
ProviderPlan
```

These concepts may reference one another.

They are not interchangeable.

---

# 4. Product authority

The canonical Product and ProductVersion remain governed by the appropriate Baobab product/control-plane contracts.

`baobab-subscriptions` SHALL NOT create an independent competing definition of the product itself.

The subscriptions engine consumes product identity necessary for billing.

---

# 5. SubscriptionOffering

A `SubscriptionOffering` SHALL describe a commercially subscribable form of a Product/ProductVersion.

Conceptually:

```text
SubscriptionOffering
────────────────────────────

offering_id

product_id
product_version_id

name
description

availability

effective_from
effective_until

supported_markets

pricing_plan_refs

metadata
```

The exact canonical representation SHALL be governed through Shared where cross-engine interoperability requires it.

---

# 6. Offering versus Product

A Product answers:

> What platform product exists?

An offering answers:

> In what commercially subscribable form is that product made available?

One Product MAY therefore have multiple offerings.

Example:

```text
Baobab Intelligence Product
            │
            ├── Standard Offering
            ├── Professional Offering
            └── Enterprise Offering
```

This does not create three canonical products.

---

# 7. PricingPlan

A `PricingPlan` SHALL define a versioned set of pricing components applicable to an offering.

Conceptually:

```text
PricingPlan
────────────────────────────

pricing_plan_id
version

offering_id

name

billing_frequency

effective_from
effective_until

components[]

supported_markets
supported_currencies

status
```

---

# 8. Pricing components

A plan MAY contain one or more components.

Examples include:

```text
RECURRING
USAGE
ONE_TIME
ALLOWANCE
```

or canonical equivalents.

This permits:

```text
Monthly platform fee
        +
usage overage
```

without pretending these are separate subscriptions.

---

# 9. Recurring component

A recurring component MAY conceptually define:

```text
RecurringPriceComponent
────────────────────────

component_id

frequency

amount
currency

billing_timing

proration_policy

effective_from
effective_until
```

The precise contract MAY evolve.

---

# 10. Usage component

Usage pricing SHALL reference canonical usage metrics established under ADR-SUB-0004.

Conceptually:

```text
UsagePriceComponent
─────────────────────────

component_id

metric_id

rating_model

tiers / rates

allowance

currency

effective_from
effective_until
```

Pricing SHALL NOT redefine the metric itself.

---

# 11. Hybrid plans

Baobab SHALL support plans combining components.

For example:

```text
Professional Plan

Monthly recurring fee:
    ZAR 2,000

Included API calls:
    50,000

Additional API calls:
    ZAR 0.02 each
```

Conceptually:

```text
PricingPlan
    │
    ├── RecurringComponent
    │       ZAR 2,000/month
    │
    └── UsageComponent
            metric = api.request
            allowance = 50,000
            rate = ZAR 0.02
```

---

# 12. Fixed pricing

The architecture SHALL support straightforward fixed subscription pricing.

Example:

```text
ZAR 500 / month
```

Fixed pricing SHALL use the same versioning and effective-time rules as more complex pricing.

Simple pricing is not exempt from historical correctness.

---

# 13. Usage pricing

Usage pricing SHALL support rating models defined by ADR-SUB-0004.

Examples MAY include:

- per-unit;
- tiered;
- graduated;
- volume;
- included allowance plus overage.

The catalogue defines the applicable commercial rule.

Usage ingestion merely records consumption.

---

# 14. One-time components

A plan MAY include authorised one-time billing components.

Examples may include:

- onboarding fee;
- setup fee;
- activation fee.

Such charges SHALL remain distinct from recurring subscription price.

---

# 15. Currency

Every monetary PriceDefinition SHALL explicitly specify currency.

For example:

```text
amount = 500
currency = ZAR
```

not:

```text
amount = 500
```

Currency SHALL never be inferred from the numeric amount.

---

# 16. Multi-currency catalogue

Baobab SHALL permit one commercial offering to have prices in multiple supported currencies.

For example:

```text
Professional Plan
        │
        ├── ZA
        │    └── ZAR price
        │
        └── UG
             └── UGX price
```

This does not imply dynamic currency conversion.

Each may be an independently governed commercial price.

---

# 17. Market-specific pricing

Price MAY vary by Market.

Conceptually:

```text
PriceDefinition
────────────────────────

pricing_plan_id

market_id

currency

amount / rating rules

effective_from
effective_until
```

The applicable market SHALL be resolved from trusted platform context rather than browser-controlled pricing selection.

---

# 18. Market does not imply currency exclusively

The platform SHALL NOT hard-code:

```text
ZA = ZAR only
UG = UGX only
```

Commercial policy may legitimately support another billing currency.

Market and currency remain separate dimensions.

---

# 19. No automatic FX assumption

A price in one currency SHALL NOT automatically imply a price in another.

For example:

```text
ZAR 1,000
```

does not automatically create:

```text
UGX equivalent
```

unless an explicit FX/pricing policy authorises such derivation.

---

# 20. FX-derived prices

If future commercial policy permits FX-derived pricing, the resulting price resolution SHALL preserve:

- source price;
- source currency;
- FX rate;
- FX source;
- effective time;
- target currency;
- rounding.

Dynamic FX SHALL not erase the original pricing provenance.

---

# 21. Effective dating

Every material pricing version SHALL have explicit effective-time semantics.

Conceptually:

```text
Price v1
effective:
2026-01-01 → 2026-06-30

Price v2
effective:
2026-07-01 →
```

This allows historical billing to remain reproducible.

---

# 22. Price changes are new versions

A material price change SHALL normally create a new price/version rather than mutate historical pricing.

Preferred:

```text
Plan v1 = ZAR 500
Plan v2 = ZAR 600
```

Not:

```text
UPDATE plan
SET price = 600
```

where that update destroys evidence that prior subscribers were billed under ZAR 500 terms.

---

# 23. Published pricing immutability

Once a pricing version has produced subscription BillingTerms or financial consequences, its financially relevant meaning SHALL be immutable.

Corrections SHALL use explicit supersession/correction/versioning semantics.

---

# 24. Draft pricing

Pricing definitions MAY support lifecycle states equivalent to:

```text
DRAFT
   │
   ▼
ACTIVE
   │
   ▼
RETIRED
```

A DRAFT price SHALL NOT be used for production subscription resolution.

---

# 25. Retired pricing

Retiring a pricing plan SHALL prevent inappropriate new selection.

It SHALL NOT automatically invalidate existing subscriptions whose BillingTerms legitimately reference that version.

---

# 26. Grandfathering

Baobab SHALL support grandfathered subscription pricing.

Example:

```text
Existing subscriber:
    ZAR 500/month

New subscribers:
    ZAR 600/month
```

The new catalogue version SHALL not silently alter the existing subscriber's BillingTerms unless migration policy explicitly requires it.

---

# 27. BillingTerms resolution

When a ProductSubscription becomes billable, subscriptions SHALL resolve the applicable commercial terms.

Conceptually:

```text
ProductSubscription
        │
        ▼
ProductVersion
        │
        ▼
SubscriptionOffering
        │
        ▼
PricingPlan
        │
        ▼
Market
        │
        ▼
Currency
        │
        ▼
Commercial Agreement
        │
        ▼
Classification Policy
        │
        ▼
ResolvedBillingTerms
```

Resolution SHALL be deterministic.

---

# 28. ResolvedBillingTerms

Resolved BillingTerms SHALL capture enough information to reproduce the billing decision.

Conceptually:

```text
ResolvedBillingTerms
─────────────────────────────

billing_terms_id

billing_subscription_id

product_id
product_version_id

offering_id

pricing_plan_id
pricing_plan_version

classification

market_id

currency

price_components

commercial_agreement_ref
where applicable

billing_frequency

effective_from
effective_until

resolution_reason
resolved_at
```

---

# 29. Resolution snapshot

BillingTerms SHALL preserve either:

- immutable references to historical pricing definitions; or
- an appropriate immutable snapshot;

such that future catalogue changes cannot alter historical billing interpretation.

---

# 30. Resolution provenance

Baobab SHALL be able to explain:

```text
Why did subscription X receive these billing terms?
```

The answer SHOULD identify:

- ProductVersion;
- offering;
- plan;
- plan version;
- market;
- currency;
- applicable commercial agreement;
- classification;
- effective date;
- resolution policy.

---

# 31. Catalogue price versus BillingTerms

Catalogue pricing represents what is commercially available.

BillingTerms represent what applies to a specific subscription.

Therefore:

```text
Catalogue Price
      !=
Resolved BillingTerms
```

A catalogue change does not automatically rewrite an existing subscription.

---

# 32. Standard pricing

Standard pricing SHALL be the default commercial price when no authorised override applies.

Conceptually:

```text
Offering
   │
   ▼
Standard PricingPlan
   │
   ▼
Market Price
```

---

# 33. Contract-specific pricing

Baobab SHALL support explicit commercial agreements where a customer has negotiated pricing.

Conceptually:

```text
Standard Price
      │
      ▼
Commercial Agreement?
    /       \
   no       yes
   │         │
   ▼         ▼
standard   authorised
terms      override
```

---

# 34. CommercialAgreement

Where required, a commercial agreement MAY conceptually include:

```text
CommercialAgreement
────────────────────────────

agreement_id

billing_account / customer reference

product/offering scope

pricing overrides

currency

effective_from
effective_until

approval_reference

status
```

This ADR does not make subscriptions the canonical CRM/contract repository.

Subscriptions requires only the governed pricing consequence/reference.

---

# 35. Contract override authority

A tenant SHALL NOT manufacture contract pricing through client input.

For example:

```json
{
  "monthly_price": "1.00"
}
```

SHALL NOT override an authoritative ZAR 10,000 plan merely because the request is authenticated.

Commercial overrides require explicit platform authority.

---

# 36. Override precedence

Price resolution SHALL have deterministic precedence.

Conceptually:

```text
Authoritative Commercial Agreement
              │
              ▼
      Market-specific Plan
              │
              ▼
        Standard Plan
```

The exact precedence SHALL be encoded in canonical policy rather than distributed across controllers.

---

# 37. No hidden overrides

A price override SHALL have explicit provenance.

The system SHALL NOT support unexplained:

```text
effective_price = 734.22
```

without identifying why that value applies.

---

# 38. Classification separation

Classification and pricing SHALL remain orthogonal.

```text
Classification
     │
     ▼
monetary treatment

PricingPlan
     │
     ▼
commercial valuation
```

This enables:

```text
INTERNAL subscription
+
commercial-equivalent price known
+
payable amount = zero
```

without destroying catalogue information.

---

# 39. INTERNAL resolution

For INTERNAL ProductSubscriptions, subscriptions MAY resolve the applicable catalogue/commercial-equivalent pricing for:

- shadow valuation;
- cost analysis;
- usage economics;
- planning.

However:

```text
classification = INTERNAL
```

SHALL cause ADR-SUB-0006 monetary policy to enforce:

```text
payable = zero
payment_required = false
```

---

# 40. INTERNAL is not a zero-price plan

Baobab SHALL NOT model INTERNAL merely as:

```text
plan = FREE
```

because that collapses two different concepts:

```text
commercial price
```

and:

```text
subscription monetary treatment
```

An internal subscription may consume a product whose external commercial price is substantial.

---

# 41. COMMERCIAL resolution

A COMMERCIAL ProductSubscription requiring billing SHALL resolve valid pricing.

If required pricing cannot be resolved:

```text
COMMERCIAL
   +
missing required pricing
```

the result SHALL be:

```text
PENDING_CONFIGURATION / BLOCKED
```

or equivalent governed state.

It SHALL NOT silently become free.

---

# 42. Missing price fail-closed

The following is prohibited:

```text
COMMERCIAL
+
price lookup failed
=
amount = 0
```

Zero is a valid price only where an authoritative pricing rule explicitly says zero.

Missing is not zero.

---

# 43. Zero-price commercial offering

Baobab MAY support a legitimate COMMERCIAL zero-price offering.

For example:

```text
classification = COMMERCIAL
plan price = 0
```

This remains distinct from:

```text
classification = INTERNAL
```

The provenance SHALL make the difference explicit.

---

# 44. Trial pricing

A commercial plan MAY include a trial phase.

Conceptually:

```text
Trial
   │
   ▼
ZAR 0 for 30 days
   │
   ▼
Paid phase
   │
   ▼
ZAR 500/month
```

This SHALL remain COMMERCIAL throughout unless Control Plane classification says otherwise.

---

# 45. Pricing phases

A PricingPlan MAY support versioned phases such as:

```text
TRIAL
INTRODUCTORY
STANDARD
```

where commercial requirements justify them.

Phase transitions SHALL be deterministic and effective-dated.

---

# 46. Allowances

A PricingPlan MAY define included usage allowances.

Example:

```text
Monthly fee:
    ZAR 1,000

Included:
    10,000 API requests

Overage:
    ZAR 0.02/request
```

Allowance semantics SHALL integrate with ADR-SUB-0004.

---

# 47. Discounts

Discounts SHALL be explicit pricing/billing facts.

A discount SHOULD identify:

- rule/reference;
- scope;
- value;
- effective period;
- authorization;
- stacking rules where applicable.

A discount SHALL not be implemented as unexplained price mutation.

---

# 48. Discount versus credit

A discount affects price determination.

A credit reduces an established billing consequence.

Conceptually:

```text
Base price
    │
    ▼
Discount
    │
    ▼
Charge
    │
    ▼
Credit
```

The two SHALL remain distinguishable.

---

# 49. Percentage discounts

Where percentage discounts are supported, calculation order and rounding SHALL be explicit.

For example:

```text
base = ZAR 1,000
discount = 10%
result = ZAR 900
```

The system SHALL define how multiple discounts interact.

---

# 50. Price precision

Price definitions SHALL use exact decimal monetary representation.

Binary floating-point SHALL NOT be used for authoritative financial calculations.

---

# 51. Rounding

Pricing policy SHALL define deterministic rounding where calculations produce fractional monetary values.

The policy SHALL specify:

- precision;
- currency minor-unit treatment;
- rounding mode;
- calculation stage.

---

# 52. Price display versus billing price

Display formatting SHALL remain separate from authoritative price.

For example:

```text
Authoritative:
    1000.00 ZAR

Display:
    R1,000.00
```

UI formatting SHALL not become pricing authority.

---

# 53. Tax inclusion

Price definitions SHALL explicitly indicate or reference whether displayed/commercial pricing is:

- tax-exclusive;
- tax-inclusive;

where relevant.

Tax calculation itself remains a separate concern.

---

# 54. No tax inference from market price

The engine SHALL NOT assume that a price in ZA automatically includes VAT or that another market price automatically excludes tax.

Tax semantics SHALL be explicit.

---

# 55. Product version relationship

Pricing MAY differ between ProductVersions.

A new ProductVersion does not necessarily require a new price.

Likewise, a price change does not necessarily require a new ProductVersion.

These dimensions SHALL remain independently versioned.

---

# 56. Capability composition

A Product may compose multiple capabilities under ADR-BCP-005.

Pricing MAY apply:

```text
to the whole Product
```

or, where commercial policy explicitly supports it:

```text
to specific billable components/capabilities
```

Subscriptions SHALL NOT infer entitlement from price components.

---

# 57. Price does not create entitlement

The existence of:

```text
price_component = AI_ANALYTICS
```

does not itself grant:

```text
CapabilityGrant(AI_ANALYTICS)
```

Control Plane remains entitlement authority.

---

# 58. Entitlement does not determine price

Likewise, a CapabilityGrant does not itself establish a monetary amount.

Pricing comes from authoritative commercial terms.

---

# 59. Billing frequency

Supported frequencies MAY include:

```text
MONTHLY
QUARTERLY
ANNUAL
```

and other explicit contractual periods.

The exact canonical vocabulary SHALL be governed centrally.

---

# 60. Frequency is not duration assumption

`MONTHLY` SHALL be interpreted according to explicit billing-calendar semantics.

The engine SHALL NOT assume:

```text
monthly = 30 days
```

unless the relevant policy explicitly defines that behaviour.

---

# 61. Billing anchor

Pricing/BillingTerms SHALL establish the applicable billing anchor where recurring billing requires one.

Examples:

- subscription anniversary;
- first day of month;
- contract-defined date.

The anchor SHALL be explicit and reproducible.

---

# 62. Proration policy

Pricing plans requiring proration SHALL reference explicit proration policy.

The price itself SHALL not implicitly determine how partial periods are calculated.

---

# 63. Price migration

Moving an existing subscription to a new pricing plan SHALL be an explicit controlled transition.

Conceptually:

```text
Current BillingTerms
        │
        ▼
Price Change Decision
        │
        ▼
effective date
        │
        ▼
New BillingTerms version
```

Historical BillingTerms remain.

---

# 64. No silent migration

Publishing:

```text
Plan v2
```

SHALL NOT automatically move every v1 subscription to v2.

Migration policy must explicitly state which subscriptions move and when.

---

# 65. Prospective migration

The default pricing-change model SHOULD be prospective:

```text
old terms
    │
    ▼
effective boundary
    │
    ▼
new terms
```

rather than retroactive re-rating.

---

# 66. Retroactive pricing correction

Retroactive pricing changes SHALL be exceptional.

They require:

- governed reason;
- authority;
- affected period;
- old pricing reference;
- corrected pricing reference;
- recalculation;
- resulting adjustment;
- audit.

Historical charges SHALL not simply be overwritten.

---

# 67. Contract renewal

Contract renewal MAY resolve a new PricingPlan or BillingTerms version.

Renewal SHALL not be treated as an invisible mutation of the original contract terms.

---

# 68. Plan switching

Switching between plans SHALL have explicit:

- source plan;
- target plan;
- effective date;
- proration treatment;
- allowance treatment;
- billing-cycle treatment.

Plan switching is a domain transition, not arbitrary foreign-key replacement.

---

# 69. Downgrade/upgrade semantics

Terms such as:

```text
upgrade
downgrade
```

are commercial interpretations.

The canonical operation is a governed plan transition.

Subscriptions SHALL not infer that a plan is an upgrade merely from its price.

---

# 70. Kill Bill catalogue boundary

Kill Bill may require provider-native:

- products;
- plans;
- phases;
- price lists;
- currencies.

These SHALL be generated/mapped through the Kill Bill adapter.

Conceptually:

```text
Baobab PricingPlan
       │
       ▼
Provider Mapping
       │
       ▼
Kill Bill Plan
```

---

# 71. Kill Bill does not own Baobab pricing

The following architecture is prohibited:

```text
Kill Bill catalogue
       │
       ▼
becomes Baobab canonical catalogue
```

The provider is an execution mechanism.

Baobab remains the source of canonical commercial semantics.

---

# 72. Provider mapping

Provider mappings SHALL preserve:

```text
Baobab pricing_plan_id
Baobab pricing_plan_version

provider
provider_plan_reference
provider_catalogue_reference
```

or equivalent information.

Mappings SHALL be auditable and version-aware.

---

# 73. Provider capability mismatch

If a Baobab pricing model cannot be represented directly in Kill Bill, the adapter SHALL NOT silently alter its meaning.

The system SHALL instead:

- compose provider capabilities safely;
- retain part of rating in Baobab;
- mark unsupported configuration;
- or fail closed.

Commercial semantics take precedence over provider convenience.

---

# 74. Provider pricing drift

Reconciliation SHALL detect material differences between expected Baobab pricing and provider configuration.

Examples:

| Drift | Example |
|---|---|
| Missing plan | Baobab plan has no provider mapping |
| Wrong currency | Baobab ZAR mapped to provider USD |
| Wrong amount | Baobab 500 mapped to provider 550 |
| Wrong frequency | monthly mapped to annual |
| Stale plan | provider still uses superseded version |
| Duplicate mapping | multiple provider plans ambiguously mapped |

---

# 75. Provider drift behaviour

Financially material pricing drift SHALL block unsafe billing and require repair/reconciliation.

The provider SHALL not silently become authoritative because it already contains configuration.

---

# 76. Catalogue publication

Production pricing SHOULD use controlled publication.

Conceptually:

```text
DRAFT
   │
   ▼
validate
   │
   ▼
approve
   │
   ▼
publish
   │
   ▼
ACTIVE
```

The exact approval workflow may be defined separately.

---

# 77. Catalogue validation

Before publication, pricing SHOULD be validated for:

- product/offering existence;
- version integrity;
- supported market;
- supported currency;
- amount precision;
- effective-date overlap;
- metric existence;
- tier correctness;
- allowance correctness;
- billing frequency;
- provider compatibility where required.

---

# 78. Overlapping prices

The system SHALL reject ambiguous active prices where the resolution algorithm cannot deterministically choose one.

For example:

```text
Plan P
Market ZA
Currency ZAR

Price A effective Jan–Dec
Price B effective Jun–Sep
```

without explicit precedence is ambiguous.

---

# 79. Pricing resolution determinism

Given identical authoritative inputs:

```text
ProductSubscription
ProductVersion
Market
Currency
Effective Time
Commercial Agreement
Classification
```

pricing resolution SHALL produce the same BillingTerms.

---

# 80. Resolution failure

Pricing resolution SHALL return a structured reason when terms cannot be resolved.

Examples:

```text
NO_ACTIVE_OFFERING
NO_ACTIVE_PRICING_PLAN
MARKET_NOT_SUPPORTED
CURRENCY_NOT_SUPPORTED
AMBIGUOUS_PRICE
COMMERCIAL_AGREEMENT_INVALID
PROVIDER_MAPPING_MISSING
```

or canonical equivalents.

---

# 81. PENDING_CONFIGURATION

Where a COMMERCIAL subscription cannot be safely billed because pricing configuration is incomplete, ADR-SUB-0003's:

```text
PENDING_CONFIGURATION
```

state SHALL be used where appropriate.

The blocker SHALL be precise.

---

# 82. Catalogue cache

Pricing/catalogue data MAY be cached for performance.

Caching SHALL NOT violate:

- version correctness;
- effective dates;
- tenant/commercial agreement isolation;
- publication changes.

Cache keys SHALL include all dimensions material to price resolution.

---

# 83. No leaf-UI price authority

Digital estates may display prices.

They SHALL NOT calculate authoritative subscription pricing independently.

Conceptually:

```text
UI
 │
 │ request/display
 ▼
authoritative pricing interface
```

not:

```text
UI JavaScript
 │
 ▼
canonical billing amount
```

---

# 84. Quotation boundary

A quotation may reference a PricingPlan or negotiated CommercialAgreement.

Acceptance of a quotation SHALL result in governed BillingTerms.

The quotation document itself SHALL not become an unversioned source of billing arithmetic.

---

# 85. B2B applicability

The model SHALL support negotiated B2B arrangements such as those required by enterprises using Baobab.

For example:

```text
Customer Group
     │
     ▼
Commercial Agreement
     │
     ├── negotiated recurring fee
     ├── usage allowance
     └── overage rate
```

without encoding a specific customer or corporate group into platform architecture.

---

# 86. External customers and subsidiaries

Pricing resolution SHALL not assume that only Nabhold-owned entities use Baobab.

An external customer group may have:

```text
Parent Organisation
      │
      ├── Subsidiary A
      └── Subsidiary B
```

Pricing may be negotiated at an authorised payer/account level while subscription and tenant boundaries remain distinct.

---

# 87. Group agreement does not merge tenants

A group-level CommercialAgreement MAY influence pricing for several authorised subscriptions.

It SHALL NOT merge:

- tenant identities;
- ProductSubscriptions;
- usage;
- entitlements;
- billing projections.

Shared commercial terms are not shared tenancy.

---

# 88. Security

Pricing mutations SHALL conform to ADR-SUB-0016.

Material operations include:

- plan publication;
- price change;
- commercial override;
- retroactive correction;
- plan migration;
- provider mapping change.

These operations SHALL be authorised, auditable and controlled.

---

# 89. Audit

Baobab SHALL preserve evidence for material pricing decisions including:

```text
who/what changed pricing?
what changed?
previous version?
new version?
when effective?
why?
which subscriptions were affected?
which approval authorised it?
```

where applicable.

---

# 90. Observability

Subscriptions SHOULD expose telemetry for:

- pricing-resolution success;
- pricing-resolution failure;
- missing commercial pricing;
- ambiguous pricing;
- unsupported markets;
- unsupported currencies;
- commercial agreement resolution failure;
- provider mapping failure;
- provider pricing drift;
- subscriptions by pricing-plan version;
- pending pricing migrations.

Financial values SHOULD not become uncontrolled metric labels.

---

# 91. Reconciliation

Pricing reconciliation SHALL compare:

```text
Canonical Catalogue
       │
       ▼
Resolved BillingTerms
       │
       ▼
Provider Mapping
       │
       ▼
Provider Configuration
```

A discrepancy SHALL be explicit.

---

# 92. Historical reproducibility

For any historical charge, Baobab SHALL be able to reconstruct:

```text
Product
   │
ProductVersion
   │
Offering
   │
PricingPlan + Version
   │
Market
   │
Currency
   │
Commercial Agreement
   │
Classification
   │
BillingTerms
   │
Charge
```

This is a core financial invariant.

---

# 93. Domain invariants

### INV-PRC-01

Product identity and pricing-plan identity are distinct.

### INV-PRC-02

Classification and pricing are distinct.

### INV-PRC-03

INTERNAL is not implemented as a fake zero-price provider plan.

### INV-PRC-04

Missing COMMERCIAL pricing never silently becomes zero.

### INV-PRC-05

A legitimate zero-price COMMERCIAL plan remains COMMERCIAL.

### INV-PRC-06

Every monetary price has explicit currency.

### INV-PRC-07

Market and currency remain separate dimensions.

### INV-PRC-08

Pricing versions are effective-dated.

### INV-PRC-09

Published pricing used for billing is historically reproducible.

### INV-PRC-10

New pricing does not silently rewrite existing BillingTerms.

### INV-PRC-11

Grandfathered pricing is supported.

### INV-PRC-12

Commercial overrides require explicit authority and provenance.

### INV-PRC-13

A tenant cannot manufacture its own price.

### INV-PRC-14

Usage producers cannot determine price.

### INV-PRC-15

Price does not create entitlement.

### INV-PRC-16

Entitlement does not determine price.

### INV-PRC-17

Kill Bill plans are provider projections, not canonical Baobab plans.

### INV-PRC-18

Provider pricing drift cannot silently override Baobab pricing.

### INV-PRC-19

Historical price correction preserves the original financial record.

### INV-PRC-20

Price resolution is deterministic for identical authoritative inputs.

### INV-PRC-21

Ambiguous pricing fails closed.

### INV-PRC-22

A shared commercial agreement does not merge tenant boundaries.

### INV-PRC-23

Pricing calculations use exact deterministic monetary arithmetic.

### INV-PRC-24

Every resolved BillingTerms instance has explainable pricing provenance.

---

# 94. Alternatives considered

## 94.1 Use Kill Bill catalogue as Baobab's canonical catalogue

**Rejected.**

It would bind Baobab commercial semantics to one provider.

---

## 94.2 Store a single price on Product

**Rejected.**

It cannot safely represent markets, currencies, versions, usage, contracts or effective dates.

---

## 94.3 Treat INTERNAL as a free plan

**Rejected.**

It conflates commercial valuation with subscription classification.

---

## 94.4 Let each digital estate calculate its own price

**Rejected.**

It would fragment pricing authority and permit inconsistent billing.

---

## 94.5 Update price rows in place

**Rejected for published financially relevant pricing.**

It destroys historical reproducibility.

---

## 94.6 Automatically migrate existing subscribers whenever price changes

**Rejected.**

Pricing migration is a commercial decision requiring explicit effective semantics.

---

## 94.7 Automatically convert prices using current FX

**Rejected as a default model.**

It creates unstable pricing and weak historical provenance.

---

## 94.8 Put negotiated prices directly into provider configuration

**Rejected as canonical architecture.**

Baobab must retain commercial agreement provenance independent of the provider.

---

## 94.9 Let a missing price mean zero

**Rejected.**

Missing configuration and zero price have fundamentally different meanings.

---

# 95. Consequences

## Positive

- Baobab pricing remains provider-independent.
- Multi-market and multi-currency pricing is supported.
- Historical billing remains reproducible.
- INTERNAL subscriptions retain commercial-equivalent valuation.
- Grandfathered pricing becomes possible.
- Negotiated B2B pricing can coexist with standard plans.
- Kill Bill can be replaced without redefining product pricing.
- Usage pricing integrates cleanly with ADR-SUB-0004.
- Billing cycles receive stable BillingTerms.
- External customer groups can receive negotiated pricing without compromising tenancy.

## Negative

- Catalogue management becomes a first-class platform capability.
- Pricing publication requires governance.
- Historical versions must be retained.
- Commercial agreements add resolution complexity.
- Provider catalogue mappings require reconciliation.
- Plan migration requires explicit workflows.

These costs are accepted because mutable or provider-owned pricing would create substantial financial and architectural risk.

---

# 96. Implementation requirements

An implementation conforming to this ADR SHALL provide:

1. canonical SubscriptionOffering identity;
2. versioned PricingPlans;
3. explicit price components;
4. recurring pricing;
5. usage pricing;
6. hybrid pricing;
7. explicit currencies;
8. market-aware pricing;
9. effective-dated pricing;
10. immutable historical pricing semantics;
11. BillingTerms resolution;
12. pricing provenance;
13. grandfathering;
14. commercial agreement/override support;
15. missing-price fail-closed behaviour;
16. legitimate zero-price COMMERCIAL support;
17. INTERNAL commercial-equivalent pricing without payable obligation;
18. deterministic price resolution;
19. controlled price publication;
20. Kill Bill provider mappings;
21. provider pricing reconciliation;
22. audit and controlled mutation.

---

# 97. Required tests

At minimum:

### Standard pricing

- active plan resolves;
- inactive plan rejected;
- future plan not selected prematurely;
- expired plan not selected for new subscription.

### Market

- ZA price resolves;
- UG price resolves;
- unsupported market blocks;
- market does not implicitly overwrite currency.

### Currency

- correct currency resolves;
- unsupported currency blocks;
- no implicit FX conversion.

### Versioning

- v1 historical terms retained;
- v2 selected after effective boundary;
- existing grandfathered subscription remains v1;
- explicit migration produces new BillingTerms.

### COMMERCIAL

- valid price;
- legitimate zero price;
- missing price blocks;
- ambiguous price blocks.

### INTERNAL

- commercial-equivalent price may resolve;
- payable amount remains zero;
- no payment obligation;
- classification does not become FREE plan.

### Commercial agreement

- valid override;
- expired agreement ignored/rejected appropriately;
- unauthorised override rejected;
- group agreement does not merge tenants.

### Usage

- allowance;
- overage;
- tiered rate;
- metric mismatch rejected;
- historical pricing version retained.

### Provider

- correct Kill Bill mapping;
- missing mapping;
- wrong currency;
- wrong amount;
- stale provider plan;
- duplicate provider mapping.

---

# 98. Recommended implementation sequence

```text
1. SubscriptionOffering model
          │
          ▼
2. PricingPlan + version
          │
          ▼
3. Recurring price components
          │
          ▼
4. Market / currency prices
          │
          ▼
5. Usage price components
          │
          ▼
6. BillingTerms resolver
          │
          ▼
7. Classification integration
          │
          ▼
8. Commercial agreement overrides
          │
          ▼
9. Pricing migration
          │
          ▼
10. Kill Bill mapping
          │
          ▼
11. Reconciliation
          │
          ▼
12. Publication governance
```

---

# 99. Architectural sequence

ADR-SUB-0005 completes an important progression:

```text
ADR-SUB-0002
What is the billing domain?
        │
        ▼
ADR-SUB-0003
How does an authoritative subscription
become a billing projection?
        │
        ▼
ADR-SUB-0004
How is consumption measured and rated?
        │
        ▼
ADR-SUB-0005
What commercial price and terms apply?
        │
        ▼
ADR-SUB-0006
What monetary treatment does the
authoritative classification permit?
        │
        ▼
ADR-SUB-0012
How do those consequences become
billing cycles, invoices and obligations?
```

---

# 100. Pricing resolution flow

```text
START
  │
  ▼
Authoritative ProductSubscription
  │
  ▼
Resolve ProductVersion
  │
  ▼
Resolve eligible Offering
  │
  ▼
Resolve effective PricingPlan
  │
  ▼
Resolve Market
  │
  ▼
Resolve Currency
  │
  ▼
Commercial Agreement?
  │
  ├──────────── YES ────────────┐
  │                              ▼
  │                    Validate override
  │                              │
  │                              ▼
  │                      Apply authorised
  │                      commercial terms
  │                              │
  │◄─────────────────────────────┘
  │
  ▼
Resolve classification
  │
  ▼
Construct versioned BillingTerms
  │
  ├── INTERNAL
  │      │
  │      ▼
  │ commercial/shadow valuation
  │ payable = ZERO
  │
  └── COMMERCIAL
         │
         ▼
   commercial billing terms
         │
         ▼
      READY
```

Failure at any required COMMERCIAL pricing step results in a precise blocker rather than implicit free service.

---

# 101. Relationship to ADR-SUB-0004

ADR-SUB-0004 establishes:

```text
what was consumed?
```

ADR-SUB-0005 establishes:

```text
under what price should
that consumption be valued?
```

Together:

```text
UsageAggregate
      │
      ▼
Metric
      │
      ▼
PricingPlan Version
      │
      ▼
Rating
      │
      ▼
RatedUsage
```

Usage and pricing remain independently governed.

---

# 102. Relationship to ADR-SUB-0006

ADR-SUB-0005 establishes commercial valuation.

ADR-SUB-0006 establishes monetary treatment.

Therefore:

```text
Pricing
   │
   ▼
commercial value
   │
   ▼
Classification Policy
   │
   ├── INTERNAL
   │      ▼
   │    zero payable
   │
   └── COMMERCIAL
          ▼
       applicable
       monetary billing
```

This separation is fundamental.

---

# 103. Relationship to ADR-SUB-0012

ADR-SUB-0005 produces resolved BillingTerms.

ADR-SUB-0012 consumes those terms:

```text
PricingPlan
     │
     ▼
ResolvedBillingTerms
     │
     ▼
BillingCycle
     │
     ▼
Charge
     │
     ▼
InvoiceProjection
     │
     ▼
FinancialObligation
```

Billing-cycle execution SHALL not independently rediscover current catalogue pricing after BillingTerms have already been resolved for the applicable period.

---

# 104. Relationship to ADR-SUB-0015

ADR-SUB-0015 SHALL translate canonical pricing into provider-specific configuration where necessary.

```text
Baobab PricingPlan
       │
       ▼
ResolvedBillingTerms
       │
       ▼
BillingProvider
       │
       ▼
KillBillAdapter
       │
       ▼
Kill Bill Plan
```

Provider limitations SHALL not redefine canonical pricing.

---

# 105. Relationship to ADR-SUB-0016

Pricing administration is financially privileged.

Operations such as:

```text
publish price
change price
create override
migrate subscriber
correct historical price
change provider mapping
```

SHALL be:

```text
authenticated
      +
authorised
      +
controlled
      +
idempotent where applicable
      +
audited
```

A valid tenant identity does not imply permission to choose its own price.

---

# 106. Final decision

Baobab SHALL own a **provider-independent, versioned and effective-dated subscription pricing model**.

The canonical commercial chain is:

```text
PRODUCT
   │
   ▼
PRODUCT VERSION
   │
   ▼
SUBSCRIPTION OFFERING
   │
   ▼
PRICING PLAN
   │
   ▼
PRICE COMPONENTS
   │
   ▼
MARKET / CURRENCY
   │
   ▼
COMMERCIAL AGREEMENT
   │
   ▼
RESOLVED BILLING TERMS
   │
   ▼
CLASSIFICATION POLICY
   │
   ▼
BILLING EXECUTION
```

The principal pricing rule is:

> **Catalogue pricing describes commercial value; BillingTerms describe the commercial terms applicable to a specific subscription; classification determines the permitted monetary treatment. These are related but separate concepts.**

The principal historical rule is:

> **A price change creates a new commercial version. It does not silently rewrite the terms under which earlier consumption or subscription periods occurred.**

The principal INTERNAL rule is:

> **INTERNAL is a subscription classification, not a fake free pricing plan. Baobab may retain the commercial-equivalent value of internal consumption while enforcing zero payable monetary obligation.**

The principal COMMERCIAL rule is:

> **Missing commercial pricing is a configuration failure, not a zero price. A COMMERCIAL subscription that requires pricing must fail closed until valid terms can be resolved.**

The principal provider rule is:

> **Kill Bill may represent Baobab plans for provider execution, but Kill Bill's catalogue does not define Baobab's canonical commercial model.**

And the principal portability rule is:

> **Baobab must be able to replace, upgrade or supplement its billing provider without changing the meaning of its products, plans, prices, negotiated agreements or historical BillingTerms.**