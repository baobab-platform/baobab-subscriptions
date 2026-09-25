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
| 0016 | Security, workload identity, audit | Partial | Workload-only JWTs with the issuer, audience, allowed-client and per-route-scope checks (§7-10); trusted tenant context and tenant-scoped lookups with cross-tenant not-found (§11-14); a caller cannot manufacture INTERNAL (§17-19); controlled commands (§20-23); idempotency with mismatch refusal (§24-28); revision-aware mutation (§29); append-only audit records in the same transaction (§35-36, §40); problems that carry no secrets (§48) | Event authentication for consumed events (§31-33, which need an inbox); audit read access and retention (§41); provider credential isolation (§44, which needs a real provider) |
| 0017 | Observability and billing correctness | Partial | Liveness and readiness endpoints that report commercial readiness honestly; JSON logs with correlation IDs | Metrics with bounded labels, SLOs, pipeline and reconciliation health, alerts |
| 0018 | Availability, backup, DR | Partial | PostgreSQL persistence with versioned migrations; a stateless service; in-memory state refused in staging and production | HA topology, backups and PITR, RPO/RTO, restore drills |
