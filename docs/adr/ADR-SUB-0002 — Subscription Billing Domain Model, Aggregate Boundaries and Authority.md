# ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Domain Model / Authority Boundary  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** NABHOLD / Baobab Platform Architecture  
**Supersedes:** None  

**Related:**

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-018 — Canonical Organisation and Tenant Relationships
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-PAY-0001 — Adopt HyperSwitch as the Headless Baobab Payment Orchestration Engine
- ADR-SHARED-007 — Capability Contracts
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- `shared/contracts/subscriptions/v1`
- `shared/contracts/product/v1`
- `shared/contracts/payments/v1`

---

## 1. Context

ADR-SUB-0001 establishes `baobab-subscriptions` as Baobab's headless subscription billing engine and adopts Kill Bill as its foundational billing implementation behind a Baobab-owned façade.

That decision deliberately establishes several authority boundaries:

- `baobab-cp` owns `ProductSubscription`;
- `baobab-cp` owns subscription classification and its provenance;
- `baobab-cp` owns INTERNAL eligibility;
- `baobab-cp` owns tenants, legal entities, PlatformAccount identity and CapabilityGrants;
- `baobab-subscriptions` owns billing projections, billing cycles, recurring billing, usage rating and credits;
- `baobab-payments` owns payment execution and orchestration;
- `baobab-erp` owns accounting, ledger, receivables and revenue recognition.

What remains undefined is the internal domain model by which `baobab-subscriptions` represents its responsibilities.

This distinction is critical because several apparently similar concepts exist across the platform.

For example:

```text
Control Plane                     Subscriptions                  Kill Bill

ProductSubscription               BillingSubscriptionProjection Kill Bill Subscription
PlatformAccount                   BillingAccountProjection      Kill Bill Account
ProductVersion                    BillingPlanReference          Kill Bill Plan
Subscription classification       BillingPolicy                 Catalog configuration
CapabilityGrant                   —                             —
```

These concepts are related, but they are not interchangeable.

Allowing Kill Bill's domain model to become Baobab's canonical billing model would couple the platform to an implementation technology.

Allowing `baobab-subscriptions` to reproduce Control Plane concepts would create competing authorities.

Allowing billing state to become entitlement state would make the billing engine an implicit authorization service.

Allowing invoices or payment-provider objects to become accounting records would blur the boundary between billing, payment execution and financial accounting.

A formal subscription billing domain model is therefore required before deeper implementation proceeds.

---

## 2. Decision

`baobab-subscriptions` SHALL maintain a **Baobab-owned subscription billing domain model** independent of:

1. the Control Plane domain model;
2. Kill Bill's internal domain model;
3. payment-provider models;
4. ERP accounting models.

The principal aggregate SHALL be:

> **BillingSubscriptionProjection**

A `BillingSubscriptionProjection` is the billing engine's operational representation of an authoritative Control Plane `ProductSubscription`.

It does not replace, duplicate or become authoritative for the `ProductSubscription`.

The relationship is:

```text
Authoritative                                      Operational
Platform Domain                                    Billing Domain

baobab-cp                                          baobab-subscriptions

ProductSubscription
        │
        │ authorised billing projection
        ▼
BillingSubscriptionProjection
        │
        ├── BillingAccountProjection
        ├── BillingTerms
        ├── BillingCycle
        ├── UsageRecord
        ├── RatedUsage
        ├── Charge
        ├── Credit / Adjustment
        └── InvoiceProjection
```

The billing domain MAY subsequently be projected into Kill Bill.

Kill Bill SHALL NOT define the Baobab domain model.

---

# 3. Domain ownership

The authoritative ownership model SHALL be:

| Domain concept | Authority |
|---|---|
| Organisation | `baobab-cp` |
| Tenant | `baobab-cp` |
| Legal Entity | `baobab-cp` |
| PlatformAccount | `baobab-cp` |
| Product | `baobab-cp` |
| ProductVersion | `baobab-cp` |
| ProductSubscription | `baobab-cp` |
| Subscription classification | `baobab-cp` |
| Classification provenance | `baobab-cp` |
| INTERNAL eligibility | `baobab-cp` |
| CapabilityComposition | `baobab-cp` |
| CapabilityGrant | `baobab-cp` |
| Entitlement | `baobab-cp` |
| BillingAccountProjection | `baobab-subscriptions` |
| BillingSubscriptionProjection | `baobab-subscriptions` |
| BillingTerms | `baobab-subscriptions` |
| BillingCycle | `baobab-subscriptions` |
| UsageRecord | `baobab-subscriptions` |
| RatedUsage | `baobab-subscriptions` |
| Charge | `baobab-subscriptions` |
| Credit / BillingAdjustment | `baobab-subscriptions` |
| InvoiceProjection | `baobab-subscriptions` |
| Billing-provider mapping | `baobab-subscriptions` |
| Payment execution | `baobab-payments` |
| Payment routing | `baobab-payments` |
| Capture / refund orchestration | `baobab-payments` |
| Accounting journal | `baobab-erp` |
| Accounts receivable | `baobab-erp` |
| General ledger | `baobab-erp` |
| Revenue recognition | `baobab-erp` |

Ownership means authority over the lifecycle and invariant of the concept.

It does not prohibit another engine from holding an immutable identifier or local projection required to perform its work.

---

# 4. Principal aggregate

## 4.1 BillingSubscriptionProjection

`BillingSubscriptionProjection` SHALL be the principal aggregate representing the billing consequences of a Control Plane `ProductSubscription`.

Conceptually:

```text
BillingSubscriptionProjection
─────────────────────────────────────────

billing_subscription_id
tenant_id
platform_account_id
product_subscription_id
product_id
product_version_id

classification
classification_version/reference

billing_policy
billing_status

currency
billing_terms
billing_cycle

provider
provider_reference

effective_from
effective_until

simulated
created_at
updated_at
```

The exact wire representation belongs in `shared/contracts/subscriptions/v1`, not this ADR.

The ADR defines the semantics.

---

## 4.2 Identity

`billing_subscription_id` SHALL identify the billing projection.

It SHALL NOT replace `product_subscription_id`.

The two identifiers represent different aggregates.

```text
product_subscription_id
        │
        │ 1
        ▼
BillingSubscriptionProjection
        │
        └── billing_subscription_id
```

Consumers MUST NOT assume:

```text
billing_subscription_id == product_subscription_id
```

even where an implementation initially makes them mechanically related.

---

# 5. ProductSubscription remains authoritative

The billing projection SHALL always reference the authoritative Control Plane subscription.

`baobab-subscriptions` SHALL NOT independently create a commercial entitlement merely because a billing projection exists.

Therefore:

```text
BillingSubscriptionProjection
        │
        X
        │ cannot create
        ▼
ProductSubscription
```

The valid direction is:

```text
ProductSubscription
        │
        │ authorises
        ▼
BillingSubscriptionProjection
```

The billing engine MAY reject or delay projection when billing prerequisites are unavailable.

It SHALL NOT manufacture the upstream subscription.

---

# 6. BillingAccountProjection

A `BillingAccountProjection` SHALL represent the billing-relevant view of a PlatformAccount or other authorised billing party.

It is not an identity account.

It SHALL reference authoritative platform identifiers.

Conceptually:

```text
PlatformAccount
      │
      │ reference
      ▼
BillingAccountProjection
      │
      ├── billing preferences
      ├── billing currency
      ├── invoice metadata
      ├── provider mapping
      └── billing contacts/references where authorised
```

The billing engine SHALL NOT redefine:

- organisation identity;
- tenant ownership;
- corporate relationships;
- legal-entity relationships;
- PlatformAccount identity.

Those remain Control Plane concerns.

---

# 7. Subscriber, consumer and payer are not assumed identical

Baobab SHALL NOT embed the assumption that:

```text
subscriber == tenant == payer == legal entity == PlatformAccount
```

These identities may coincide in simple deployments but are semantically distinct.

For example:

```text
Customer Group
      │
      ├── Legal Entity A
      │       └── Tenant A
      │
      └── Legal Entity B
              └── Tenant B

Central Billing Account
        │
        ├── pays subscription for Tenant A
        └── pays subscription for Tenant B
```

The billing engine SHALL preserve the authoritative references necessary to distinguish:

- consuming tenant;
- subscribing PlatformAccount;
- billing account;
- payer reference;
- relevant legal entity.

It SHALL NOT infer corporate relationships from billing configuration.

Corporate relationships remain authoritative in the Control Plane.

---

# 8. BillingTerms

`BillingTerms` SHALL represent the billing conditions applicable to the projection.

They MAY include:

```text
BillingTerms
   │
   ├── currency
   ├── charging model
   ├── billing interval
   ├── billing anchor
   ├── pricing reference
   ├── usage-rating reference
   ├── trial conditions
   ├── credit conditions
   └── effective period
```

BillingTerms SHALL be version-aware.

A historical invoice or rated usage result MUST remain explainable after pricing or product configuration changes.

Current configuration SHALL NOT silently reinterpret historical billing facts.

---

# 9. BillingCycle

`BillingCycle` SHALL represent the temporal billing period applicable to a billing projection.

Examples include:

- monthly;
- quarterly;
- annual;
- usage-window based;
- contract-defined periods.

The billing cycle is a billing concept.

It SHALL NOT determine the lifecycle of the authoritative ProductSubscription.

For example:

```text
ProductSubscription ACTIVE
            │
            ├──── BillingCycle January
            ├──── BillingCycle February
            ├──── BillingCycle March
            └──── ...
```

A single ProductSubscription may therefore span many billing cycles.

---

# 10. UsageRecord

A `UsageRecord` SHALL represent an accepted usage fact attributable to a billing subscription.

A UsageRecord SHALL be:

- tenant-scoped;
- subscription-scoped;
- metric-specific;
- timestamped;
- attributable to a trusted producer;
- idempotently ingestible;
- auditable.

Conceptually:

```text
UsageRecord
─────────────────────
usage_record_id
event_id
tenant_id
product_subscription_id
billing_subscription_id
metric
quantity
unit
occurred_at
received_at
source
```

The exact contract is governed by Shared.

Usage ingestion SHALL NOT determine subscription classification.

---

# 11. INTERNAL subscriptions remain metered

An INTERNAL subscription has zero monetary charge under the policy established by ADR-SUB-0001.

It does not imply zero usage.

Therefore:

```text
INTERNAL subscription
        │
        ├── entitled             YES
        ├── metered              YES
        ├── audited              YES
        ├── readiness-controlled YES
        ├── rated                MAY BE
        ├── monetary charge      ZERO
        └── payment execution    NEVER
```

This distinction allows Baobab to understand the true resource consumption of internal tenants without inventing artificial monetary transactions.

---

# 12. RatedUsage

`RatedUsage` SHALL represent the deterministic application of an applicable rating policy to accepted usage.

It SHALL reference:

- source usage;
- applicable pricing/rating version;
- billing period;
- quantity;
- resulting rated amount where monetary rating applies.

Raw usage and rated usage SHALL remain distinguishable.

```text
UsageRecord
     │
     ▼
Aggregation
     │
     ▼
RatedUsage
     │
     ▼
Charge
```

Re-rating MUST be explicit.

Historical rated results SHALL NOT silently mutate merely because the current catalog changes.

---

# 13. Charge

A `Charge` SHALL represent a billing obligation calculated by the subscription engine.

A Charge is not a payment.

A Charge is not an accounting journal entry.

```text
Charge
  │
  ├──────────────► InvoiceProjection
  │
  ├──────────────► payment obligation
  │                       │
  │                       ▼
  │                baobab-payments
  │
  └──────────────► financial fact
                          │
                          ▼
                     baobab-erp
```

Therefore:

```text
Charge != Payment
Charge != LedgerEntry
```

---

# 14. Credits and BillingAdjustments

The engine MAY create billing-domain credits and adjustments where permitted by policy.

These objects represent corrections to billing calculations.

They SHALL NOT directly modify accounting ledgers.

Any resulting accounting consequence SHALL be communicated to `baobab-erp` through the applicable canonical integration contract.

Any resulting monetary consequence SHALL use `baobab-payments`.

---

# 15. InvoiceProjection

`InvoiceProjection` SHALL represent the subscription engine's billing statement of charges, credits and applicable amounts for a billing period.

It SHALL NOT become the authoritative general ledger or accounts-receivable ledger.

The distinction is:

```text
Subscription Engine                  ERP

"What should be billed?"             "How is it accounted for?"

InvoiceProjection ────────────────► Financial Posting / Receivable
```

`baobab-erp` remains authoritative for accounting consequences.

---

# 16. Kill Bill model boundary

Kill Bill objects SHALL remain implementation details behind the `BillingProvider` boundary.

For example:

```text
BAOBAB DOMAIN                         KILL BILL

BillingAccountProjection  ────────►  Account
BillingSubscriptionProjection ────►  Subscription / Bundle
BillingTerms              ────────►  Catalog configuration
UsageRecord               ────────►  Usage input
InvoiceProjection         ◄────────  Invoice representation
```

The arrows represent adapter mappings.

They do not establish semantic identity.

Consequently:

```text
BillingAccountProjection != KillBill Account

BillingSubscriptionProjection != KillBill Subscription

ProductSubscription != KillBill Subscription

ProductVersion != KillBill Plan

InvoiceProjection != KillBill Invoice
```

---

# 17. Anti-corruption layer

All communication with Kill Bill SHALL occur through a provider adapter implementing a Baobab-owned port.

Conceptually:

```text
                    Baobab Domain
                         │
                         ▼
                  BillingProvider
                         │
              ┌──────────┴──────────┐
              │                     │
              ▼                     ▼
     TemporaryProvider        KillBillProvider
                                    │
                                    ▼
                                Kill Bill
```

Domain services SHALL depend on the `BillingProvider` abstraction rather than Kill Bill APIs or classes.

Kill Bill identifiers SHALL be stored as external/provider references rather than canonical platform identifiers.

---

# 18. Provider references

Provider mappings SHALL explicitly preserve both sides of the relationship.

Conceptually:

```text
ProviderReference
────────────────────────────
provider
resource_type
canonical_reference
provider_reference
created_at
last_verified_at
```

Examples:

```text
BillingSubscriptionProjection
    billing_subscription_id = SUB-123

Kill Bill
    subscription_id = KB-789
```

The mapping is:

```text
SUB-123 ──ProviderReference──► KB-789
```

`KB-789` never becomes the canonical Baobab identifier.

---

# 19. Aggregate boundaries

The billing domain SHALL enforce aggregate boundaries rather than constructing one enormous subscription object.

Recommended logical boundaries are:

```text
BillingAccountProjection
        │
        └── references platform account

BillingSubscriptionProjection
        │
        ├── references ProductSubscription
        ├── BillingTerms
        └── BillingCycle

Usage
        │
        ├── UsageRecord
        └── RatedUsage

Billing
        │
        ├── Charge
        ├── Credit
        ├── Adjustment
        └── InvoiceProjection

Provider Integration
        │
        └── ProviderReference
```

These boundaries MAY share transactions where implementation consistency requires it, but they SHALL retain distinct semantics.

---

# 20. No cross-engine database ownership

`baobab-subscriptions` SHALL own its persistence.

It SHALL NOT:

- query Control Plane tables directly;
- query payment-engine tables directly;
- query ERP tables directly;
- expose its database as another engine's integration interface.

Likewise, other Baobab engines SHALL NOT use the subscriptions database as an integration API.

Cross-engine interaction occurs through:

1. canonical APIs;
2. canonical events;
3. explicitly governed reconciliation mechanisms.

---

# 21. Canonical contracts

Cross-repository representations SHALL be defined in `baobab-platform/shared`.

The dependency direction is:

```text
                   shared
          canonical contracts
                  / | \
                 /  |  \
                ▼   ▼   ▼
               CP  SUB  PAY
                    │
                    ▼
                   ERP
```

`baobab-subscriptions` MAY maintain richer internal models than Shared exposes.

It SHALL NOT publish provider-specific structures as canonical contracts.

---

# 22. Authority versus projection

Baobab SHALL consistently distinguish authority from projection.

A projection is a local representation required for an engine to perform its responsibility.

It does not become authoritative merely because it is persisted locally.

For example:

```text
AUTHORITATIVE                  LOCAL PROJECTION

CP PlatformAccount      ───►   BillingAccountProjection

CP ProductSubscription ───►   BillingSubscriptionProjection
```

Changes to authoritative identity MUST originate from the owning authority.

The subscription engine MAY refresh or reconcile its projection.

It SHALL NOT mutate the upstream authoritative object directly through database access.

---

# 23. Entitlement boundary

Billing state SHALL NOT directly constitute entitlement.

The following is prohibited:

```text
payment_failed
      │
      ▼
billing engine
      │
      ▼
DELETE CapabilityGrant
```

Instead:

```text
Billing state changes
        │
        ▼
canonical lifecycle signal
        │
        ▼
baobab-cp
        │
        ▼
governed entitlement decision
        │
        ▼
CapabilityGrant lifecycle
```

This preserves separation of duties.

`baobab-subscriptions` reports billing facts.

`baobab-cp` remains the entitlement authority.

---

# 24. Payment boundary

The subscription engine MAY determine that a monetary obligation exists.

It SHALL NOT execute the payment itself.

```text
Billing calculation
       │
       ▼
Charge
       │
       ▼
Payment obligation
       │
       ▼
baobab-payments
       │
       ▼
payment orchestration
```

A successful billing calculation does not imply successful payment.

A successful payment does not rewrite the billing calculation.

Both are related through canonical references.

---

# 25. ERP boundary

The subscription engine SHALL NOT function as the accounting ledger.

Its financial objects describe billing facts.

ERP records accounting consequences.

```text
Subscription domain             Accounting domain

Charge
Credit              ───────►    Receivable
Adjustment                     Journal
InvoiceProjection              Ledger
                               Revenue recognition
```

Reconciliation SHALL preserve traceability between these domains.

---

# 26. State must remain explainable

Every billing projection SHALL be explainable using durable references to:

- authoritative ProductSubscription;
- classification;
- applicable policy;
- applicable product/pricing version;
- billing terms;
- usage facts where applicable;
- provider references where applicable;
- lifecycle transitions.

The system SHOULD be capable of answering questions such as:

```text
Why was this tenant billed?

Which ProductSubscription authorised it?

Which classification applied?

Which price/rating version was used?

Which usage produced this charge?

Which billing cycle contained it?

Which provider object represented it?

Which payment corresponds to it?

Which ERP posting resulted from it?
```

The answer SHALL NOT depend exclusively on mutable current configuration.

---

# 27. Temporal integrity

Billing is inherently temporal.

Domain records SHALL therefore distinguish where appropriate:

```text
effective_at
occurred_at
received_at
processed_at
created_at
updated_at
```

The platform SHALL NOT assume these timestamps are equivalent.

A late usage event, for example, may have:

```text
occurred_at  = August 31
received_at  = September 2
processed_at = September 2
```

The applicable treatment is a billing-policy concern, but the domain model must preserve enough information to make that decision deterministically.

---

# 28. Currency

Currency SHALL be explicit wherever monetary values occur.

No monetary field SHALL depend on an implicit global currency.

Conceptually:

```text
Money {
    amount
    currency
}
```

A billing projection MAY operate in ZAR, UGX, USD or another supported currency according to applicable policy and market configuration.

Currency SHALL NOT be inferred from tenant identity alone.

---

# 29. Monetary precision

Monetary values SHALL use exact decimal semantics.

Floating-point arithmetic SHALL NOT be used for authoritative billing amounts.

Rounding policy SHALL be explicit and deterministic.

Provider adapters SHALL translate between Baobab monetary semantics and provider representations without changing the authoritative amount silently.

---

# 30. Tenant isolation

Every tenant-scoped billing aggregate SHALL carry or be unambiguously resolvable to trusted tenant context.

Tenant context SHALL originate from trusted Baobab workload identity and Control Plane context.

Caller-supplied browser headers SHALL NOT establish authoritative tenant identity.

A resource belonging to another tenant SHALL not become accessible merely because its identifier is known.

---

# 31. PlatformAccount isolation

`PlatformAccount` references SHALL be treated independently from tenant identifiers.

The architecture SHALL support:

```text
one PlatformAccount → one tenant

one PlatformAccount → multiple authorised tenants

multiple subscriptions → one authorised billing account
```

where Control Plane policy permits those relationships.

`baobab-subscriptions` SHALL consume such relationships.

It SHALL NOT create them.

---

# 32. Domain invariants

At minimum, the following invariants SHALL hold.

### INV-SUB-01

Every BillingSubscriptionProjection references exactly one authoritative ProductSubscription.

### INV-SUB-02

A BillingSubscriptionProjection cannot create or grant entitlement.

### INV-SUB-03

Subscription classification originates from the Control Plane.

### INV-SUB-04

INTERNAL classification cannot produce a monetary payment obligation.

### INV-SUB-05

INTERNAL subscriptions remain meterable and auditable.

### INV-SUB-06

Provider identifiers never replace Baobab canonical identifiers.

### INV-SUB-07

A Charge is not a Payment.

### INV-SUB-08

An InvoiceProjection is not an accounting ledger.

### INV-SUB-09

Every monetary amount has an explicit currency.

### INV-SUB-10

Authoritative monetary calculations use exact decimal semantics.

### INV-SUB-11

Cross-tenant access fails closed.

### INV-SUB-12

Historical billing facts remain explainable after configuration changes.

### INV-SUB-13

No external billing provider determines Baobab organisation, tenant, PlatformAccount or entitlement identity.

### INV-SUB-14

No subscription-domain aggregate directly mutates another engine's persistence.

---

# 33. Conceptual domain model

```text
                     BAOBAB CONTROL PLANE
┌──────────────────────────────────────────────────────┐
│ Organisation                                         │
│ Tenant                                               │
│ PlatformAccount                                      │
│ Product                                              │
│ ProductVersion                                       │
│ ProductSubscription                                  │
│ SubscriptionClassification                           │
│ CapabilityComposition                                │
│ CapabilityGrant                                      │
└───────────────────────┬──────────────────────────────┘
                        │
                        │ canonical contract/event
                        ▼
              BAOBAB SUBSCRIPTIONS
┌──────────────────────────────────────────────────────┐
│                                                      │
│ BillingAccountProjection                             │
│                                                      │
│ BillingSubscriptionProjection                        │
│      │                                               │
│      ├── BillingTerms                                │
│      ├── BillingCycle                                │
│      │                                               │
│      ├── UsageRecord                                 │
│      │      │                                        │
│      │      ▼                                        │
│      │   RatedUsage                                  │
│      │      │                                        │
│      │      ▼                                        │
│      ├── Charge                                      │
│      ├── Credit                                      │
│      ├── Adjustment                                  │
│      └── InvoiceProjection                           │
│                                                      │
│ ProviderReference                                    │
└──────────────┬───────────────────┬───────────────────┘
               │                   │
               │                   │
        monetary obligation    accounting facts
               │                   │
               ▼                   ▼
     BAOBAB PAYMENTS           BAOBAB ERP
               │                   │
               ▼                   ▼
        payment execution      ledger/accounting


              Provider boundary
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

---

# 34. Lifecycle flow

```text
Control Plane
     │
     │ ProductSubscription authorised
     ▼
Subscription contract/event
     │
     ▼
Validate trusted context
     │
     ▼
Validate classification
     │
     ▼
Resolve billing policy
     │
     ▼
Create/update BillingSubscriptionProjection
     │
     ▼
Resolve BillingTerms
     │
     ├──────── INTERNAL ───────► zero monetary charge
     │                              │
     │                              └── metering remains active
     │
     └──────── COMMERCIAL ─────► billing configuration
                                    │
                                    ▼
                              BillingProvider
                                    │
                                    ▼
                                 Kill Bill
```

---

# 35. Reconciliation model

Because the authoritative subscription and billing projection live in separate engines, drift is possible.

The architecture SHALL therefore support reconciliation.

Conceptually:

```text
ProductSubscription
       │
       │ expected state
       ▼
Reconciliation
       │
       ├── compare projection
       ├── compare provider mapping
       ├── detect missing projection
       ├── detect stale projection
       └── detect provider divergence
              │
              ▼
       deterministic repair
       or operator escalation
```

Reconciliation SHALL NOT silently invent upstream authority.

---

# 36. Failure philosophy

Billing-domain operations SHALL fail closed where authority cannot be established.

Examples:

- unknown tenant → reject;
- unknown ProductSubscription → reject;
- unverifiable classification → reject;
- cross-tenant identifier → not found/reject;
- unsupported currency → reject;
- ambiguous pricing version → reject;
- duplicate idempotent operation → return prior deterministic outcome;
- provider unavailable → preserve local state and report provider failure rather than invent success.

Financial correctness takes precedence over optimistic availability.

---

# 37. Migration and provider replacement

The domain model SHALL permit provider replacement.

For example:

```text
                    BillingProvider
                         │
           ┌─────────────┼──────────────┐
           ▼             ▼              ▼
     Temporary       Kill Bill       Future
     Provider        Adapter          Adapter
```

A future provider migration SHALL NOT require consumers to replace:

- ProductSubscription IDs;
- billing subscription IDs;
- canonical API contracts;
- event contracts.

Only provider mappings should need migration where practicable.

---

# 38. Consequences

## Positive

- Baobab retains control of its subscription billing semantics.
- Kill Bill remains replaceable.
- Control Plane authority is preserved.
- Billing cannot silently become entitlement authority.
- Payment orchestration remains independent.
- ERP remains accounting authority.
- INTERNAL subscriptions remain observable without artificial payments.
- Multi-tenant and multi-organisation billing models remain possible.
- Historical billing decisions can be explained.
- Provider migration becomes materially safer.

## Negative

- The Baobab façade requires a deliberate domain model rather than simply exposing Kill Bill.
- Mapping and reconciliation code must be maintained.
- Some information exists as projections in multiple engines.
- Provider behaviour must be translated into Baobab semantics.
- Distributed consistency must be explicitly managed.

These costs are accepted because the alternative creates tighter coupling and weaker authority boundaries.

---

# 39. Rejected alternatives

## 39.1 Use Kill Bill's model as Baobab's billing model

**Rejected.**

It would make an implementation technology part of the platform contract and make replacement materially more difficult.

---

## 39.2 Let Control Plane own billing aggregates

**Rejected.**

Control Plane owns product subscription and entitlement authority, not recurring billing execution.

Combining the domains would violate separation of duties established by ADR-SUB-0001.

---

## 39.3 Let subscriptions own ProductSubscription

**Rejected.**

It would create competing subscription authorities.

---

## 39.4 Treat billing status as entitlement

**Rejected.**

Billing state is an input to governed lifecycle decisions, not authorization authority.

---

## 39.5 Let ERP perform subscription billing

**Rejected.**

ERP owns accounting consequences. Subscription rating and lifecycle billing remain a specialist billing-engine responsibility.

---

## 39.6 Let the payment engine own invoices and charges

**Rejected.**

Payments execute monetary obligations. They do not determine why the obligation exists.

---

# 40. Implementation requirements

Implementation following this ADR SHALL:

1. introduce explicit Baobab-owned billing-domain types;
2. keep `ProductSubscription` as an external authoritative reference;
3. distinguish billing account from PlatformAccount;
4. distinguish BillingSubscriptionProjection from provider subscription;
5. persist provider mappings explicitly;
6. preserve classification provenance/reference;
7. enforce tenant isolation;
8. use exact monetary arithmetic;
9. make currency explicit;
10. retain temporal information needed for historical explanation;
11. prohibit direct cross-engine database access;
12. use canonical Shared contracts for cross-repository communication;
13. keep Kill Bill-specific types inside the provider adapter;
14. expose sufficient information for deterministic reconciliation;
15. preserve idempotent mutation semantics established by ADR-SUB-0001.

---

# 41. Testing requirements

Tests SHALL demonstrate at minimum:

- a valid ProductSubscription can produce a billing projection;
- a billing projection cannot manufacture a ProductSubscription;
- INTERNAL subscriptions produce no monetary payment obligation;
- INTERNAL subscriptions can still record usage;
- COMMERCIAL subscriptions preserve monetary billing semantics;
- another tenant cannot retrieve or mutate a billing projection;
- provider identifiers do not replace canonical identifiers;
- duplicate projection creation is idempotent;
- historical pricing references survive current pricing changes;
- Kill Bill unavailability does not manufacture successful provider state;
- billing state does not directly mutate CapabilityGrants;
- charges remain distinguishable from payments;
- invoice projections remain distinguishable from ERP accounting records.

---

# 42. Follow-on decisions

This ADR establishes the vocabulary and aggregate boundaries required by subsequent decisions.

In particular:

```text
ADR-SUB-0002
Domain Model
     │
     ├────► ADR-SUB-0003
     │      Control Plane → Billing Projection Lifecycle
     │
     ├────► ADR-SUB-0006
     │      Classification-Driven Billing Policy
     │
     ├────► ADR-SUB-0015
     │      Kill Bill Adapter and Provider Portability
     │
     └────► ADR-SUB-0016
            Security, Workload Identity,
            Audit and Controlled Mutation
```

Those ADRs SHALL build upon the authority boundaries established here rather than redefining them.

---

# 43. Final decision

`baobab-subscriptions` SHALL maintain a **Baobab-owned billing domain**, centred on `BillingSubscriptionProjection`, which represents the billing consequences of an authoritative Control Plane `ProductSubscription`.

The domain SHALL remain separate from:

- Control Plane subscription and entitlement authority;
- Kill Bill's provider-specific model;
- payment execution;
- ERP accounting.

The architectural relationship is therefore:

```text
baobab-cp
ProductSubscription
      │
      │ authorises
      ▼
baobab-subscriptions
BillingSubscriptionProjection
      │
      ├────────► BillingProvider ────────► Kill Bill
      │
      ├────────► baobab-payments
      │
      └────────► baobab-erp
```

The central rule is:

> **Control Plane owns why a subscription exists and what it authorises. Subscriptions owns how that authorised subscription is billed. Payments owns how money is moved. ERP owns how the financial consequence is accounted for. Kill Bill is an implementation provider, never the Baobab domain.**