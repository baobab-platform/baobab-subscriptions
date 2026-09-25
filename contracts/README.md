# Contracts

This engine defines no canonical schema of its own. The canonical contracts live in [`baobab-platform/shared`](https://github.com/baobab-platform/shared) and are pinned by commit.

| Shared contract | Role here |
|---|---|
| `contracts/subscriptions/v1` | **Producer**: the Baobab Billing API and `billing-subscription.*` and `usage.recorded` events |
| `contracts/product/v1` (classification, `billing-policy.yaml`) | **Consumer**: the Control Plane's classification and the billing policy per subscription type |
| `contracts/payments/v1` | **Consumer**: payment intents for commercial payment |

Where each contract is enforced in code is documented with the runtime.
