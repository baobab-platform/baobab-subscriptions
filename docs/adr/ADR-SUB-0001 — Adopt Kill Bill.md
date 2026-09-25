# ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine

**Status:** Accepted
**Date:** 2026-09-25
**Decision Type:** Architecture / Platform Engine
**Repository:** `baobab-platform/baobab-subscriptions`
**Scope:** Baobab Platform
**Engine:** Kill Bill, behind a Baobab-owned billing façade
**Owners:** NABHOLD / Baobab Platform Architecture
**Supersedes:** None
**Related:**
- ADR-BCP-005 (Product, Capability Composition, Subscription, Entitlement);
- ADR-BCP-017 (Organisation Admission, Subscription Classification);
- ADR-BCP-018 (Canonical Organisation and Tenant Relationships, gate ORG-11);
- ADR-BCP-020 (Separation of Duties); ADR-BCP-021 (Controlled Mutation);
- ADR-PAY-0001 (`baobab-payments`, HyperSwitch);
- ADR-SHARED-007 (Capability Contracts); ADR-SHARED-011 (Subscription Classification, Billing and Payment Contracts).

---

## 1. Context

Baobab sells, and gives away, platform subscriptions:

- external organisations pay commercially;
- Nabhold Group Africa and its recognised subsidiaries receive **INTERNAL** subscriptions at zero monetary charge (ADR-BCP-017 §11);
- trials, partner arrangements, manual subscriptions and migrations are also possible (ADR-BCP-005).

Every one of those subscriptions must still be:

- metered;
- entitled through explicit CapabilityGrants;
- audited;
- readiness-controlled;
- isolated.

Recurring billing, usage rating, invoicing, credits and dunning are a mature specialist domain. Baobab should adopt an engine for them rather than rebuild them inside the Control Plane or inside each business engine.

The Control Plane already owns ProductSubscription, subscription classification and INTERNAL eligibility (ADR-BCP-017, ADR-BCP-018). What is missing is the engine that turns an **authorised, already-classified** ProductSubscription into billing.

---

## 2. Decision

`baobab-platform/baobab-subscriptions` SHALL be the **headless subscription billing engine** of the Baobab platform.

- **Kill Bill** is its foundational billing implementation.
- Kill Bill is reached only through a **Baobab-owned billing façade**, whose contracts are canonical in `baobab-platform/shared` (`contracts/subscriptions/v1`).
- Kill Bill objects never become the Baobab contract.

```text
                     baobab-cp
                         │  ProductSubscription + classification (authoritative)
                         ▼
               baobab-subscriptions
                Baobab Billing API  ◄── contracts/subscriptions/v1
                         │
                 BillingProvider port
               ┌─────────┴──────────┐
               ▼                    ▼
       TemporaryProvider      Kill Bill adapter
   (development/integration)  (NOT YET IMPLEMENTED)
                         │
          commercial payment required
                         ▼
                  baobab-payments  (ADR-PAY-0001)
```

---

## 3. Non-negotiable distinctions

```text
CP ProductSubscription            != Kill Bill Subscription
Baobab subscription classification != Kill Bill catalog plan
Baobab billing projection          != Kill Bill account/subscription/bundle
```

The relationship is one-directional:

```text
CP ProductSubscription ──► Billing Projection ──► baobab-subscriptions ──► Kill Bill adapter
```

A billing projection *references* the ProductSubscription and its classification. It never redefines them.

---

## 4. Authority boundaries

| Concern | Authority |
|---|---|
| ProductSubscription, its classification and provenance, INTERNAL eligibility, CapabilityGrants, tenant, legal entity, PlatformAccount identity | `baobab-cp` |
| Billing projection, billing account projection, billing cycle, recurring billing, usage rating, credits, billing-provider integration | `baobab-subscriptions` |
| Payment execution, orchestration, routing, authorisation, capture and refund coordination | `baobab-payments` |
| Accounting, ledger, receivables, revenue recognition, financial posting | `baobab-erp` |

In particular, Kill Bill (and this engine) SHALL NOT:

- mint, change or revoke CapabilityGrants. Entitlement stays ProductSubscription → ProductVersion → CapabilityComposition → CapabilityGrant in the Control Plane;
- determine tenant identity, legal entity or PlatformAccount identity. These are asserted by the Control Plane over workload identity and are never accepted from browsers;
- determine INTERNAL / COMMERCIAL / other classification, or why INTERNAL applies. The Control Plane answers "why is this subscription INTERNAL?" without asking Kill Bill;
- act as the accounting ledger. ERP remains the accounting authority;
- move money. Payments execute it.

---

## 5. Classification-driven billing policy

The engine consumes the Control Plane's classification. It applies the policy Shared publishes (`contracts/product/v1/billing-policy.yaml`) and never infers the classification itself.

| Classification | Monetary charge | Billing required | Usage metering | Payments invoked |
|---|---|---|---|---|
| INTERNAL | zero | no | **yes** | **never** |
| COMMERCIAL | priced | yes | yes | when payment is required |
| others | per Shared policy | per Shared policy | yes | per Shared policy |

`INTERNAL != unmetered` and `INTERNAL != uncontrolled`. Only monetary billing is suppressed.

An INTERNAL projection becomes billing-ready once:

- the classification is valid;
- the projection exists;
- metering is available;
- the engine is ready.

It requires no payment provider, payment method or capture.

A COMMERCIAL projection reports `PENDING_CONFIGURATION`, with a precise readiness blocker such as `PAYMENT_PROVIDER_NOT_CONFIGURED`, until a real billing and payment path exists. It is never reported as financially ready by simulation.

---

## 6. Provider strategy

- `BillingProvider` is an implementation-neutral port: ensure account, ensure subscription, suspend, cancel, record usage, health.
- **TemporaryProvider** is deterministic and exists for development and integration only. It creates projections, never contacts a payment provider, never represents money movement, and is marked `simulated` in every projection it produces.
- **Kill Bill provider:** NOT YET IMPLEMENTED. Nothing in this repository may be named or labelled as a Kill Bill integration until it actually calls Kill Bill.

---

## 7. Upstream verification (recorded 2026-09-25)

- **Releases:** the Kill Bill repository carries both the 0.24.x and 0.25.x lines. The most recent tag observed is `killbill-0.25.5`, and `master` is `0.25.6-SNAPSHOT` (killbill-oss-parent `0.147.30`).
- **Database:** Kill Bill ships a PostgreSQL DDL (`util/.../ddl-postgresql.sql`) alongside its default DDL.
- **Not yet verified:**
  - the officially supported PostgreSQL major versions for the selected release;
  - the Java level (the upstream documentation host was not reachable from the build environment).
- **Consequence:**
  - no Kill Bill runtime, image or database version is pinned by this ADR;
  - the devcontainer's `postgres:16` image does not prove production support;
  - the Kill Bill integration gate SHALL pin an exact Kill Bill release and a PostgreSQL version verified against it. It SHALL NOT use `latest`.

Baobab-owned persistence of the façade (projections, idempotency, usage) uses PostgreSQL 17, the platform standard for Baobab services. It is separate from any Kill Bill schema.

---

## 8. Integration rules

- **Workload identity only.**
  - Control Plane → subscriptions, and subscriptions → payments, use Baobab workload identity with scoped tokens.
  - Static bearer secrets are not a supported production mechanism.
- **Idempotency.** Every mutation (ensure projection, record usage, suspend, cancel) takes an `Idempotency-Key`. A retry never duplicates a projection or a usage record.
- **Tenant isolation.** Every record is scoped by `tenant_id`, and every read and write is checked against the trusted caller context. Another tenant's identifiers resolve to not-found.
- **Events.** Only events with real meaning are published:
  - `billing-subscription.created`, `.suspended` and `.cancelled`;
  - `usage.recorded`.

  They never reuse `product.subscription.*`, which belongs to the Control Plane aggregate.
- **Persistence.** The engine owns its database. It never reads the Control Plane database, and the Control Plane never reads it.
- **No schedulers for Control Plane concerns.** Client-application expiry, admission and tenant onboarding are Control Plane lifecycle concerns and are never implemented here.

---

## 9. Consequences

**Positive.**
- One billing engine serves every commercial model.
- INTERNAL subscriptions stay metered and governed at zero charge.
- Kill Bill can be introduced, upgraded or replaced behind a stable Baobab contract.
- The Control Plane keeps a complete explanation of every classification.

**Negative.**
- Another production engine and a Java runtime to operate.
- Kill Bill's operational footprint once it is integrated.
- A temporary provider that must never be mistaken for production billing. This is mitigated by explicit labelling, `simulated` markers and readiness that refuses to report commercial financial readiness.

---

## 10. Alternatives considered

- **Billing inside the Control Plane:** rejected. It would merge entitlement authority with billing execution.
- **Billing inside each business engine:** rejected. It duplicates rating, invoicing and dunning, and splits usage.
- **Let the billing engine decide INTERNAL eligibility:** rejected. Eligibility is a governed Control Plane decision based on CorporateRelationship and PlatformRelationship evidence (ADR-BCP-018 ORG-11).
- **Expose Kill Bill directly to consumers:** rejected. It would couple every consumer to Kill Bill's model and prevent replacement.

---

## 11. Final decision

`baobab-subscriptions` is Baobab's headless subscription billing engine, with Kill Bill as its foundational implementation behind Baobab contracts.

> **The Control Plane decides which subscription exists, how it is classified and why. The subscription engine decides how that authorised subscription is represented and billed. The payment engine decides how an authorised monetary obligation is executed.**
