# ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Provider Integration / Anti-Corruption Layer  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None

**Related:**

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-018 — Canonical Organisation and Tenant Relationships
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-PAY-0001 — Adopt HyperSwitch as the Headless Baobab Payment Orchestration Engine
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- `shared/contracts/subscriptions/v1`
- `shared/contracts/product/v1`
- `shared/contracts/payments/v1`

---

# 1. Context

ADR-SUB-0001 adopts Kill Bill as the foundational subscription billing implementation for Baobab.

That decision does **not** make Kill Bill the Baobab subscription domain.

Subsequent ADRs establish that:

- `baobab-cp` owns `ProductSubscription`, classification, tenant identity and entitlement authority;
- `baobab-subscriptions` owns `BillingSubscriptionProjection` and billing behaviour;
- billing policy determines whether and how monetary billing applies;
- INTERNAL subscriptions are zero-charge and must never generate payment obligations;
- COMMERCIAL subscriptions are billed according to explicit policy;
- `baobab-payments` owns monetary execution;
- `baobab-erp` owns accounting;
- billing state, ProductSubscription state and provider state are separate;
- provider state must be reconciled rather than treated as authoritative platform state.

Kill Bill therefore occupies a deliberately constrained architectural position:

```text
Baobab Domain
      │
      ▼
BillingProvider Port
      │
      ▼
KillBillAdapter
      │
      ▼
Kill Bill
```

The platform must prevent the more dangerous architecture:

```text
Baobab APIs
     │
     ▼
Kill Bill API
     │
     ▼
Kill Bill objects
     │
     ▼
Baobab starts depending on
Kill Bill semantics everywhere
```

That would create vendor and implementation coupling throughout the ecosystem.

This ADR defines the provider boundary, anti-corruption layer, provider mappings, failure semantics, portability requirements and migration principles.

---

# 2. Decision

`baobab-subscriptions` SHALL integrate Kill Bill exclusively through a **Baobab-owned BillingProvider port and KillBillAdapter anti-corruption layer**.

Kill Bill SHALL NOT be directly exposed to:

- `baobab-cp`;
- `baobab-payments`;
- `baobab-erp`;
- digital estates;
- tenants;
- public APIs;
- Shared canonical contracts;
- other Baobab engines.

The architecture SHALL be:

```text
                 BAOBAB ECOSYSTEM
                        │
                        ▼
              baobab-subscriptions
                        │
                Baobab Domain
                        │
                        ▼
                BillingProvider
                   Provider Port
                        │
          ┌─────────────┴─────────────┐
          ▼                           ▼
 TemporaryProvider              KillBillAdapter
                                      │
                                      ▼
                                   Kill Bill
```

Future providers MAY implement the same port without changing canonical consumers.

---

# 3. Architectural objective

The provider architecture SHALL optimise for four properties:

1. **domain independence**;
2. **provider replaceability**;
3. **financial correctness**;
4. **operational recoverability**.

Portability does not mean reducing every provider to the lowest common denominator.

It means Baobab owns the semantics required by Baobab and adapters translate those semantics into provider-specific capabilities.

---

# 4. Kill Bill is implementation, not authority

Kill Bill SHALL NOT be authoritative for:

| Concern | Authority |
|---|---|
| Organisation | `baobab-cp` |
| Tenant | `baobab-cp` |
| PlatformAccount | `baobab-cp` |
| Product | `baobab-cp` |
| ProductSubscription | `baobab-cp` |
| Subscription classification | `baobab-cp` |
| INTERNAL eligibility | `baobab-cp` |
| CapabilityGrant | `baobab-cp` |
| Billing policy | Baobab canonical policy |
| BillingSubscriptionProjection | `baobab-subscriptions` |
| Billing provider mapping | `baobab-subscriptions` |
| Payment execution | `baobab-payments` |
| Accounting | `baobab-erp` |

Kill Bill is authoritative only for its own provider-local operational state.

---

# 5. No provider leakage

Kill Bill-specific concepts SHALL NOT appear in canonical Shared contracts except where explicitly represented as opaque provider metadata.

The following SHALL NOT become canonical Baobab concepts merely because Kill Bill uses them:

- Kill Bill Account;
- Bundle;
- Subscription;
- Plan;
- PlanPhase;
- Catalog;
- Invoice;
- InvoiceItem;
- Payment;
- Plugin property;
- Kill Bill-specific state values.

Baobab contracts SHALL instead expose Baobab concepts.

---

# 6. Semantic separation

The following equivalences are explicitly rejected:

```text
ProductSubscription
    != Kill Bill Subscription

BillingSubscriptionProjection
    != Kill Bill Subscription

BillingAccountProjection
    != Kill Bill Account

ProductVersion
    != Kill Bill Plan

BillingTerms
    != Kill Bill Catalog

InvoiceProjection
    != Kill Bill Invoice

Charge
    != Kill Bill InvoiceItem
```

Mappings may exist.

Identity does not.

---

# 7. Anti-corruption layer

The `KillBillAdapter` SHALL function as an anti-corruption layer.

Its responsibility is to translate:

```text
Baobab intent
      │
      ▼
Kill Bill operations
```

and:

```text
Kill Bill observations
      │
      ▼
Baobab provider observations
```

Provider-specific vocabulary SHALL terminate at this boundary.

---

# 8. BillingProvider port

The application/domain layer SHALL depend on a Baobab-owned `BillingProvider` abstraction.

Conceptually:

```text
BillingProvider
────────────────────────

ensureAccount(...)
ensureSubscription(...)

suspend(...)
resume(...)
cancel(...)

recordUsage(...)

observeAccount(...)
observeSubscription(...)

health(...)
```

The exact programming-language interface MAY evolve during implementation.

The semantic boundary defined by this ADR SHALL remain.

---

# 9. Intent-oriented operations

The provider port SHOULD expose business intent rather than thin wrappers around provider HTTP endpoints.

Preferred:

```text
ensureSubscription(desiredSubscription)
```

over:

```text
postKillBillSubscription(json)
```

Preferred:

```text
suspend(subscriptionRef)
```

over:

```text
putSubscriptionWithRequestedDate(...)
```

This keeps provider mechanics inside the adapter.

---

# 10. Ensure semantics

Provisioning operations SHOULD use **ensure semantics** wherever practical.

For example:

```text
ensureAccount(desired)
```

means:

> Ensure that an appropriate provider account representing this Baobab billing account exists and return the observed provider reference.

It does not necessarily mean:

> Create a new provider account.

The operation may:

```text
existing valid mapping
        │
        ▼
return existing

missing mapping
        │
        ▼
search deterministically
        │
     ┌──┴──┐
   found  absent
     │      │
     ▼      ▼
 recover   create
 mapping
```

This supports safe retries and recovery.

---

# 11. Provider-neutral inputs

The domain layer SHALL provide provider-neutral intent objects.

Conceptually:

```text
DesiredBillingAccount

DesiredBillingSubscription

DesiredUsageRecord

DesiredSuspension

DesiredCancellation
```

These objects SHALL contain Baobab semantics, not Kill Bill request payloads.

---

# 12. Provider-neutral outputs

Provider adapters SHALL return provider-neutral outcomes.

Conceptually:

```text
ProviderResult<T>
```

with semantics capable of distinguishing:

```text
SUCCESS
ALREADY_CONVERGED
RETRYABLE_FAILURE
PERMANENT_FAILURE
CONFLICT
UNKNOWN_OUTCOME
```

or equivalent typed outcomes.

The domain SHALL not parse provider HTTP response codes throughout the application.

---

# 13. Unknown outcome is first-class

A provider operation can have an ambiguous result.

Example:

```text
Baobab
   │
   │ create
   ▼
Kill Bill
   │
   │ commit
   ▼
response lost
```

Baobab cannot safely conclude either success or failure.

The adapter SHALL therefore support:

```text
UNKNOWN_OUTCOME
```

as a first-class result.

This state SHALL trigger reconciliation before any unsafe retry.

---

# 14. ProviderReference

All Kill Bill resource identifiers SHALL be represented through explicit provider mappings.

Conceptually:

```text
ProviderReference
────────────────────────────

provider
resource_type

baobab_resource_type
baobab_resource_id

provider_resource_id

provider_metadata

created_at
last_verified_at
```

For example:

```text
billing_subscription_id
    = bs_01ABC

provider
    = killbill

provider_resource_id
    = 5f19...uuid
```

The Kill Bill UUID SHALL never replace the Baobab identifier.

---

# 15. Mapping direction

Provider mappings SHALL be resolvable in both directions where operationally required:

```text
Baobab ID
    │
    ▼
ProviderReference
    │
    ▼
Kill Bill ID
```

and for incoming provider observations:

```text
Kill Bill ID
    │
    ▼
ProviderReference
    │
    ▼
Baobab ID
```

The mapping remains owned by `baobab-subscriptions`.

---

# 16. Provider metadata

Provider-specific metadata MAY be persisted where necessary for:

- reconciliation;
- diagnostics;
- migration;
- provider version compatibility;
- operational support.

Such metadata SHALL remain provider-local.

It SHALL NOT become required input to canonical consumers.

---

# 17. Kill Bill Account mapping

A Kill Bill Account MAY represent a Baobab `BillingAccountProjection`.

It SHALL NOT become the Baobab PlatformAccount.

The relationship is:

```text
PlatformAccount
      │
      │ authoritative reference
      ▼
BillingAccountProjection
      │
      │ provider mapping
      ▼
Kill Bill Account
```

The Kill Bill account therefore exists two authority boundaries away from platform identity.

---

# 18. Kill Bill Subscription mapping

A Kill Bill subscription SHALL represent provider execution of a Baobab billing projection.

The relationship is:

```text
ProductSubscription
      │
      ▼
BillingSubscriptionProjection
      │
      ▼
ProviderReference
      │
      ▼
Kill Bill Subscription
```

A Kill Bill subscription SHALL NOT directly represent the authoritative Control Plane ProductSubscription.

---

# 19. Bundle mapping

If Kill Bill requires Bundle semantics, the adapter SHALL manage them internally.

Baobab SHALL NOT introduce `Bundle` as a canonical platform concept solely to satisfy Kill Bill.

Conceptually:

```text
Baobab billing projection
        │
        ▼
KillBillAdapter
        │
        ├── Bundle
        └── Subscription
```

Bundle lifecycle is an adapter concern unless a future Baobab domain requirement independently justifies an equivalent concept.

---

# 20. Catalog mapping

Kill Bill catalog configuration SHALL be generated or managed from Baobab billing semantics.

The direction is:

```text
Baobab Product/Pricing/Billing Policy
                │
                ▼
           BillingTerms
                │
                ▼
         KillBillAdapter
                │
                ▼
        Kill Bill Catalog
```

Not:

```text
Kill Bill Catalog
       │
       ▼
defines Baobab products
```

---

# 21. Catalog versioning

The adapter SHALL preserve the relationship between:

- Baobab product version;
- pricing version;
- billing-policy version;
- corresponding provider configuration.

Historical provider configuration SHALL remain traceable.

A provider catalog update SHALL NOT silently reinterpret historical Baobab billing.

---

# 22. Provider naming

Provider-side names SHALL not be relied upon as canonical identity.

For example:

```text
"baobab-pro-plan"
```

may be useful as provider metadata but SHALL NOT replace stable Baobab identifiers and explicit mappings.

Human-readable names change.

Canonical identity must not depend on them.

---

# 23. INTERNAL subscriptions

Under ADR-SUB-0006:

```text
classification = INTERNAL
provider_requirement = NOT_REQUIRED
payment_requirement = PROHIBITED
```

Therefore the KillBillAdapter SHALL normally not be invoked to create monetary billing resources for INTERNAL subscriptions.

Valid INTERNAL state includes:

```text
BillingSubscriptionProjection = ACTIVE
ProviderReference = NONE
Kill Bill Subscription = NONE
```

This is not provider drift.

It is policy-compliant behaviour.

---

# 24. Existing provider resource after INTERNAL transition

If a subscription transitions:

```text
COMMERCIAL
    │
    ▼
INTERNAL
```

an existing Kill Bill resource may remain from the prior commercial period.

The adapter SHALL reconcile it according to policy.

The resource SHALL NOT continue producing unauthorised monetary billing.

Possible provider treatment may include:

- cancellation;
- suspension;
- end-dating;
- archival/provider-specific equivalent.

The exact operation depends on provider capabilities.

Historical references SHALL be retained.

---

# 25. COMMERCIAL subscriptions

For COMMERCIAL subscriptions, ADR-SUB-0006 determines whether provider participation is required.

Only after policy resolution SHALL subscriptions invoke:

```text
BillingProvider
```

The provider does not decide whether the subscription should be commercial.

---

# 26. Provider provisioning flow

```text
COMMERCIAL BillingDecision
           │
           ▼
provider required?
       ┌───┴───┐
      no      yes
       │        │
       ▼        ▼
 continue    ensureAccount
                 │
                 ▼
          ensureSubscription
                 │
                 ▼
           persist mappings
                 │
                 ▼
          observe provider
                 │
                 ▼
              converge
```

---

# 27. Provider idempotency

Every provider mutation SHALL be safe under retry.

The adapter SHALL use the strongest provider-supported idempotency mechanism available.

Where provider-native idempotency is insufficient, Baobab SHALL supplement it with:

- durable operation identity;
- provider mappings;
- deterministic lookup;
- reconciliation.

The platform SHALL not assume HTTP retry safety.

---

# 28. Idempotency identity

Provider operations SHOULD be correlated with a durable Baobab operation identifier.

Conceptually:

```text
provider_operation_id
idempotency_key
correlation_id
causation_id
```

These identifiers SHALL be retained sufficiently to reconstruct ambiguous operations.

---

# 29. Duplicate prevention

Before creating provider resources, the adapter SHALL determine whether the intended resource already exists.

The process SHOULD be equivalent to:

```text
provider mapping exists?
       │
   ┌───┴───┐
  yes     no
   │       │
   ▼       ▼
verify   deterministic lookup
            │
        ┌───┴───┐
      found    absent
        │        │
        ▼        ▼
     recover    create
     mapping
```

Blind create-on-retry is prohibited.

---

# 30. Desired versus observed state

As established by ADR-SUB-0003, the adapter SHALL distinguish desired Baobab state from observed provider state.

Example:

```text
Desired:
    ACTIVE

Observed Kill Bill:
    CANCELLED
```

The adapter reports the observation.

The domain/reconciliation layer determines the appropriate recovery action.

---

# 31. Observation must not mutate authority

Reading provider state SHALL NOT itself modify:

- ProductSubscription;
- classification;
- entitlement;
- billing policy.

Provider observations are evidence about provider execution.

They are not platform authority.

---

# 32. Reconciliation

The KillBillAdapter SHALL expose enough observational capability for the reconciliation mechanism established by ADR-SUB-0003.

At minimum reconciliation must be capable of determining:

- whether expected account resources exist;
- whether expected subscription resources exist;
- current provider lifecycle state;
- whether mappings remain valid;
- whether duplicates appear to exist;
- whether provider state differs from desired state.

---

# 33. Provider drift

Examples of provider drift include:

```text
Baobab ACTIVE
Kill Bill CANCELLED
```

```text
Baobab TERMINATED
Kill Bill ACTIVE
```

```text
Baobab provider mapping exists
Kill Bill resource missing
```

```text
one Baobab projection
multiple Kill Bill subscriptions
```

Such conditions SHALL become reconciliation findings.

They SHALL not be silently ignored.

---

# 34. Duplicate provider resources

Duplicate Kill Bill resources representing one Baobab projection are financially sensitive.

Automatic destructive repair SHALL occur only where the correct resource can be established unambiguously.

Otherwise:

```text
MANUAL_REVIEW_REQUIRED
```

is the correct result.

The platform SHALL not guess which potentially billable resource should survive.

---

# 35. Provider health

`BillingProvider.health()` SHALL report provider integration health without implying billing correctness.

For example:

```text
provider reachable = true
```

does not imply:

```text
all provider subscriptions synchronized = true
```

Provider connectivity and reconciliation health SHALL remain separate.

---

# 36. Failure taxonomy

The adapter SHALL translate provider-specific failures into Baobab-owned failure semantics.

At minimum:

| Failure class | Meaning |
|---|---|
| Validation | Provider rejected invalid request |
| Authentication | Provider authentication failed |
| Authorization | Operation not permitted |
| Conflict | Provider state conflicts with requested operation |
| Not Found | Expected provider resource missing |
| Rate Limited | Provider throttled request |
| Transient | Temporary provider/network failure |
| Permanent | Retry will not resolve without change |
| Unknown Outcome | Request result cannot safely be established |

Provider HTTP status codes MAY contribute to classification but SHALL not leak as domain policy.

---

# 37. Retry policy

The adapter SHALL NOT blindly retry all failures.

Conceptually:

```text
provider failure
      │
      ▼
classify
      │
      ├── transient ─────► bounded retry
      │
      ├── rate limit ────► provider-aware backoff
      │
      ├── validation ────► stop
      │
      ├── auth ──────────► stop/alert
      │
      ├── conflict ──────► reconcile
      │
      └── unknown ───────► reconcile before retry
```

---

# 38. Backoff

Retryable provider calls SHOULD use bounded exponential backoff with jitter or equivalent production-grade retry policy.

Retries SHALL be observable.

Retry exhaustion SHALL result in durable operational state rather than disappearing into logs.

---

# 39. Circuit breaking

The provider integration SHOULD support circuit-breaking or equivalent protection when Kill Bill is persistently unavailable.

The objective is to prevent:

- retry storms;
- resource exhaustion;
- cascading failure.

Circuit state SHALL not alter billing authority.

It changes only whether provider operations can currently be attempted.

---

# 40. Timeouts

Every remote provider operation SHALL use bounded timeouts.

An unbounded Kill Bill request SHALL NOT hold application resources indefinitely.

Timeout configuration SHOULD distinguish operations where appropriate.

A timeout SHALL be treated according to whether provider execution may have occurred.

---

# 41. Kill Bill unavailability

Kill Bill unavailability SHALL NOT:

- invalidate ProductSubscriptions;
- change classification;
- revoke CapabilityGrants directly;
- fabricate billing success;
- silently convert COMMERCIAL to INTERNAL.

Instead it SHALL create operational degradation and, where relevant, pending provider work.

---

# 42. Payment separation

Kill Bill may have payment-related capabilities, but Baobab has explicitly separated payment orchestration into `baobab-payments`.

Therefore `KillBillAdapter` SHALL NOT become Baobab's payment orchestration engine.

The architecture is:

```text
Billing calculation
       │
       ▼
baobab-subscriptions
       │
       ├────────► Kill Bill
       │          billing provider
       │
       └────────► baobab-payments
                  monetary execution
```

Not:

```text
baobab-subscriptions
       │
       ▼
Kill Bill
       │
       ▼
payment processor
```

where this bypasses `baobab-payments`.

---

# 43. Provider payment features

Where Kill Bill intrinsically exposes payment features, the adapter SHALL configure or use Kill Bill in a manner consistent with Baobab's payment boundary.

Kill Bill SHALL NOT independently select, execute or own Baobab payment routing where that responsibility belongs to `baobab-payments`.

Any unavoidable provider interaction with payment-related constructs SHALL remain an implementation concern and SHALL not displace canonical payment authority.

---

# 44. ERP separation

Kill Bill invoice or payment records SHALL NOT become Baobab's accounting ledger.

The flow remains:

```text
Kill Bill
   │
   │ provider observation
   ▼
baobab-subscriptions
   │
   │ canonical billing fact
   ▼
baobab-erp
   │
   ▼
accounting
```

ERP SHALL not depend directly on Kill Bill.

---

# 45. Events

Kill Bill-native events SHALL NOT be published directly as Baobab canonical events.

The adapter SHALL translate relevant provider observations into Baobab domain outcomes.

The domain layer may then emit canonical billing events.

Therefore:

```text
Kill Bill event
      │
      ▼
KillBillAdapter
      │
      ▼
provider observation
      │
      ▼
Baobab domain transition
      │
      ▼
canonical billing event
```

---

# 46. Webhooks and callbacks

If Kill Bill or plugins produce callbacks/webhooks, they SHALL terminate at a provider-specific ingress boundary.

That boundary SHALL:

1. authenticate or otherwise verify the provider;
2. validate the provider payload;
3. deduplicate delivery;
4. resolve the provider reference;
5. establish trusted tenant context from internal mapping;
6. translate the payload into a provider observation;
7. invoke domain/reconciliation processing.

The provider SHALL NOT be trusted to establish Baobab tenant identity merely by supplying a tenant identifier.

---

# 47. Provider callback isolation

A callback containing:

```text
provider_subscription_id = X
```

SHALL be resolved through:

```text
ProviderReference
```

to determine the Baobab resource and tenant.

Caller-supplied Baobab tenant IDs SHALL not override that mapping.

This protects tenant isolation at provider ingress.

---

# 48. TemporaryProvider

The repository MAY maintain a `TemporaryProvider` for:

- local development;
- unit/integration testing;
- contract testing;
- lifecycle development before Kill Bill integration is complete.

It SHALL implement the same BillingProvider semantics where applicable.

---

# 49. TemporaryProvider limitations

`TemporaryProvider` SHALL be explicitly marked as non-production monetary infrastructure.

It SHALL NOT:

- contact real payment rails;
- create real monetary obligations outside Baobab's test environment;
- claim production Kill Bill readiness;
- silently activate itself in production.

Production configuration SHALL fail closed if the required production provider is absent.

---

# 50. Provider selection

Provider selection SHALL be explicit configuration or policy.

The domain SHALL not contain scattered code such as:

```text
if provider == "killbill"
```

throughout business logic.

Selection SHALL occur at the provider boundary.

Conceptually:

```text
BillingProviderFactory
        │
        ├── temporary
        ├── killbill
        └── future
```

The exact dependency-injection mechanism is implementation-specific.

---

# 51. Provider capability declaration

Different providers may support different features.

The provider abstraction SHOULD expose provider capabilities explicitly where necessary.

For example:

```text
ProviderCapabilities
────────────────────────

supports_usage
supports_suspension
supports_delayed_cancellation
supports_catalog_versioning
supports_native_idempotency
...
```

The domain SHALL not discover unsupported features only after unsafe execution.

---

# 52. Capability negotiation

When a billing policy requires a feature the configured provider does not support:

```text
required capability
       │
       ▼
provider supports?
    ┌──┴──┐
   yes    no
    │      │
    ▼      ▼
 proceed  BLOCKED
```

The engine SHALL not silently approximate financially meaningful behaviour unless an explicit Baobab policy authorises the approximation.

---

# 53. Provider portability

A future provider SHALL be adoptable by implementing the BillingProvider port and satisfying Baobab contract tests.

Conceptually:

```text
                   BillingProvider
                         │
         ┌───────────────┼────────────────┐
         ▼               ▼                ▼
 TemporaryProvider  KillBillAdapter  FutureProvider
```

No upstream Baobab engine should require changes merely because the underlying billing provider changes.

---

# 54. Portability boundary

Provider portability SHALL protect:

- canonical APIs;
- canonical events;
- BillingSubscriptionProjection IDs;
- ProductSubscription IDs;
- billing-account IDs;
- usage contract;
- payment integration;
- ERP integration.

Provider replacement MAY require:

- provider mappings;
- provider configuration;
- adapter code;
- migration workflows;
- provider-specific operational tooling.

That is the intended boundary.

---

# 55. Provider migration

Provider replacement SHALL be treated as a controlled migration.

A migration SHALL conceptually support:

```text
Current provider
      │
      ▼
inventory provider resources
      │
      ▼
compare with Baobab projections
      │
      ▼
resolve discrepancies
      │
      ▼
provision target provider
      │
      ▼
establish new mappings
      │
      ▼
verify target state
      │
      ▼
controlled cutover
      │
      ▼
retire old provider resources
```

Provider migration SHALL NOT redefine canonical subscription identity.

---

# 56. Dual-provider migration

During controlled migration, a billing projection MAY temporarily have references to more than one provider.

For example:

```text
BillingSubscriptionProjection
       │
       ├── Kill Bill reference
       │      status = retiring
       │
       └── Future Provider reference
              status = active
```

The platform SHALL explicitly designate which provider is authoritative for new provider execution during the migration window.

It SHALL not allow uncontrolled dual billing.

---

# 57. Single active monetary execution path

For a given billing obligation and effective period, provider migration SHALL preserve the invariant:

> Only one authorised provider path may create the operative billing consequence unless an explicitly designed migration strategy says otherwise.

Dual-running providers for comparison SHALL not result in duplicate customer obligations.

---

# 58. Migration audit

Provider migration SHALL preserve:

- old provider references;
- new provider references;
- mapping history;
- migration timestamps;
- reconciliation results;
- cutover decision;
- operator/workload identity;
- correlation IDs.

Historical billing SHALL remain traceable to the provider that actually processed it.

---

# 59. Provider version pinning

Production Kill Bill deployments SHALL use an explicitly pinned and verified version.

The platform SHALL NOT deploy:

```text
killbill:latest
```

or equivalent unbounded provider versions.

ADR-SUB-0001 recorded that the exact production release and supported PostgreSQL/Java compatibility still require verification at the integration gate.

That verification SHALL occur before production adoption.

---

# 60. Compatibility verification

Before pinning a production Kill Bill version, implementation SHALL verify from authoritative upstream documentation or source:

- supported Java version;
- supported PostgreSQL version;
- schema requirements;
- upgrade path;
- API compatibility;
- plugin compatibility where used;
- operational requirements;
- backup/restore expectations.

Baobab's PostgreSQL 17 choice for its own subscription façade database SHALL NOT be assumed to imply that the selected Kill Bill release supports PostgreSQL 17 for Kill Bill's own database.

These are separate persistence concerns.

---

# 61. Database isolation

Kill Bill SHALL use its own provider-owned schema/database according to supported deployment requirements.

`baobab-subscriptions` SHALL maintain its own Baobab-owned persistence.

The architecture is:

```text
baobab-subscriptions
       │
       ▼
Baobab subscription DB

KillBillAdapter
       │
       ▼
Kill Bill API
       │
       ▼
Kill Bill DB
```

The subscriptions service SHALL NOT directly read or write Kill Bill tables.

---

# 62. No database integration

The following is prohibited:

```text
baobab-subscriptions
        │
        ▼
SELECT * FROM killbill...
```

or direct writes into Kill Bill tables.

The provider API is the integration boundary.

This preserves provider upgradeability and portability.

---

# 63. Secrets

Kill Bill credentials and provider secrets SHALL:

- never be committed to source control;
- never be embedded in canonical contracts;
- never be returned through public APIs;
- be provided through approved secret-management mechanisms;
- be scoped according to least privilege;
- be rotatable.

Detailed security requirements are defined by ADR-SUB-0016.

---

# 64. Tenant isolation

Provider resources SHALL be associated with trusted Baobab tenant context through internal mappings.

Provider metadata alone SHALL NOT establish tenant authority.

For every provider operation:

```text
trusted tenant context
       +
Baobab billing resource
       +
provider mapping
       │
       ▼
provider operation
```

Cross-tenant provider reference use SHALL fail closed.

---

# 65. Observability

Provider integration SHALL expose operational telemetry sufficient to observe:

- request latency;
- request failure rate;
- provider availability;
- timeout rate;
- retry rate;
- circuit-breaker state;
- unknown outcomes;
- reconciliation backlog;
- provider drift;
- duplicate provider resources;
- missing provider mappings;
- provider operation success by operation class.

Sensitive provider or tenant information SHALL not be placed in uncontrolled metric labels.

---

# 66. Structured logging

Provider operations SHOULD emit structured logs containing appropriate:

```text
correlation_id
provider_operation_id
billing_subscription_id
provider
operation
outcome
duration
```

Sensitive secrets and payment information SHALL not be logged.

Provider payload logging SHALL be conservative and sanitised.

---

# 67. Tracing

Distributed traces SHOULD preserve the boundary:

```text
Control Plane
     │
     ▼
Subscriptions
     │
     ▼
KillBillAdapter
     │
     ▼
Kill Bill
```

Trace context SHOULD also continue independently to `baobab-payments` where payment execution follows.

Provider implementation details SHALL not become business identifiers merely for tracing convenience.

---

# 68. Provider readiness

Provider readiness SHALL be distinguishable from basic service health.

For example:

```text
subscriptions process = healthy
database = healthy
Kill Bill = unreachable
```

should result in:

```text
service health = DEGRADED
provider readiness = NOT_READY
```

rather than claiming full commercial billing readiness.

---

# 69. Startup behaviour

The service MAY start while Kill Bill is temporarily unavailable if doing so permits safe operation of non-provider functionality.

However, it SHALL report degraded readiness and SHALL NOT falsely accept provider-dependent work as completed.

Whether provider unavailability blocks startup entirely MAY be environment/policy dependent.

---

# 70. Production configuration

Production SHALL explicitly configure:

- provider type;
- provider endpoint;
- authentication mechanism;
- timeout policy;
- retry policy;
- circuit-breaker policy;
- provider version expectations;
- feature/capability expectations.

Unsafe defaults SHALL not silently activate.

---

# 71. Provider contract tests

Every production BillingProvider implementation SHALL pass a common provider contract suite.

The suite SHOULD verify semantics such as:

```text
ensure account
ensure subscription
repeat ensure
observe
suspend
repeat suspend
resume
cancel
repeat cancel
unknown reference
invalid input
tenant isolation boundary
timeout behaviour
unknown outcome recovery
```

Provider-specific tests SHALL supplement, not replace, the common contract suite.

---

# 72. Kill Bill integration tests

Kill Bill-specific integration testing SHALL use a pinned supported Kill Bill deployment.

Tests SHOULD cover:

- account provisioning;
- subscription provisioning;
- duplicate provisioning attempt;
- catalog mapping;
- usage recording where supported;
- suspension;
- resumption;
- cancellation;
- delayed cancellation where applicable;
- provider lookup;
- reconciliation;
- provider restart;
- network interruption;
- request timeout;
- ambiguous result;
- authentication failure;
- unsupported configuration.

---

# 73. Upgrade testing

Before upgrading Kill Bill, Baobab SHALL test:

1. schema migration;
2. API compatibility;
3. adapter contract tests;
4. representative subscription lifecycle;
5. catalog compatibility;
6. reconciliation;
7. rollback/recovery procedure;
8. backup restoration.

A provider upgrade SHALL not be treated as an ordinary stateless container image change.

---

# 74. Backup and recovery

Kill Bill's persistence SHALL be backed up according to provider-specific recovery requirements.

Baobab's own subscription database SHALL be backed up independently.

Recovery SHALL recognise that the two stores may be restored to different logical times.

Therefore after recovery:

```text
restore
   │
   ▼
reconcile Baobab projection
   │
   ▼
reconcile provider state
   │
   ▼
resolve drift
```

is mandatory before assuming complete consistency.

---

# 75. Provider data loss

If Kill Bill loses provider state while Baobab retains authoritative billing projections, Baobab SHALL NOT simply assume the provider is correct because it is empty.

Reconciliation SHALL identify missing provider resources and determine whether they can be safely reconstructed.

Likewise, if Baobab mappings are lost but Kill Bill resources survive, deterministic recovery SHALL be attempted before creating replacements.

---

# 76. Disaster recovery principle

Neither database backup alone constitutes complete distributed billing recovery.

Recovery requires:

```text
Baobab projection
       +
provider state
       +
mapping/reconciliation
       =
restored operational billing
```

---

# 77. Security boundary

The KillBillAdapter is a privileged infrastructure component.

Only the subscriptions application/provider integration layer SHALL possess credentials required to invoke Kill Bill.

Other Baobab engines SHALL not receive Kill Bill credentials merely because they consume subscription data.

This limits blast radius.

---

# 78. Controlled provider mutation

Provider mutations SHALL originate from validated domain operations.

External callers SHALL not be allowed to submit arbitrary Kill Bill commands through the subscriptions API.

Prohibited:

```text
POST /provider/killbill/raw

{
   "method": "...",
   "payload": "..."
}
```

Provider escape-hatch APIs undermine the anti-corruption boundary and SHALL not be exposed as normal application interfaces.

---

# 79. Administrative operations

Where provider-specific administrative intervention is unavoidable, it SHALL be:

- privileged;
- auditable;
- operationally separated from normal tenant APIs;
- narrowly scoped;
- reconciliation-aware.

Direct provider administration SHOULD be considered exceptional operational work, not normal Baobab workflow.

---

# 80. Domain invariants

The following invariants SHALL hold.

### INV-PROV-01

Kill Bill is an implementation provider, not Baobab subscription authority.

### INV-PROV-02

All Kill Bill access occurs through the provider boundary.

### INV-PROV-03

Kill Bill types do not become canonical Baobab contracts.

### INV-PROV-04

Provider IDs never replace Baobab IDs.

### INV-PROV-05

Provider mappings are owned by `baobab-subscriptions`.

### INV-PROV-06

Provider operations are idempotent or made safely repeatable through Baobab controls.

### INV-PROV-07

Unknown provider outcomes are reconciled before unsafe retry.

### INV-PROV-08

Provider observations cannot modify ProductSubscription authority.

### INV-PROV-09

Provider state cannot determine subscription classification.

### INV-PROV-10

INTERNAL subscriptions do not require monetary provider resources solely for readiness.

### INV-PROV-11

Kill Bill does not bypass `baobab-payments` for Baobab payment orchestration.

### INV-PROV-12

Kill Bill does not replace ERP accounting authority.

### INV-PROV-13

Other engines do not directly access Kill Bill.

### INV-PROV-14

Subscriptions does not directly access Kill Bill's database.

### INV-PROV-15

Provider-native events are translated before becoming Baobab domain events.

### INV-PROV-16

Provider replacement does not change canonical subscription identity.

### INV-PROV-17

Provider migration cannot create uncontrolled duplicate billing.

### INV-PROV-18

Production provider versions are pinned.

### INV-PROV-19

Provider failure cannot manufacture successful billing state.

### INV-PROV-20

Tenant authority is established from trusted Baobab context, not provider-supplied metadata.

---

# 81. Alternatives considered

## 81.1 Expose Kill Bill directly

**Rejected.**

This would couple Baobab clients and engines to Kill Bill APIs and domain semantics.

---

## 81.2 Use Kill Bill objects as canonical Baobab objects

**Rejected.**

This would make provider replacement extremely expensive and blur authority boundaries.

---

## 81.3 Allow each Baobab engine to integrate with Kill Bill

**Rejected.**

This would duplicate provider logic, credentials, failure handling and mapping semantics.

---

## 81.4 Integrate through Kill Bill database tables

**Rejected.**

This would tightly couple Baobab to Kill Bill's persistence implementation and bypass provider invariants.

---

## 81.5 Use Kill Bill for Baobab payment orchestration

**Rejected.**

`baobab-payments` owns payment execution and routing.

---

## 81.6 Use Kill Bill as the accounting ledger

**Rejected.**

`baobab-erp` owns accounting.

---

## 81.7 Build provider-specific logic directly into domain services

**Rejected.**

Provider-specific branching throughout business logic would defeat portability and complicate testing.

---

## 81.8 Model only the lowest common provider feature set

**Rejected.**

Portability does not require weakening Baobab's domain.

Baobab defines the required semantics; adapters declare whether providers can satisfy them.

---

## 81.9 Blindly retry failed provider operations

**Rejected.**

Ambiguous outcomes can produce duplicate provider resources and financial consequences.

---

# 82. Consequences

## Positive

- Kill Bill remains replaceable.
- Canonical Baobab contracts remain provider-neutral.
- Provider failure handling is centralised.
- Provider credentials have a smaller blast radius.
- Duplicate resource creation can be controlled.
- Provider migration becomes feasible.
- Kill Bill upgrades are isolated behind adapter tests.
- Other Baobab engines remain independent of billing-provider technology.
- INTERNAL subscriptions do not require artificial provider resources.
- Payment and accounting boundaries remain intact.

## Negative

- An adapter and mapping layer must be maintained.
- Some Kill Bill features require deliberate translation.
- Reconciliation infrastructure becomes mandatory.
- Provider migration requires explicit tooling.
- Provider capability differences require modelling.
- Provider upgrades require compatibility testing rather than simple image replacement.

These costs are accepted because provider coupling inside a multi-engine platform would create substantially greater long-term operational and architectural cost.

---

# 83. Implementation requirements

Implementation conforming to this ADR SHALL:

1. define a Baobab-owned `BillingProvider` port;
2. implement Kill Bill through `KillBillAdapter`;
3. keep Kill Bill-specific code outside core domain logic;
4. persist explicit provider references;
5. provide idempotent ensure semantics;
6. represent ambiguous outcomes;
7. provide provider observation for reconciliation;
8. classify provider failures into Baobab-owned semantics;
9. implement bounded retry and timeout policies;
10. prevent blind retries after unknown outcomes;
11. prevent direct Kill Bill access by other engines;
12. prevent direct Kill Bill database integration;
13. keep INTERNAL provider-free where policy permits;
14. route monetary execution through `baobab-payments`;
15. preserve ERP accounting independence;
16. expose provider health/readiness;
17. support provider contract testing;
18. pin the production Kill Bill version;
19. verify Kill Bill Java/PostgreSQL compatibility before production deployment;
20. support deterministic provider migration and reconciliation.

---

# 84. Recommended implementation structure

The exact language/package layout may evolve, but the architecture SHOULD resemble:

```text
baobab-subscriptions
│
├── domain/
│   ├── billing_subscription
│   ├── billing_account
│   ├── billing_policy
│   └── provider
│
├── application/
│   ├── provisioning
│   ├── lifecycle
│   └── reconciliation
│
├── ports/
│   └── BillingProvider
│
└── adapters/
    └── billing/
        ├── temporary/
        │   └── TemporaryProvider
        │
        └── killbill/
            ├── KillBillAdapter
            ├── client
            ├── mapping
            ├── catalog
            ├── usage
            ├── errors
            └── configuration
```

The exact directory names are non-normative.

The dependency direction is normative:

```text
Domain
  │
  ▼
Port
  ▲
  │
Adapter
```

The domain SHALL NOT depend on the adapter.

---

# 85. Dependency rule

The following dependency is valid:

```text
KillBillAdapter
      │
      ▼
BillingProvider
```

The following is invalid:

```text
BillingProvider
      │
      ▼
KillBillAdapter
```

Likewise:

```text
Domain
   │
   ▼
Kill Bill SDK
```

is prohibited.

---

# 86. Implementation gate

Kill Bill integration SHALL NOT be declared production-ready until all of the following are satisfied:

| Gate | Requirement |
|---|---|
| Version | Exact Kill Bill release pinned |
| Runtime | Supported Java version verified |
| Database | Supported PostgreSQL version verified |
| Schema | Migration/bootstrap process verified |
| Adapter | BillingProvider contract implemented |
| Identity | Provider mappings durable |
| Idempotency | Duplicate provisioning tested |
| Unknown outcomes | Recovery tested |
| Lifecycle | Suspend/resume/cancel tested |
| Usage | Applicable usage path tested |
| Reconciliation | Drift detection operational |
| Security | Credentials and network access hardened |
| Observability | Metrics/logs/traces operational |
| Backup | Backup and restore tested |
| DR | Recovery plus reconciliation tested |
| Payments | No bypass of `baobab-payments` |
| Accounting | No replacement of `baobab-erp` authority |
| Contracts | No Kill Bill leakage into Shared |
| CI | Provider contract/integration tests enforced |

Until these gates are satisfied, commercial billing readiness SHALL remain explicit rather than assumed.

---

# 87. Relationship to ADR-SUB-0016

This ADR establishes the provider integration boundary.

ADR-SUB-0016 SHALL govern the security around that boundary, including:

- workload identity;
- service-to-service authentication;
- authorisation;
- tenant context;
- secrets;
- privileged operations;
- audit;
- controlled mutation;
- reconciliation authority.

The relationship is:

```text
ADR-SUB-0002
Domain
    │
    ▼
ADR-SUB-0003
Lifecycle
    │
    ▼
ADR-SUB-0006
Billing Policy
    │
    ▼
ADR-SUB-0015
Provider Boundary
    │
    ▼
ADR-SUB-0016
Security and Controlled Mutation
```

---

# 88. Final decision

Baobab SHALL use Kill Bill as a **replaceable billing implementation behind a Baobab-owned BillingProvider port**.

The final architecture is:

```text
                    BAOBAB CONTROL PLANE
                            │
                            │ ProductSubscription
                            ▼
                   BAOBAB SUBSCRIPTIONS
                            │
                     Baobab Domain
                            │
                  BillingSubscriptionProjection
                            │
                            ▼
                     Billing Policy
                            │
                            ▼
                    BillingProvider
                            │
             ┌──────────────┼──────────────┐
             │              │              │
             ▼              ▼              ▼
       Temporary       Kill Bill        Future
       Provider         Adapter         Provider
                            │
                            ▼
                         Kill Bill
```

Kill Bill therefore sits **below**, not inside, the Baobab domain.

The central rule is:

> **Baobab decides what a billing subscription means. The BillingProvider port expresses what the platform needs a provider to do. The Kill Bill adapter translates that intent into Kill Bill semantics. Kill Bill may execute Baobab's billing intent, but it does not define Baobab's products, subscriptions, classifications, tenants, entitlements, payments, accounting, or canonical contracts.**

Provider portability follows from that rule:

> **Replacing Kill Bill may require a new adapter, new provider mappings, migration and reconciliation. It must not require redefining ProductSubscription, BillingSubscriptionProjection, canonical subscription APIs, tenant identity, payment authority, or accounting authority.**

And the financial-safety rule is:

> **When provider state is uncertain, Baobab reconciles before it guesses. When provider behaviour conflicts with authoritative Baobab policy, Baobab policy wins.**