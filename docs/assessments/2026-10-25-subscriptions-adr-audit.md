# Baobab Subscriptions — ADR conformance audit and provider scaffold

**Date:** 2026-10-25  
**Base revision:** `725bd4f4554e50542ffc0a62506ae0f6d1ae508f` (includes the verified PR #45 Maven/JDBC security update)  
**Governing decisions:** ADR-SUB-0001–0018; Shared subscription/product/payment contracts and EA v2.3.  
**Scope of this increment:** Provider-port modelling and PostgreSQL provider custody foundation. No financial activation.

## Verified baseline

The service implements authenticated workload-only HTTP APIs, tenant-scoped
billing projections, revision-aware commands, idempotency, INTERNAL current-CP
authority revalidation, current founding-event receipt custody, append-only
audit and a local transactional outbox. Commercial readiness is correctly
BLOCKED with the temporary simulated provider; production rejects that
provider. The base implementation is not a Kill Bill integration.

The repository's own `docs/adr-alignment.md` records ADR-SUB-0001–0018:
many partially implemented, with catalogue/pricing (0005), billing periods
(0008), charge calculation (0009), tax (0010), billing accounts (0011),
invoicing (0012), and financial obligation handoff (0013) not started.
The founding-event inbox is **not** the general ProductSubscription event
consumer described in ADR-SUB-0003/0014.

## Prioritised gaps

| Priority | Governing ADRs | Gap and required next acceptance |
| --- | --- | --- |
| P0 | 0001, 0015 | Implement pinned, tested Kill Bill provider behind Baobab intent/outcome contracts; do not activate until provider/database compatibility and credentials have been independently verified |
| P0 | 0003, 0014, 0016 | Current, authenticated CP subscription lifecycle intake, revision fencing, durable deduplication, bounded reconciliation |
| P0 | 0005, 0006, 0011 | Versioned catalogue/pricing, explicit payer and billing-account binding, monetary decision provenance; no guessed pricing |
| P0 | 0014, 0017 | Outbox relay, ack/retry/quarantine, observable backlog with bounded labels |
| P1 | 0004, 0008–0013 | Rating, usage windows, periods, taxes, charges, invoice projection and idempotent Payments obligation handoff |
| P1 | 0018 | Real backup/PITR/restore and operator evidence in protected environments |

## Scaffold delivered by this increment

- `ProviderOutcome`: provider-neutral and fail-closed, including
  `UNKNOWN_OUTCOME` that requires observation before unsafe retry.
- `ProviderCapabilities`: declares support before monetary operations; not a
  readiness or health assertion.
- `ProviderOperationIntent`: tenant, canonical billing ID, CP revision,
  durable operation ID and request hash with validation.
- V5 PostgreSQL migration: composite tenant/billing subscription FK, unique
  provider reference mappings and fenced operation-custody columns
  (`lease_epoch`, `lease_owner`, `lease_expires_at`), indexed ambiguous
  outcomes for reconciliation.
- Unit tests for invalid authority/revision/digest and ambiguous outcomes.

**Not activated:** The BillingService and existing BillingProvider remain on
the current nonmonetary path. A future bounded change must implement storage
repository methods, enforce every operation transition under a lease-epoch
fence, refresh CP policy/terms, and use the new types before invoking Kill Bill.
The new tables are **not evidence of a working Kill Bill or financial cycle**.

## Toolchain compatibility and security

`baobab-dev` main manifest `config/versions.yaml` and resolved
`config/versions.lock` specify Temurin **Java 17**, Maven **3.9.16** and
PostgreSQL **17** in the `full` development profile. The latest image's
actual installed binaries must additionally be verified in a Codespace
with `java -version; mvn -version; psql --version`.

Subscriptions instead compiles `--release 21`, builds in a Maven/Temurin 26
container and runs in a Temurin 25 JRE image; its
`.baobab/environment.yaml` currently claims strict compatibility while
requiring Java 21. **This is a pre-existing incompatible toolchain contract.**
Downgrading source compatibility to 17 without an API audit and CI matrix would
be unsafe; blindly upgrading this repo to 26 based on an image that declares
17 would also misstate the development contract. Resolve the developer-image
profile/version policy separately with ERP compatibility, then align build
and runtime JDKs to one supported version, verify image digests and run all
tests in the actual published development image.

PostgreSQL engine major version **17** already matches the stated platform
standard; the repository's `pom.xml` PostgreSQL JDBC driver now uses
**42.7.14** via the reviewed PR #45, replacing vulnerable 42.7.13.
Kill Bill's *own* database compatibility must be validated independently.

## Release gates

1. Unit tests and Maven dependency-convergence/security checks green.
2. Run PostgreSQL V1–V5 migrations and tenant-crossing negative cases against
   PostgreSQL 17 (not merely in-memory store tests).
3. Implement and prove an operation journal with crash recovery, lease
   fencing, repeat ensure without double creation and observation of ambiguous
   outcomes, before enabling real money.
4. Validate Kill Bill release + PostgreSQL compatibility with a separately
   pinned database; test account/subscription/usage/reconciliation paths.
5. IAM/CP current authority, pricing, payer, Payments and ERP cross-engine
   tests must pass before a COMMERCIAL projection may be called READY.
