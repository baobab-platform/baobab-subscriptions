# Contracts

This engine defines no canonical schema of its own. The canonical contracts live in [`baobab-platform/shared`](https://github.com/baobab-platform/shared) and are pinned by commit.

| Shared contract | Role here |
|---|---|
| `contracts/subscriptions/v1` | **Producer**: the Baobab Billing API and `billing-subscription.*` and `usage.recorded` events |
| `contracts/product/v1` (classification, `billing-policy.yaml`) | **Consumer**: the Control Plane's classification and the billing policy per subscription type |
| `contracts/payments/v1` | **Consumer**: payment intents for commercial payment |

## Where each contract is enforced

- **Vendored copies.** The files the runtime validates against are vendored byte-for-byte under `src/main/resources/contracts`, at the Shared commit pinned in `/contracts.lock.yaml`. `PinnedContractsTest` fails on any drift, and CI checks Shared out at that commit.
- **Enforcement points:**

| Contract | Enforced by |
|---|---|
| `subscriptions/v1` `EnsureBillingProjectionRequest`, `BillingProjectionCommand`, `RecordUsageRequest` | Every request is validated before use (`BillingService`) |
| `subscriptions/v1` `BillingProjection`, `UsageRecord`, events; `events/v1` envelope | Every scenario's outputs are validated in tests (`BillingScenarios`) |
| `product/v1/billing-policy.yaml` | Loaded at startup (`BillingPolicies`). A missing type, or a policy that switches off an always-on control, stops the service. |
| `errors/v1` problem details | Every error response (`HttpApi`), validated in `HttpApiTest` |
| `authorization/v1` scopes `billing:manage`, `billing:read`, `usage:record` | Per route (`HttpApi`, `WorkloadAuthenticator`) |
| `payments/v1` | Not yet consumed: the payments client is not built |
