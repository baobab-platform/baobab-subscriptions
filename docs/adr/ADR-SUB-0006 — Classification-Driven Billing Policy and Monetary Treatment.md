# ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Billing Policy / Classification  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None  

**Related:**

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-018 — Canonical Organisation and Tenant Relationships
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-PAY-0001 — Adopt HyperSwitch as the Headless Baobab Payment Orchestration Engine
- ADR-SHARED-007 — Capability Contracts
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- `shared/contracts/product/v1`
- `shared/contracts/subscriptions/v1`
- `shared/contracts/payments/v1`

---

# 1. Context

The Baobab platform distinguishes the existence of a `ProductSubscription` from the commercial treatment of that subscription.

The Control Plane is authoritative for:

- the `ProductSubscription`;
- its classification;
- classification provenance;
- eligibility for classifications whose use is restricted;
- the tenant and PlatformAccount relationships associated with the subscription.

`baobab-subscriptions` consumes that authoritative classification and determines its billing consequences.

ADR-SUB-0001 already establishes an important initial distinction:

```text
INTERNAL
    monetary charge = zero
    metering = yes
    audit = yes
    entitlement = governed
    payment invocation = never

COMMERCIAL
    monetary billing = applicable
    metering = applicable
    payment invocation = when required
```

This distinction must now become a formal billing policy rather than an implementation convention.

Without such a policy, different components could incorrectly interpret classification.

Examples of dangerous interpretations include:

```text
INTERNAL == free tier
INTERNAL == development
INTERNAL == unmetered
INTERNAL == unrestricted
INTERNAL == bypass billing controls

COMMERCIAL == always charge immediately
COMMERCIAL == always use Kill Bill
COMMERCIAL == entitlement depends directly on payment
```

All are incorrect.

Classification is authoritative upstream context.

Billing policy determines its billing consequences.

---

# 2. Decision

`baobab-subscriptions` SHALL implement a **classification-driven billing policy**.

The policy SHALL translate an authoritative subscription classification into deterministic billing requirements without becoming authoritative for the classification itself.

The architectural relationship is:

```text
              CONTROL PLANE
                    │
                    │ authoritative
                    ▼
        Subscription Classification
                    │
                    ▼
              SHARED POLICY
                    │
                    ▼
          BAOBAB SUBSCRIPTIONS
                    │
             Billing Policy
          ┌─────────┼─────────┐
          ▼         ▼         ▼
       Metering   Billing   Payment
       policy     policy    requirement
```

The initial supported classifications SHALL include:

- `INTERNAL`
- `COMMERCIAL`

The architecture SHALL allow additional classifications to be introduced through canonical policy evolution without redesigning the billing engine.

---

# 3. Classification authority

`baobab-subscriptions` SHALL NOT:

- assign a subscription classification;
- upgrade or downgrade a classification;
- determine INTERNAL eligibility;
- infer classification from organisation names;
- infer classification from corporate relationships;
- infer classification from pricing;
- infer classification from payment history;
- infer classification from Kill Bill configuration.

Classification SHALL originate from the Control Plane.

Therefore:

```text
Control Plane
     │
     │ authoritative classification
     ▼
Subscriptions
     │
     │ applies billing consequence
     ▼
Billing behaviour
```

The reverse is prohibited:

```text
Billing behaviour
      │
      X
      ▼
Subscription classification
```

---

# 4. Classification is not organisation type

Classification SHALL NOT be inferred from whether an organisation is:

- Nabhold Group Africa;
- a Nabhold subsidiary;
- a Baobab internal organisation;
- an external customer;
- a customer subsidiary;
- a particular tenant type.

Corporate relationship and subscription classification are distinct dimensions.

For example:

```text
Organisation A
    └── ProductSubscription
            classification = INTERNAL

Organisation B
    └── ProductSubscription
            classification = COMMERCIAL
```

is valid where authorised by Control Plane policy.

Likewise, a single organisation may hold different ProductSubscriptions with different classifications where policy permits.

---

# 5. Classification is subscription-scoped

Classification SHALL apply to the authoritative `ProductSubscription`, not implicitly to the entire tenant, PlatformAccount or organisation.

Therefore:

```text
PlatformAccount
      │
      ├── ProductSubscription A
      │       classification = INTERNAL
      │
      └── ProductSubscription B
              classification = COMMERCIAL
```

is structurally valid if authorised upstream.

The billing engine SHALL evaluate each subscription according to its own authoritative classification.

---

# 6. Canonical billing policy

The billing consequences of classification SHALL be governed by a canonical policy contract owned outside provider-specific implementation.

Conceptually:

```yaml
classifications:
  INTERNAL:
    monetary_charge: zero
    metering: required
    billing_projection: required
    payment_execution: prohibited
    provider_required: false

  COMMERCIAL:
    monetary_charge: priced
    metering: policy_driven
    billing_projection: required
    payment_execution: policy_driven
    provider_required: policy_driven
```

This example is illustrative.

The normative representation belongs in `baobab-platform/shared`, including the referenced `billing-policy.yaml` or its canonical successor.

---

# 7. Policy evaluation

The billing engine SHALL determine billing treatment through explicit policy evaluation.

Conceptually:

```text
ProductSubscription
       │
       ├── classification
       ├── product
       ├── product version
       ├── tenant context
       └── relevant billing references
                 │
                 ▼
         BillingPolicyResolver
                 │
                 ▼
          BillingDecision
```

A `BillingDecision` SHOULD resolve sufficient information to determine:

- monetary treatment;
- whether metering is required;
- whether rating is required;
- whether billing-provider provisioning is required;
- whether payment execution can occur;
- whether a billing projection can become ACTIVE;
- configuration blockers;
- applicable policy version.

---

# 8. Policy must be deterministic

Given equivalent authoritative inputs and the same policy version, policy evaluation SHALL produce the same billing decision.

Conceptually:

```text
BillingDecision =
    f(
      classification,
      product_version,
      billing_policy_version,
      relevant_authoritative_context
    )
```

Provider availability SHALL NOT change classification.

Payment success SHALL NOT change classification.

Current billing-engine load SHALL NOT change classification.

---

# 9. INTERNAL classification

`INTERNAL` SHALL mean:

> An upstream-authorised ProductSubscription whose billing policy requires zero monetary charge while preserving appropriate metering, audit, lifecycle, entitlement governance and operational controls.

It SHALL NOT mean:

> A subscription that bypasses the billing system.

An INTERNAL subscription therefore remains a real ProductSubscription with a real billing projection where required.

---

# 10. INTERNAL monetary treatment

The monetary consequence of INTERNAL SHALL be:

```text
chargeable monetary amount = 0
```

This SHALL hold regardless of measured consumption unless an authorised upstream classification change occurs.

Usage does not silently convert INTERNAL into COMMERCIAL.

For example:

```text
INTERNAL subscription

usage:
    100 units
    1,000 units
    1,000,000 units

monetary obligation:
    ZERO
```

High usage MAY trigger:

- operational alerts;
- capacity controls;
- policy review;
- governance workflows;

but SHALL NOT autonomously create a commercial charge.

---

# 11. INTERNAL does not mean unmetered

INTERNAL subscriptions SHALL remain meterable.

This is required for:

- capacity planning;
- cost attribution;
- operational intelligence;
- abuse detection;
- service optimisation;
- readiness analysis;
- future commercial analysis;
- audit.

Therefore:

```text
INTERNAL
   │
   ├── usage observed
   ├── usage retained according to policy
   ├── usage aggregated where appropriate
   └── monetary charge = zero
```

Metering and charging are separate concepts.

---

# 12. INTERNAL may be rated without being charged

The architecture MAY support shadow or analytical rating of INTERNAL usage where useful.

For example:

```text
usage
  │
  ▼
shadow rating
  │
  ├── economic value = ZAR X
  └── actual charge = ZAR 0
```

If implemented, such rating SHALL be explicitly marked non-billable.

It SHALL NOT produce:

- payment obligations;
- accounts receivable;
- collectible invoices;
- payment attempts.

This can support internal cost analysis without falsifying financial transactions.

---

# 13. INTERNAL payment prohibition

`baobab-subscriptions` SHALL NEVER request monetary payment execution for an INTERNAL subscription.

The invariant is:

```text
classification == INTERNAL
        │
        ▼
payment_execution == PROHIBITED
```

This prohibition SHALL be enforced in domain logic, not merely through user-interface behaviour.

Even if:

- a payment method exists;
- a Kill Bill account exists;
- a provider mapping accidentally exists;
- pricing exists;
- usage is high;

the billing engine SHALL NOT generate a payment obligation for an INTERNAL subscription.

---

# 14. INTERNAL provider requirement

INTERNAL subscriptions SHALL NOT require external billing-provider resources solely to establish billing readiness.

Therefore the following can be valid:

```text
classification = INTERNAL
billing_projection = ACTIVE
metering = ACTIVE
provider_reference = NONE
payment_path = NONE
```

The platform SHALL NOT create artificial Kill Bill accounts, bundles or subscriptions merely to mirror INTERNAL ProductSubscriptions.

If provider resources exist for another justified reason, their existence SHALL NOT override INTERNAL zero-charge policy.

---

# 15. INTERNAL entitlement

INTERNAL does not mean unconditional entitlement.

Entitlement remains governed by the Control Plane.

Therefore:

```text
INTERNAL
   │
   ├── zero monetary charge
   │
   └── entitlement
           │
           ▼
      Control Plane governance
```

An INTERNAL subscription can still be:

- suspended;
- terminated;
- restricted;
- denied due to invalid tenant state;
- denied because eligibility no longer holds;

according to upstream policy.

The absence of payment does not imply permanent access.

---

# 16. INTERNAL eligibility

The billing engine SHALL NOT determine whether a subscription qualifies as INTERNAL.

That responsibility remains with the Control Plane, including the organisational and relationship rules established by ADR-BCP-017 and ADR-BCP-018.

Subscriptions SHALL consume the result.

If INTERNAL eligibility cannot be verified through authoritative context where verification is required, the engine SHALL fail closed rather than silently treating the subscription as INTERNAL.

---

# 17. COMMERCIAL classification

`COMMERCIAL` SHALL mean:

> A ProductSubscription whose billing treatment is governed by applicable monetary pricing and commercial billing policy.

COMMERCIAL does not necessarily mean:

- payment is due immediately;
- usage billing is required;
- Kill Bill must always be contacted;
- a particular payment rail is required;
- the subscription is currently collectible.

Those decisions depend on the applicable commercial billing configuration.

---

# 18. COMMERCIAL billing models

COMMERCIAL policy SHALL support different charging models without introducing new subscription classifications for every pricing structure.

Examples may include:

```text
COMMERCIAL
    │
    ├── fixed recurring
    ├── usage based
    ├── fixed + usage
    ├── tiered
    ├── volume
    ├── prepaid
    ├── postpaid
    ├── contract-specific
    └── future supported models
```

Classification answers:

> Is this subscription commercially billable?

Pricing answers:

> How is it billed?

These questions SHALL remain distinct.

---

# 19. COMMERCIAL pricing requirement

A COMMERCIAL subscription SHALL NOT become fully billing-operational when required pricing cannot be resolved.

Example:

```text
classification = COMMERCIAL
pricing = UNKNOWN
```

must result in a state such as:

```text
billing_status = PENDING_CONFIGURATION

blocker =
    PRICING_CONFIGURATION_MISSING
```

It SHALL NOT result in an invented zero price.

---

# 20. Zero-priced COMMERCIAL subscriptions

A COMMERCIAL subscription MAY legitimately have a monetary amount of zero for a defined period or pricing rule.

Examples include:

- trial;
- contractual credit;
- promotional period;
- introductory pricing;
- fully offset credit.

That does not make the subscription INTERNAL.

Therefore:

```text
COMMERCIAL + current charge 0
```

is not equivalent to:

```text
INTERNAL
```

Classification and calculated amount are separate.

---

# 21. Zero monetary amount must retain provenance

Where a COMMERCIAL billing calculation produces zero, the engine SHALL preserve why.

For example:

```text
amount = 0
reason = TRIAL

amount = 0
reason = FULL_CREDIT

amount = 0
reason = ZERO_USAGE

amount = 0
reason = CONTRACTUAL_PRICE
```

It SHALL not rewrite the classification as INTERNAL.

---

# 22. Pricing resolution

Commercial pricing SHALL be resolved through authoritative Baobab policy/configuration.

The billing engine SHALL NOT derive pricing from:

- UI display text;
- Kill Bill catalog objects alone;
- payment-provider configuration;
- ERP ledger entries.

The logical flow is:

```text
ProductSubscription
       │
       ├── classification
       ├── product/version
       └── billing context
                │
                ▼
        canonical pricing policy
                │
                ▼
          BillingTerms
```

Kill Bill MAY implement the resulting terms but does not become the canonical source of Baobab pricing semantics.

---

# 23. Policy versioning

Every material billing decision SHALL be traceable to the policy version under which it was made.

Conceptually:

```text
billing_policy_id
billing_policy_version
effective_from
```

Historical billing SHALL remain explainable after policy changes.

The platform SHALL NOT silently apply current policy retrospectively to historical financial facts unless an explicit correction or migration operation authorises it.

---

# 24. Effective dating

Billing policy SHALL support effective dating.

For example:

```text
Policy v1
effective until 2026-12-31

Policy v2
effective from 2027-01-01
```

The applicable policy is determined by the relevant effective-time semantics, not simply by whichever policy happens to be loaded today.

---

# 25. Unknown classification

An unknown classification SHALL fail closed.

For example:

```text
classification = PARTNER
```

when the running subscription engine does not understand `PARTNER` SHALL NOT be treated as:

- INTERNAL;
- COMMERCIAL;
- free;
- zero charge.

Instead:

```text
billing_status = PENDING_CONFIGURATION / BLOCKED

blocker =
    UNSUPPORTED_SUBSCRIPTION_CLASSIFICATION
```

This protects future classification evolution.

---

# 26. Missing classification

A missing classification SHALL likewise fail closed.

The engine SHALL NOT default an unclassified ProductSubscription to INTERNAL.

That would create an unauthorised free-service path.

It SHALL NOT blindly default to COMMERCIAL either, because that could generate an unauthorised financial obligation.

The correct outcome is explicit configuration failure.

---

# 27. Future classifications

The policy architecture SHALL permit future classifications without changing the fundamental domain model.

Potential future classifications are not defined by this ADR.

The model is:

```text
classification
      │
      ▼
canonical policy
      │
      ▼
billing behaviour
```

rather than:

```text
if internal ...
else commercial ...
```

spread throughout application code.

Classification-specific behaviour SHALL be centralised behind policy evaluation.

---

# 28. Classification transition

Classification changes SHALL be treated as controlled financial lifecycle transitions.

Two particularly important transitions are:

```text
INTERNAL → COMMERCIAL

COMMERCIAL → INTERNAL
```

Neither SHALL be implemented as a casual field update.

---

# 29. INTERNAL to COMMERCIAL

When authoritative classification changes:

```text
INTERNAL
    │
    ▼
COMMERCIAL
```

subscriptions SHALL evaluate the commercial prerequisites.

The transition may require:

```text
classification update
       │
       ▼
resolve commercial policy
       │
       ▼
resolve pricing
       │
       ▼
resolve billing account
       │
       ▼
resolve currency
       │
       ▼
provider provisioning if required
       │
       ▼
payment readiness if required
       │
       ▼
COMMERCIAL billing ACTIVE
```

Until required configuration is complete, the billing projection MAY remain:

```text
PENDING_CONFIGURATION
```

or:

```text
PROVISIONING
```

as defined by ADR-SUB-0003.

---

# 30. No retrospective commercial billing by default

An INTERNAL → COMMERCIAL transition SHALL NOT automatically cause historical INTERNAL usage to become retrospectively billable.

Default behaviour is:

```text
before effective transition:
    INTERNAL policy

from effective transition:
    COMMERCIAL policy
```

Retrospective rebilling requires an explicit, governed policy or correction mechanism.

This prevents classification changes from silently creating unexpected historical liabilities.

---

# 31. COMMERCIAL to INTERNAL

When authoritative classification changes:

```text
COMMERCIAL
    │
    ▼
INTERNAL
```

the billing engine SHALL:

1. establish the effective transition time;
2. stop creating new commercial monetary obligations after that effective boundary;
3. preserve legitimate historical commercial obligations;
4. retain usage metering;
5. prevent future payment execution for INTERNAL periods;
6. reconcile provider resources according to provider policy.

The transition SHALL NOT erase valid historical commercial billing.

---

# 32. Outstanding obligations survive classification change

Changing a subscription from COMMERCIAL to INTERNAL does not automatically cancel valid obligations created while it was COMMERCIAL.

Example:

```text
August:
    COMMERCIAL
    valid charge = ZAR 1,000

September:
    classification changes to INTERNAL
```

The September classification does not rewrite the August charge to zero.

Historical obligations remain governed by the policy applicable when they arose.

---

# 33. Effective transition time

Classification changes SHALL have an authoritative effective time or equivalent revision semantics.

The billing engine SHALL distinguish:

```text
decision time
effective time
received time
processed time
```

These may differ.

For example:

```text
classification decided: 10:00
effective from:          00:00 next month
event received:          10:01
processed:               10:02
```

Billing treatment SHALL respect the effective semantics rather than merely event arrival time.

---

# 34. Late classification events

If a classification event arrives after its effective time, the engine SHALL reconcile the affected billing period.

It SHALL NOT silently ignore the delay.

Depending on the consequences, reconciliation may:

- update future billing;
- generate a controlled correction;
- flag manual review;
- preserve an existing historical result where policy forbids retrospective mutation.

The operation SHALL be auditable.

---

# 35. Classification provenance

The billing projection SHOULD preserve sufficient reference to classification provenance to explain why the billing treatment was applied.

This does not make subscriptions authoritative for provenance.

It stores an operational projection/reference.

The system should be capable of answering:

```text
Why was this subscription zero-charge?

Because:
classification = INTERNAL
authoritative source = Control Plane
classification revision = X
billing policy version = Y
```

---

# 36. BillingDecision

Policy evaluation SHOULD produce a first-class internal result rather than scattered booleans.

Conceptually:

```text
BillingDecision
──────────────────────────────

classification
classification_revision

policy_id
policy_version

monetary_treatment
metering_requirement
rating_requirement

provider_requirement
payment_requirement

currency_requirement
pricing_requirement

effective_from

blockers[]
```

The exact implementation type is not prescribed by this ADR.

Its semantics are.

---

# 37. Monetary treatment

A billing decision SHOULD distinguish monetary treatment such as:

```text
ZERO
PRICED
NOT_APPLICABLE
```

rather than inferring it from amount alone.

This prevents:

```text
amount = 0
```

from being misinterpreted as INTERNAL.

---

# 38. Metering requirement

Metering policy SHOULD distinguish semantics such as:

```text
REQUIRED
OPTIONAL
NOT_APPLICABLE
```

The initial INTERNAL policy SHALL require metering where the subscribed product exposes meterable usage according to the canonical product contract.

COMMERCIAL metering depends on the applicable charging model and operational policy.

---

# 39. Rating requirement

Rating is distinct from metering.

For example:

```text
INTERNAL:
    meter = yes
    financial rating = not required
    analytical shadow rating = optional

COMMERCIAL fixed recurring:
    meter = optional/policy driven
    rating = fixed price

COMMERCIAL usage based:
    meter = required
    rating = required
```

The engine SHALL not conflate usage collection with monetary calculation.

---

# 40. Provider requirement

Billing policy SHALL determine whether external billing-provider resources are required.

Conceptually:

```text
provider_requirement:
    REQUIRED
    OPTIONAL
    NOT_REQUIRED
```

Initial semantics:

```text
INTERNAL:
    NOT_REQUIRED

COMMERCIAL:
    policy driven
```

ADR-SUB-0015 defines how provider operations are executed when required.

---

# 41. Payment requirement

Billing policy SHALL distinguish:

```text
PROHIBITED
NOT_REQUIRED
REQUIRED_WHEN_DUE
```

or equivalent semantics.

For INTERNAL:

```text
payment_requirement = PROHIBITED
```

For COMMERCIAL:

```text
payment_requirement = policy driven
```

This explicit distinction is important.

`NOT_REQUIRED` and `PROHIBITED` do not mean the same thing.

---

# 42. Payment boundary

When policy determines that a COMMERCIAL monetary obligation requires payment execution:

```text
Subscriptions
      │
      │ payment obligation
      ▼
baobab-payments
```

Subscriptions SHALL NOT:

- select payment processors directly;
- capture card payments directly;
- store provider card credentials;
- call HyperSwitch as though it were the subscriptions provider;
- implement payment orchestration locally.

Those responsibilities belong to `baobab-payments`.

---

# 43. INTERNAL payment safety barrier

Before emitting any payment obligation, the engine SHALL verify the current authoritative billing decision.

Conceptually:

```text
charge candidate
      │
      ▼
classification/policy check
      │
      ├── INTERNAL ──────► STOP
      │
      └── COMMERCIAL ────► continue if policy permits
```

This barrier SHALL exist even if upstream processing was expected to prevent an INTERNAL charge.

Financial safety should not depend on a single upstream assumption.

---

# 44. Provider safety barrier

Likewise, provider operations capable of producing monetary billing SHALL verify the applicable billing policy.

A stale or incorrectly mapped provider object SHALL NOT override current authoritative classification.

For example:

```text
Kill Bill subscription exists
        │
        ▼
CP classification now INTERNAL
        │
        ▼
Baobab policy prohibits monetary billing
```

The provider object does not authorise continued charging.

Reconciliation SHALL converge provider behaviour to the Baobab policy.

---

# 45. Pricing and provider separation

Pricing semantics SHALL remain Baobab-owned even where Kill Bill requires corresponding catalog configuration.

Conceptually:

```text
Baobab pricing policy
        │
        ▼
BillingTerms
        │
        ▼
KillBillAdapter
        │
        ▼
Kill Bill catalog representation
```

Not:

```text
Kill Bill catalog
        │
        ▼
defines Baobab commercial policy
```

---

# 46. Classification and capability separation

Classification SHALL NOT directly define which capabilities a subscription grants.

The flow remains:

```text
ProductSubscription
       │
       ├── classification ───► billing policy
       │
       └── product composition ───► CapabilityGrants
```

These are related aspects of the same subscription but have separate authorities.

For example, INTERNAL and COMMERCIAL subscriptions to the same product may grant the same capabilities while receiving different monetary treatment.

---

# 47. Classification and tenant lifecycle separation

Classification SHALL NOT bypass tenant admission or onboarding rules.

An INTERNAL subscription still requires valid platform authority.

The following is prohibited:

```text
classification = INTERNAL
       │
       ▼
skip tenant admission
```

Likewise, COMMERCIAL classification does not itself admit a tenant.

Admission and billing are separate platform concerns.

---

# 48. Classification and organisation relationships

The Control Plane may use canonical organisation relationships when deciding INTERNAL eligibility.

Subscriptions SHALL not reproduce those rules.

Therefore:

```text
Organisation graph
      │
      ▼
Control Plane eligibility decision
      │
      ▼
ProductSubscription classification
      │
      ▼
Subscriptions billing policy
```

This avoids duplicated corporate-governance logic.

---

# 49. Multi-tenant payer arrangements

Classification remains attached to the ProductSubscription even where a billing account pays for multiple consuming tenants.

For example:

```text
Corporate Group
      │
      ├── Tenant A
      │     ProductSubscription = COMMERCIAL
      │
      └── Tenant B
            ProductSubscription = COMMERCIAL

Central Billing Account
      │
      ├── pays A
      └── pays B
```

The payer arrangement does not merge the subscriptions or classifications.

---

# 50. Multi-market treatment

Classification SHALL not encode market.

For example:

```text
COMMERCIAL_ZA
COMMERCIAL_UG
```

SHOULD NOT be introduced merely because pricing differs by market.

Instead:

```text
classification = COMMERCIAL
market = ZA
pricing policy = applicable ZA policy
```

or:

```text
classification = COMMERCIAL
market = UG
pricing policy = applicable UG policy
```

Classification and market are orthogonal dimensions.

---

# 51. Currency treatment

Likewise, classification SHALL not encode currency.

A COMMERCIAL subscription may be billed in:

- ZAR;
- UGX;
- USD;
- or another supported currency,

according to applicable market, contract and billing policy.

Currency SHALL be explicit in the resulting BillingTerms.

---

# 52. Tax treatment

Classification SHALL NOT be used as a substitute for tax determination.

For example:

```text
COMMERCIAL != taxable
INTERNAL != tax-exempt legal classification
```

Tax treatment depends on the appropriate legal, market and transaction context.

Where taxes apply to commercial billing, they SHALL be determined through the appropriate authoritative policy/integration rather than inferred solely from subscription classification.

---

# 53. Fail-closed principle

Classification-driven billing SHALL fail closed whenever a financial decision cannot be made deterministically.

Examples:

```text
missing classification
unknown classification
missing required policy
missing pricing
unsupported currency
ambiguous effective date
unverifiable tenant context
conflicting classification revision
```

shall result in an explicit blocked or pending state.

The engine SHALL not guess.

---

# 54. No implicit free fallback

A particularly important invariant is:

```text
billing configuration failure
        ≠
free service
```

If COMMERCIAL pricing is missing, the engine SHALL NOT silently use zero.

If classification is unknown, the engine SHALL NOT silently use INTERNAL.

If payment infrastructure is unavailable, the engine SHALL NOT silently convert a commercial obligation into zero charge.

---

# 55. No implicit commercial fallback

The opposite is also true.

An unknown or missing classification SHALL NOT automatically become COMMERCIAL.

Doing so could create financial liabilities without authoritative policy.

The safe state is blocked pending authoritative resolution.

---

# 56. Temporary provider

The `TemporaryProvider` established by ADR-SUB-0001 is an implementation aid for development and integration.

It SHALL NOT change billing policy.

For example:

```text
COMMERCIAL
provider = TemporaryProvider
```

does not mean:

```text
production billing ready = true
```

where production policy requires Kill Bill or another production-capable provider.

Readiness SHALL report the limitation truthfully.

---

# 57. Billing readiness

Billing readiness SHALL be evaluated against the applicable policy.

For INTERNAL:

```text
authoritative classification valid
        +
billing projection valid
        +
required metering path available
        =
billing ready
```

A monetary provider may not be required.

For COMMERCIAL, readiness may require:

```text
authoritative classification
        +
pricing
        +
currency
        +
billing account
        +
provider readiness
        +
payment readiness where required
        =
commercial billing ready
```

The exact requirements are policy driven.

---

# 58. Policy blockers

The engine SHOULD use machine-readable blockers.

Examples:

```text
CLASSIFICATION_MISSING
CLASSIFICATION_UNSUPPORTED
CLASSIFICATION_REVISION_CONFLICT

BILLING_POLICY_MISSING
BILLING_POLICY_UNSUPPORTED

PRICING_CONFIGURATION_MISSING
CURRENCY_CONFIGURATION_MISSING

BILLING_ACCOUNT_MISSING
BILLING_PROVIDER_NOT_CONFIGURED
PAYMENT_PATH_NOT_READY
```

Human-readable explanations MAY accompany these codes.

Automation SHALL depend on stable codes, not message text.

---

# 59. Policy evaluation flow

```text
Authoritative ProductSubscription
             │
             ▼
       validate context
             │
             ▼
   classification present?
        ┌────┴────┐
       no        yes
        │          │
        ▼          ▼
     BLOCKED   supported?
                ┌──┴──┐
               no    yes
                │      │
                ▼      ▼
             BLOCKED  resolve canonical policy
                         │
                         ▼
                  evaluate requirements
                         │
              ┌──────────┴──────────┐
              ▼                     ▼
           INTERNAL             COMMERCIAL
              │                     │
              ▼                     ▼
         zero charge           resolve pricing
         meter/audit                 │
         no payment                  ▼
                               configuration valid?
                                  ┌──┴──┐
                                 no    yes
                                  │      │
                                  ▼      ▼
                               PENDING  billing path
```

---

# 60. INTERNAL flow

```text
ProductSubscription
classification = INTERNAL
        │
        ▼
verify authoritative classification
        │
        ▼
load INTERNAL billing policy
        │
        ▼
create/update BillingSubscriptionProjection
        │
        ├── monetary treatment = ZERO
        ├── metering = required/applicable
        ├── payment = PROHIBITED
        └── provider = NOT_REQUIRED
                 │
                 ▼
               ACTIVE
```

subject to all other required lifecycle and operational controls.

---

# 61. COMMERCIAL flow

```text
ProductSubscription
classification = COMMERCIAL
        │
        ▼
load commercial billing policy
        │
        ▼
resolve product/version
        │
        ▼
resolve pricing
        │
        ▼
resolve currency
        │
        ▼
resolve billing account
        │
        ▼
determine provider requirement
        │
        ▼
determine payment requirement
        │
        ▼
BillingDecision
        │
        ├── blockers? ─────► PENDING_CONFIGURATION
        │
        └── valid
              │
              ▼
          PROVISIONING
              │
              ▼
            ACTIVE
```

---

# 62. Policy changes

A new billing-policy version SHALL not silently mutate existing historical facts.

Policy rollout SHALL distinguish:

```text
new subscriptions
future billing periods
existing active subscriptions
historical billing periods
```

A policy may specify its migration scope.

Where migration changes monetary meaning, the change SHALL be explicit and auditable.

---

# 63. Reconciliation

Reconciliation defined by ADR-SUB-0003 SHALL include billing-policy consistency.

It SHALL be capable of detecting conditions such as:

| Drift | Example |
|---|---|
| Classification drift | Local INTERNAL, CP COMMERCIAL |
| Policy-version drift | Projection uses obsolete policy where migration required |
| Monetary-policy violation | INTERNAL projection has payable charge |
| Provider-policy violation | INTERNAL subscription has active monetary billing provider state |
| Payment-policy violation | Payment requested for INTERNAL |
| Pricing drift | COMMERCIAL projection references invalid pricing version |
| Readiness drift | Projection reports ACTIVE despite mandatory configuration blocker |

---

# 64. Severe invariant violations

Some discrepancies SHALL be treated as high-severity correctness failures.

In particular:

```text
INTERNAL subscription
      +
non-zero payable charge
```

or:

```text
INTERNAL subscription
      +
payment execution request
```

SHALL trigger:

- rejection of the operation;
- durable audit evidence;
- operational alerting;
- reconciliation requirement.

The platform SHALL prefer stopping an incorrect financial operation over maintaining apparent availability.

---

# 65. Audit requirements

Every material billing-policy decision SHALL be explainable using:

- ProductSubscription ID;
- tenant context;
- authoritative classification;
- classification revision/provenance reference;
- billing-policy ID/version;
- product/version;
- effective time;
- resulting BillingDecision;
- blockers where applicable;
- correlation and causation identifiers.

For monetary COMMERCIAL decisions, applicable pricing references SHALL also be retained.

---

# 66. Observability

Metrics SHOULD permit operators to understand:

- subscriptions by classification;
- billing projections by classification and lifecycle state;
- COMMERCIAL subscriptions blocked by missing configuration;
- INTERNAL usage volumes;
- classification transitions;
- unsupported classifications;
- policy-resolution failures;
- attempts to generate payment for INTERNAL subscriptions;
- policy reconciliation drift.

Sensitive financial or tenant information SHALL not be exposed through uncontrolled metric labels.

---

# 67. Security

Only trusted platform authority SHALL provide or mutate classification.

External tenant users SHALL NOT be able to obtain INTERNAL treatment by submitting:

```text
classification=INTERNAL
```

through a public request.

Caller-supplied classification SHALL never override authoritative Control Plane state.

Detailed workload identity and mutation controls are governed by ADR-SUB-0016.

---

# 68. API implications

Subscription APIs MAY expose the effective billing classification and billing decision where appropriate.

Such representations SHALL make clear that classification is projected from authoritative Control Plane state.

Mutation endpoints SHALL NOT permit arbitrary classification changes.

For example:

```text
GET /billing-subscriptions/{id}
```

may return projected classification information.

But:

```text
PATCH /billing-subscriptions/{id}

{
  "classification": "INTERNAL"
}
```

SHALL NOT be an authorised mechanism for changing subscription classification.

---

# 69. Event implications

Billing events MAY include projected classification where needed for downstream interpretation.

However, billing events SHALL NOT masquerade as authoritative classification-change events.

The authoritative classification lifecycle originates from the Control Plane.

Subscriptions events describe billing consequences.

---

# 70. Domain invariants

The following invariants SHALL hold.

### INV-POL-01

Subscription classification originates from the Control Plane.

### INV-POL-02

`baobab-subscriptions` cannot assign INTERNAL eligibility.

### INV-POL-03

Classification is ProductSubscription-scoped.

### INV-POL-04

Classification is not inferred from organisation identity or corporate relationship.

### INV-POL-05

INTERNAL monetary charge is zero.

### INV-POL-06

INTERNAL payment execution is prohibited.

### INV-POL-07

INTERNAL remains meterable and auditable.

### INV-POL-08

INTERNAL does not imply unconditional entitlement.

### INV-POL-09

INTERNAL does not require monetary provider resources solely for readiness.

### INV-POL-10

COMMERCIAL requires explicit applicable monetary policy.

### INV-POL-11

Missing commercial pricing cannot silently become zero pricing.

### INV-POL-12

A zero-valued COMMERCIAL charge does not change classification to INTERNAL.

### INV-POL-13

Missing or unknown classification fails closed.

### INV-POL-14

Unknown classification cannot default to INTERNAL.

### INV-POL-15

Unknown classification cannot default to COMMERCIAL.

### INV-POL-16

Historical obligations retain the policy applicable to their effective period.

### INV-POL-17

Classification change does not silently rewrite historical billing.

### INV-POL-18

Payment/provider state cannot determine classification.

### INV-POL-19

Classification does not directly determine CapabilityGrants.

### INV-POL-20

Classification does not replace market, currency or tax context.

### INV-POL-21

Billing-policy decisions are versioned and auditable.

### INV-POL-22

Financial safety takes precedence over optimistic billing availability.

---

# 71. Alternatives considered

## 71.1 Hard-code INTERNAL and COMMERCIAL throughout the service

**Rejected.**

It would spread policy across handlers, provider adapters and billing services, making future classifications difficult and dangerous to introduce.

Classification behaviour SHALL be centralised through billing policy.

---

## 71.2 Let Kill Bill determine whether a subscription is free or commercial

**Rejected.**

Kill Bill is a provider implementation and does not own Baobab subscription classification.

---

## 71.3 Treat INTERNAL as absence of a billing projection

**Rejected.**

INTERNAL subscriptions still require metering, audit, lifecycle visibility and potentially analytical cost attribution.

---

## 71.4 Treat INTERNAL as a zero-priced COMMERCIAL plan

**Rejected.**

This loses the semantic distinction between an authorised internal subscription and a commercial subscription whose current calculated amount happens to be zero.

---

## 71.5 Treat every zero charge as INTERNAL

**Rejected.**

Trials, credits and zero usage can produce zero-valued COMMERCIAL billing.

---

## 71.6 Let subscriptions determine INTERNAL eligibility from organisation relationships

**Rejected.**

That would duplicate Control Plane organisational governance and create competing authority.

---

## 71.7 Default unknown classifications to INTERNAL

**Rejected.**

This creates an unauthorised free-service path.

---

## 71.8 Default unknown classifications to COMMERCIAL

**Rejected.**

This risks creating unauthorised monetary obligations.

---

## 71.9 Stop metering INTERNAL subscriptions

**Rejected.**

It would remove operational visibility and undermine cost, capacity and governance analysis.

---

# 72. Consequences

## Positive

- INTERNAL and COMMERCIAL have precise platform semantics.
- INTERNAL cannot accidentally generate payments.
- Internal usage remains measurable.
- Zero-price commercial cases remain distinguishable from INTERNAL.
- Future classifications can be introduced cleanly.
- Classification logic remains under Control Plane authority.
- Billing policy remains provider-independent.
- Multi-market pricing does not pollute classification.
- Historical financial decisions remain explainable.
- Configuration failures cannot silently become free service.

## Negative

- Policy evaluation becomes an explicit architectural component.
- Billing decisions require policy/version persistence.
- Classification transitions require controlled reconciliation.
- Commercial activation may remain blocked until all required configuration is present.
- Additional validation is required before provider and payment operations.

These costs are accepted because classification affects financial obligations and must not depend on scattered assumptions.

---

# 73. Implementation requirements

Implementation conforming to this ADR SHALL:

1. consume authoritative classification from the Control Plane contract;
2. resolve classification through canonical billing policy;
3. introduce an explicit `BillingDecision` or equivalent domain abstraction;
4. centralise classification-specific billing behaviour;
5. enforce zero monetary treatment for INTERNAL;
6. enforce payment prohibition for INTERNAL;
7. retain INTERNAL metering where applicable;
8. distinguish classification from calculated monetary amount;
9. fail closed for unknown/missing classification;
10. fail closed for missing required commercial pricing;
11. version billing-policy decisions;
12. preserve effective-time semantics;
13. treat classification transitions as controlled lifecycle operations;
14. prevent provider state from overriding classification;
15. prevent payment execution without policy validation;
16. expose stable machine-readable configuration blockers;
17. support reconciliation of classification/policy drift;
18. preserve audit evidence for material policy decisions.

---

# 74. Required tests

At minimum, automated tests SHALL cover:

### INTERNAL

- valid INTERNAL projection;
- INTERNAL usage recording;
- INTERNAL zero monetary charge;
- INTERNAL payment attempt rejected;
- INTERNAL without Kill Bill resource remains valid where policy permits;
- INTERNAL high usage remains zero-charge;
- INTERNAL shadow rating cannot create payable obligation.

### COMMERCIAL

- fixed-price commercial billing decision;
- usage-based commercial decision;
- COMMERCIAL with missing pricing becomes blocked;
- COMMERCIAL zero-valued trial remains COMMERCIAL;
- COMMERCIAL full credit remains COMMERCIAL;
- payment requirement follows policy rather than classification alone.

### Classification safety

- missing classification rejected;
- unsupported classification rejected;
- caller-supplied INTERNAL cannot override Control Plane classification;
- organisation name cannot infer classification;
- provider configuration cannot infer classification.

### Transitions

- INTERNAL → COMMERCIAL;
- COMMERCIAL → INTERNAL;
- delayed effective transition;
- late classification event;
- historical obligations preserved;
- no automatic retrospective charge of prior INTERNAL period.

### Reconciliation

- local classification differs from CP;
- INTERNAL projection contains payable charge;
- payment was requested for INTERNAL;
- provider state conflicts with current classification;
- obsolete policy version requiring migration.

---

# 75. Implementation sequencing

Recommended implementation order:

```text
1. Canonical billing-policy contract
            │
            ▼
2. BillingPolicyResolver
            │
            ▼
3. BillingDecision domain type
            │
            ▼
4. INTERNAL policy enforcement
            │
            ▼
5. COMMERCIAL policy enforcement
            │
            ▼
6. Configuration blockers
            │
            ▼
7. Classification transition handling
            │
            ▼
8. Policy reconciliation
            │
            ▼
9. Provider/payment safety barriers
```

No Kill Bill-specific classification logic should be introduced during these steps.

---

# 76. Relationship to ADR-SUB-0015

ADR-SUB-0006 decides **whether provider participation is required**.

ADR-SUB-0015 decides **how provider participation is implemented**.

The relationship is:

```text
ADR-SUB-0006
Billing Policy
      │
      │ provider required?
      ▼
ADR-SUB-0015
BillingProvider Port
      │
      ▼
KillBillAdapter
      │
      ▼
Kill Bill
```

Kill Bill SHALL therefore consume a provider decision.

It SHALL NOT make the classification decision.

---

# 77. Relationship to ADR-SUB-0016

ADR-SUB-0006 establishes that classification and billing-policy manipulation are financially sensitive operations.

ADR-SUB-0016 SHALL define:

- workload authentication;
- authorisation;
- tenant-context trust;
- controlled mutation;
- audit;
- privileged operations;
- reconciliation authority.

In particular, ADR-SUB-0016 SHALL ensure that no untrusted caller can manufacture INTERNAL treatment.

---

# 78. Final decision

Baobab SHALL use **classification-driven, provider-independent billing policy** to translate authoritative Control Plane subscription classification into billing behaviour.

The governing architecture is:

```text
             BAOBAB CONTROL PLANE
                     │
                     │ authoritative
                     ▼
          ProductSubscription
                     │
             classification
                     │
                     ▼
             CANONICAL POLICY
                     │
                     ▼
            BillingDecision
              /           \
             /             \
            ▼               ▼
       INTERNAL         COMMERCIAL
            │               │
     zero monetary       priced according
        charge             to policy
            │               │
       metering          billing/provider
            │            as required
       auditing               │
            │             payment when
      NO PAYMENT            required
```

The principal rule is:

> **Classification determines which billing policy applies; it does not itself perform billing. Control Plane owns the classification, Subscriptions owns its billing consequence, Payments owns monetary execution, and provider technology implements rather than defines Baobab policy.**

For `INTERNAL`, the invariant is particularly strict:

> **Zero monetary charge does not mean zero governance, zero metering, zero audit, or unrestricted access. It means that an authorised subscription receives its service without a monetary payment obligation while remaining fully visible to Baobab's lifecycle, usage, entitlement and control mechanisms.**

For `COMMERCIAL`:

> **Commercial classification authorises application of monetary billing policy; it does not authorise invented pricing, implicit provider assumptions, or uncontrolled payment. Missing financial configuration fails closed rather than silently becoming either free service or an arbitrary charge.**