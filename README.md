# baobab-subscriptions

> **Status:** architecture accepted ([ADR-SUB-0001](docs/adr/ADR-SUB-0001%20—%20Adopt%20Kill%20Bill.md)). The runnable billing façade and its temporary provider are being built. **The Kill Bill integration is NOT YET IMPLEMENTED.**

The headless subscription billing engine of the Baobab platform. It turns a ProductSubscription the Control Plane has authorised and classified into a billing projection. That covers recurring billing, usage metering and credits, through a Baobab-owned API with Kill Bill as the foundational implementation behind it.

## Role

### This engine owns

- billing projections of Control Plane ProductSubscriptions;
- billing account projections keyed to a PlatformAccount (a reference only, never the PlatformAccount's identity);
- billing cycles, recurring billing, usage metering and rating, and credits;
- billing-provider integration: Kill Bill, behind the `BillingProvider` port.

### It explicitly does not own

| Concern | Owner |
|---|---|
| ProductSubscription, subscription classification (INTERNAL / COMMERCIAL / …) and its provenance, INTERNAL eligibility | `baobab-cp` |
| CapabilityGrants and entitlement | `baobab-cp` |
| Tenant, legal entity, PlatformAccount identity | `baobab-cp` |
| Payment execution, orchestration and routing | `baobab-payments` |
| Accounting, ledger, receivables, revenue recognition | `baobab-erp` |
| Client-application expiry, admission, tenant onboarding | `baobab-cp` |

INTERNAL subscriptions are billed at zero monetary charge. They are still metered, entitled, audited and readiness-controlled, and this engine never calls `baobab-payments` for them.

## Contracts

All cross-repository contracts are canonical in [`baobab-platform/shared`](https://github.com/baobab-platform/shared):

| Contract | Use |
|---|---|
| `contracts/subscriptions/v1` | The Baobab Billing API (billing projection, usage) and its events |
| `contracts/product/v1` | The ProductSubscription classification this engine consumes, and `billing-policy.yaml` |
| `contracts/payments/v1` | The payment API this engine calls for commercial payment |

## Documentation

- [ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine](docs/adr/ADR-SUB-0001%20—%20Adopt%20Kill%20Bill.md)
- [Contracts consumed and published](contracts/README.md)

## Local development

This repository uses the shared `baobab-dev` devcontainer (`full` profile: Java and Maven, with a local PostgreSQL service). See `.baobab/environment.yaml` and `.devcontainer/`.
