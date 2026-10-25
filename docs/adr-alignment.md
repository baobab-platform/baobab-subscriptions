# ADR alignment status

This page records how far the running service implements ADR-SUB-0001 to ADR-SUB-0018. It is a status record, not a decision: the ADRs govern. It is updated with every PR that changes the status of an ADR.

**Legend:**

- **Implemented:** the ADR's normative requirements that apply today are met, with tests.
- **Partial:** some requirements are met, and the rest are listed.
- **Not started:** no code yet.

| ADR | Subject | Status | What exists | What remains |
|---|---|---|---|---|
| 0001 | Kill Bill behind a Baobab façade | Partial | The Baobab Billing API, the `BillingProvider` port, the `TemporaryProvider` (simulated, refused in production), workload identity, idempotency, tenant isolation | The Kill Bill adapter (see 0015) |
| 0002 | Domain model and authority | Partial | `BillingSubscriptionProjection` (a projection, never authoritative), `UsageRecord`; the Control Plane stays authoritative for ProductSubscription, classification and entitlement | `BillingAccountProjection`, `BillingTerms`, `BillingCycle`, `RatedUsage`, `Charge`, `Credit`, `InvoiceProjection` (0005, 0008, 0009, 0011, 0012) |
| 0003 | Projection lifecycle, sync and reconciliation | Partial | The §5 lifecycle; operational condition separate from lifecycle (§11); revision-aware ensure and commands, so an out-of-order older revision never regresses state (§16); suspend, resume and terminate, with `TERMINATED` final (§9, §10, §35); transactional outbox (§19); readiness as separate facts (§46) | Event-driven intake and the inbox/deduplication store (§14, §20); mandatory scheduled reconciliation and its records (§22-27, §48-50); delayed-effect termination with `TERMINATING` held while finalisation runs (§36); provider retry and unknown-outcome handling (§37-40, which need a real provider) |
| 0004 | Usage metering and rating | Partial | Usage metered for every type, once per source; billable only where the policy charges | Aggregation, rating, usage windows, late-usage handling |
| 0005 | Catalogue, pricing, billing terms | Not started | — | Every requirement. Until this ADR is implemented, priced billing reports `PRICING_CONFIGURATION_MISSING`. |
| 0006 | Classification-driven billing policy | Partial | The policy comes from Shared `billing-policy.yaml` and classification is never inferred (§3, §53-55); INTERNAL is zero charge, metered, and never touches payments (§9-15, §43); honest readiness with the §58 blockers, so the temporary provider never claims commercial readiness (§56-57); classification changes can come only from the Control Plane (§68) | `BillingDecision` records (§36); effective-dated transitions without retrospective billing (§29-34); policy version history (§23) |
| 0007 | Commercial lifecycle, amendments, renewal | Partial | Suspend, resume and terminate as governed, revision-aware commands | Amendments, renewal, effective-dated transitions |
| 0008 | Billing periods and proration | Not started | — | Every requirement |
| 0009 | Charges, adjustments, credits | Not started | — | Every requirement. INTERNAL never produces monetary obligations, and that guarantee already holds. |
| 0010 | Tax | Not started | — | Every requirement |
| 0011 | Billing account and payer | Not started | — | Every requirement. Until this ADR is implemented, priced billing reports `BILLING_ACCOUNT_MISSING`. |
| 0012 | Billing cycles and invoice projection | Not started | — | Every requirement |
| 0013 | Payment obligation handoff | Not started | `PaymentsPort` (not configured); zero-charge policies never consult it | `FinancialObligation`, the handoff to `baobab-payments`, collection state and delinquency |
| 0014 | Events and transactional messaging | Partial | Local ACID transactions with a transactional outbox, canonical versioned envelopes, idempotent handlers, revision checks | The outbox relay, the consumer inbox, dead-letter and quarantine handling, cross-engine reconciliation |
| 0015 | Kill Bill adapter and provider portability | Partial | The provider-neutral `BillingProvider` port; provider state kept apart from projection state | `KillBillAdapter`, with a Kill Bill release and a PostgreSQL version pinned after verification (ADR-SUB-0001 §7) |
| 0016 | Security, workload identity, audit | Partial | Workload-only JWTs with the issuer, audience, allowed-client and per-route-scope checks (§7-10); trusted tenant context and tenant-scoped lookups with cross-tenant not-found (§11-14); a caller cannot manufacture INTERNAL (§17-19); controlled commands (§20-23); idempotency with mismatch refusal (§24-28); revision-aware mutation (§29); append-only audit records in the same transaction (§35-36, §40); problems that carry no secrets (§48); current CP INTERNAL assessments before replay; verified-client founding receipt provenance and owner envelope/payload validation (§31-33) | General subscription lifecycle event intake/authentication beyond the bounded founding inbox (§31-33); audit read access and retention (§41); provider credential isolation (§44, which needs a real provider) |
| 0017 | Observability and billing correctness | Partial | Liveness and readiness endpoints that report commercial readiness honestly; JSON logs with correlation IDs | Metrics with bounded labels, SLOs, pipeline and reconciliation health, alerts |
| 0018 | Availability, backup, DR | Partial | PostgreSQL persistence with versioned migrations; a stateless service; in-memory state refused in staging and production | HA topology, backups and PITR, RPO/RTO, restore drills |

## Kill Bill adapter/worker scaffold — 25 October 2026

ADR-SUB-0015, 0016 and 0017 **remain Partial**. The provider-specific
REST anti-corruption adapter, deterministic external-key read-back,
single-POST transport, nonproduction explicit tenant credential registry,
IAM-validated bounded worker, durable V9 retry/lease and tenant monitoring
are implemented and under CI. The application runtime deliberately supplies
an unavailable CP authoritative commercial decision port and disables
dispatch; local REST fixtures do not qualify financial execution. Real
Kill Bill 0.24.22/PostgreSQL 17, current CP state read, consented
pricing/payer, Payments and ERP evidence are still mandatory.

See [Kill Bill qualification runbook](development/killbill-qualification-and-worker.md).

## Provider custody increment — 25 October 2026

ADR-SUB-0003, 0014, 0015 and 0016 remain **Partial**, but provider
custody now has real transaction-backed operations: PostgreSQL V5/V6 and a
matching in-memory store implement tenant-scoped PREPARED journaling,
lease-epoch fencing, old-revision uncertainty/supersession guards, provider reference uniqueness, a fail-closed UNKNOWN_OUTCOME
and read-only observational reconciliation, with same-transaction audit.
The BillingService prepares genuine-provider priced intent only after its
CP-governed billing projection is persisted. No Kill Bill dispatch, new
external mutation endpoint, automatic scheduler or monetary activation exists.

See [operator and implementation notes](development/provider-operation-reconciliation.md).
A successful test observer is not a qualified Kill Bill deployment; pricing,
payer, payment-path, current identity, provider/DB compatibility, eventual
commercial->INTERNAL cancellation and native billing acceptance remain open.

## P0-04A follow-up — 13 October 2026

Shared `a9c53087` is now embedded with complete inventory/provenance. Current
INTERNAL assessment and all seven founding event payloads/envelopes use exact
owner schemas before existing binding/freshness/durable replay checks. Duplicate
or trailing JSON denies; CP response retention/deadlines are bounded. The
independent 144-vector matrix and isolated packaged JAR/image probes protect
runtime byte/format compatibility. See [audit/maintenance notes](plans/P0-04A-subscriptions-contract-convergence.md).

These controls remain Partial under ADR-SUB-0016. They admit no new workload,
create no incorporation/mandate evidence and implement no chargeable invoice,
Kill Bill or Payments adapter. The founding inbox is not the general classified
ProductSubscription lifecycle synchroniser still required under 0003/0014.
