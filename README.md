# baobab-subscriptions

> **Status:** the Baobab Billing API runs with a **temporary, simulated provider** for development and integration ([ADR-SUB-0001](docs/adr/ADR-SUB-0001%20—%20Adopt%20Kill%20Bill.md)). **The Kill Bill integration is NOT YET IMPLEMENTED.** The temporary provider moves no money and is refused in production. Commercial billing therefore always reports `BLOCKED`, never ready.

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

## Runtime

A Java 21 service on the JDK HTTP server. It uses Jackson, a JSON Schema validator (networknt), Nimbus JOSE+JWT, and PostgreSQL through JDBC and HikariCP.

| Route | Scope | Purpose |
|---|---|---|
| `POST /v1/billing-projections` | `billing:manage` | Ensure the projection of one classified ProductSubscription (`EnsureBillingProjectionRequest`). It is created once and updated in place on reclassification. |
| `GET /v1/tenants/{tenant_id}/billing-projections/{billing_subscription_id}` | `billing:read` | Read a projection |
| `GET /v1/tenants/{tenant_id}/product-subscriptions/{product_subscription_id}/billing-projection` | `billing:read` | Read a subscription's projection |
| `POST /v1/billing-projections/{id}/suspend`, `/resume` and `/terminate` | `billing:manage` | Governed lifecycle commands (`BillingProjectionCommand`, carrying the Control Plane's `authoritative_revision`). `TERMINATED` is final. |
| `POST /v1/billing-projections/{id}/usage` | `usage:record` | Meter usage (`RecordUsageRequest`). A source is metered once. |
| `GET /health/live`, `GET /health/ready` | none | Liveness, and readiness of the store and provider. Readiness reports `commercial_billing: NOT_CONFIGURED` while the provider is simulated. |

**Guarantees:**

- **Authentication.** Callers present Baobab workload tokens: the configured issuer, the audience `baobab-subscriptions`, `actor_type: workload`, an allowed client (by default `baobab-cp-workload`, the Shared workload registry's identity for the Control Plane, separate from its browser and admin clients) and the route's scope. Static secrets are not accepted.
- **Idempotency.** Every mutation needs an `Idempotency-Key`. A repeat with the same body replays the stored response, flagged `Idempotent-Replayed: true`. The same key with a different body is refused. Keys are scoped per tenant.
- **Tenant isolation.** Every read and write is keyed by `tenant_id`. Another tenant's identifiers return 404.
- **Lifecycle (ADR-SUB-0003).**
  - `billing_state` is `PENDING_CONFIGURATION`, `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `TERMINATING` or `TERMINATED`.
  - `operational_condition` is reported separately.
  - Every request and command carries the Control Plane's `authoritative_revision`. An older one is refused (`STALE_AUTHORITATIVE_REVISION`) and never regresses the projection. Different terms at the same revision are refused as `CLASSIFICATION_REVISION_CONFLICT`.
  - A late resume never resurrects a terminated projection.
- **Audit (ADR-SUB-0016 §35-36).** Every material change writes an append-only audit record in the same transaction. The record carries the workload and actor, previous and resulting state, reason, idempotency key, correlation, authoritative revision, policy version and provider reference.
- **Policy.** The engine applies Shared's `billing-policy.yaml` and never infers a classification.
  - **INTERNAL:** zero charge, no billing required, metered, and `ACTIVE`/`READY`. Its usage is not billable, and the payments port is never touched.
  - **COMMERCIAL** (and any priced type) on the temporary provider: `PENDING_CONFIGURATION` and `BLOCKED`. The readiness facts report `billing_configuration_complete`, `provider_ready` and `payment_path_ready` as false, with the blockers `PRICING_CONFIGURATION_MISSING`, `BILLING_ACCOUNT_MISSING`, `BILLING_PROVIDER_NOT_CONFIGURED` and `PAYMENT_PATH_NOT_READY` (ADR-SUB-0006 §58).
- **Contracts.** Requests, responses and events are validated against the Shared contracts vendored under `src/main/resources/contracts` and pinned in `contracts.lock.yaml`. Errors are RFC 9457 problems (`errors/v1`).
- **Events.** `billing-subscription.created`, `.suspended`, `.resumed`, `.terminated` and `usage.recorded` are written as canonical envelopes to the engine's outbox, in the same transaction as the change they describe. Relaying them to the event backbone is **not built yet**.
- **Observability.** Logs are JSON lines carrying the route template, status, duration, correlation ID and client, and never tokens or bodies. `X-Correlation-ID` is honoured or minted, and echoed back.

### Configuration

| Variable | Default | Notes |
|---|---|---|
| `BAOBAB_ENVIRONMENT` | (required) | `development`, `integration`, `staging` or `production`. The temporary provider is refused in `production`. |
| `WORKLOAD_ISSUER`, `WORKLOAD_JWKS_URI` | (required) | The Baobab IAM realm. The JWKS URI must use https outside development. |
| `WORKLOAD_AUDIENCE` | `baobab-subscriptions` | |
| `WORKLOAD_ALLOWED_CLIENTS` | `baobab-cp-workload` | Comma-separated. |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | in-memory | `jdbc:postgresql://…`. Required in staging and production. The schema is migrated at startup. |
| `HTTP_PORT` | `8080` | |
| `SHUTDOWN_GRACE_SECONDS` | `10` | In-flight requests finish on SIGTERM. |

### Build, test and run

```sh
./mvnw verify                                   # unit, HTTP and contract tests
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/billing TEST_DATABASE_USER=postgres ./mvnw verify   # plus PostgreSQL
SHARED_CONTRACTS_DIR=../shared ./mvnw verify    # plus the vendored-contract drift check
docker build -t baobab-subscriptions .
```

The container runs as a non-root user (UID 10001), carries OCI labels, and has a `HEALTHCHECK` on `/health/live`.

### Not built yet

- the Kill Bill adapter;
- the client to `baobab-payments` (the `PaymentsPort` reports "not configured");
- the outbox relay;
- metrics.

## Documentation

- [ADR alignment status](docs/adr-alignment.md): how far the service implements ADR-SUB-0001 to ADR-SUB-0018.

- [ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine](docs/adr/ADR-SUB-0001%20—%20Adopt%20Kill%20Bill.md)
- [Contracts consumed and published](contracts/README.md)

## Local development

This repository uses the shared `baobab-dev` devcontainer (`full` profile: Java and Maven, with a local PostgreSQL service). See `.baobab/environment.yaml` and `.devcontainer/`. The build requires Java 21.
