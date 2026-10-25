# Kill Bill adapter and controlled provider worker

**Date:** 2026-10-25
**Governing:** ADR-SUB-0001/0003/0005/0006/0011/0013/0014/0015/0016/0017/0018 and Shared EA v2.3.
**Engineering status:** implementation scaffold / *not financially qualified*.

## Researched provider compatibility

Kill Bill 0.24.22 (released 2026-09-29) is the current verified release target. Official Kill Bill documentation supports MySQL/MariaDB/PostgreSQL, although MySQL receives the primary test focus. This is NOT evidence that Kill Bill 0.24.22 has passed a dedicated PostgreSQL 17 migration, plugin and invoice regression on Baobab's topology. The provider REST contract uses `/1.0/kb/subscriptions`, GET by `externalKey`, POST with `accountId`, `externalKey` and `planName`; a create response can point to a bundle, **not** to a subscription. Use GET read-back and validate identity. Pin the actual Kill Bill image digest, schema version, Java version and plugins before staging.

## Authority and financial execution

- CP owns ProductSubscription, current revision, tenant and classification. Subscriptions owns projection/journal/opaque mappings. Pricing, plan version, billing account/payer/legal entity and payment execution require separately adopted owner contracts. Kill Bill is **not** a payment orchestration authority; ERP remains accounting owner.
- `KillBillAdapter` implements provider-neutral observation and an explicit, typed `ensureAuthorised` single POST. No legacy service `ensureSubscription`, suspend/cancel or usage call may bypass dispatch custody; those calls throw. API credentials must be tenant-specific, stored in mounted files with renewal/rotation outside this repository. Registry mapping is not a proof of lawful billing.
- The external key is `baobab:<bsub_id>` inside independently keyed Kill Bill tenant credentials. A failed GET or 404 after a prior POST **cannot** justify repeating the POST. The provider method is called only after a durable SENT lease (and CP-approved pricing, payer, revision, exact operation and IAM identity).
- The runtime currently has **no accepted CP current-state workload read and attestation port** providing a typed authoritative monetary decision. `ProviderWorkScheduler.CpAuthority.unavailable()` therefore rejects observation and dispatch. The `Application` uses this port and sets `dispatchEnabled=false` without an alternative; **deploying the worker cannot move money**. Injecting a fake CP port in tests is not protected-environment qualification.
- Future activation must implement: independent IAM-authorised CP current read; tenant, legal actor, subscription revision and classification match; finance-owned versioned terms/payer/account/plan mapping; auditable CP decision ID; signed bounded provider dispatch permit; double-blind no-authorisation tests and provider-id collision tests. Do not allow callers to submit their own `AuthorisedTerms` over HTTP.

## Runtime configuration

The default remains `BILLING_PROVIDER=temporary`. Nonproduction can set `BILLING_PROVIDER=killbill` only with `DATABASE_URL` (PostgreSQL 17) and `KILLBILL_TENANTS_FILE`. The registry file is an array of exact CP tenant mappings, each with **six fields only**:

```json
[
  {
    "tenant_id": "tn_synthetic123",
    "base_url": "https://billing-provider.private.example",
    "username_file": "/run/secrets/kb-username",
    "password_file": "/run/secrets/kb-password",
    "api_key_file": "/run/secrets/kb-apikey",
    "api_secret_file": "/run/secrets/kb-apisecret"
  }
]
```

Restrict file permissions; no credentials or identifiable customer data in repo. Production refuses `killbill` until connected certification. `KILLBILL_WORKER_ENABLED=true` requires `KILLBILL_WORKER_CLIENT_ID`, `KILLBILL_WORKER_IAM_TOKEN_FILE`, `KILLBILL_WORKER_TENANTS` (explicit tenant set) and a JWT with scope `billing:observe`. The worker uses 60-second fixed-delay ticks, up to 25 tenant-scoped observations and 10 prepared dispatch candidates per cycle; **the current gate denies all provider IO and will not consume retry budget while CP authority is unavailable**.

The V9 `provider_reconciliation_retry` table persists retry attempts, cooldown, jitter and exhaustion per tenant/operation, capped at 8. Unknown outcomes are observation-only. No automatic POST retries, irrespective of HTTP classification. Monitoring counts are tenant-bound and must be exposed only through the authorised operator plane; the worker emits count-only logs, no credentials or native IDs.

## Release/acceptance gates (all still separate)

1. Pinned Kill Bill 0.24.22 image, patches, Java/toolchain and PostgreSQL 17 database on a disposable topology; checksum/license/dependency/DDL audit.
2. Real version/API/tenant key/plan/account/get-by-external-key testing, certificate trust, credential rotation, distinct CP tenants and negative cross-tenant checks.
3. Verified signed IAM token against actual issuer/JWKS, exact worker client/scope and live CP current state; fail closed on CP outage, revocation and revision drift.
4. Approved versioned pricing, tax, payer and Kill Bill account mapping, payment plugin isolation and Payments-owned money execution. Prove no double billing under timeout and cross-revision races.
5. PostgreSQL crash restart/retry exhaustion/teardown/tenant-isolation tests and production-grade evidence custody, alerts, PITR restore and regional residency assessment.

The **local REST fake test** checks headers, deterministic reference, one POST and read-back semantics. It does not certify Kill Bill 0.24.22 or monetary outcomes. No production READY assertion is permitted from this scaffold.
