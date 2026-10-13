# Contracts

This engine defines no canonical schema of its own. The canonical contracts live in [`baobab-platform/shared`](https://github.com/baobab-platform/shared) and are pinned by commit.

| Shared contract | Role here |
|---|---|
| `contracts/subscriptions/v1` | **Producer**: the Baobab Billing API and `billing-subscription.*` and `usage.recorded` events |
| `contracts/product/v1` (classification, `billing-policy.yaml`) | **Consumer**: the Control Plane's classification and the billing policy per subscription type |
| `contracts/payments/v1` | **Planned consumer**: no configured payment client or payment API invocation exists yet |

## Where each contract is enforced

- **Vendored copies.** The files the runtime validates against are vendored byte-for-byte under `src/main/resources/contracts`, at the Shared commit pinned in `/contracts.lock.yaml`. `scripts/sync_contracts.py --check` and `PinnedContractsTest` check the complete generated inventory, original bytes and provenance; CI checks Shared out at exact pin `a9c53087`.
- **Enforcement points:**

| Contract | Enforced by |
|---|---|
| `subscriptions/v1` `EnsureBillingProjectionRequest`, `BillingProjectionCommand`, `RecordUsageRequest` | Every request is validated before use (`BillingService`) |
| `subscriptions/v1` `BillingProjection`, `UsageRecord`, events; `events/v1` envelope | Every scenario's outputs are validated in tests (`BillingScenarios`) |
| `product/v1/billing-policy.yaml` | Loaded at startup (`BillingPolicies`). A missing type, or a policy that switches off an always-on control, stops the service. |
| `errors/v1` problem details | Every error response (`HttpApi`), validated in `HttpApiTest` |
| `authorization/v1` scopes `billing:manage`, `billing:read`, `usage:record` | Per route (`HttpApi`, `WorkloadAuthenticator`) |
| `payments/v1` | Not yet consumed: the payments client is not built |

## P0-04A current authority and event boundaries

The lock declares 22 actual files with their complete schema closure and
workload/scope vocabulary. Immutable Git blobs generate SHA-256 provenance under
`src/main/resources/contracts`; regeneration removes stale files before copying.
No canonical schema is invented in this repository. The immutable evidence diff
adds six document classes; it does not alter INTERNAL or producer semantics.

| Actual owner wire | Enforcement |
|---|---|
| `subscriptions/v1/internal-authority.schema.json` | CP client asserts the closed response and its three request-tuple property grammars before exact tenant/subscription/classification/positive lease checks. A valid negative wire still denies. |
| `events/v1/envelope.schema.json` | Founding receiver checks the closed envelope and platform/tenant rules before existing producer/type/subject binding. |
| `admission/v2/founding-lifecycle-events.schema.json` | All seven supported sponsorship/deferral payload definitions are validated before PostgreSQL receipt. A receipt never grants classification or ongoing eligibility. |
| `product/v1/billing-policy.yaml` | Original bytes are hash-checked at startup; existing independent policy invariants remain mandatory. |

The registry uses embedded, hash-checked resources and disables remote fetching;
only declared files can be addressed. Cached validators assert UUID/date-time
formats. Billing commands, CP facts and events use strict duplicate/trailing JSON
checks. Error diagnostics use schema locations without instance values.

The independent owner checker compares 144 synthetic vectors from original Git
blobs with Java. Packaged-JAR checks compare full resource bytes/inventory, Java 21
class targeting, real `lib/` dependencies and resource visibility with an invisible
caller classloader. Docker probes use the compiled package. This is code/wire
convergence; workload admission, invoice legal responsibility and protected
provider/runtime acceptance remain separate. See the [maintenance/audit plan](../docs/plans/P0-04A-subscriptions-contract-convergence.md).
