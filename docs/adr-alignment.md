# ADR alignment status

This page records how far the running service implements ADR-SUB-0001 to ADR-SUB-0018. It is a status record, not a decision: the ADRs govern. It is updated with every PR that changes the status of an ADR.

**Updated 29 October 2026:** This matrix includes the merged provider/current-authority work through PR #52 and the proposed SUB-01 outbox custody gate. Native provider certification and final-head CI are distinct from synthetic tests. See [the implementation programme](plans/SUB-00-to-SUB-12-adr-implementation-programme.md) and [18-ADR audit](assessments/2026-10-29-subscriptions-scaffold-audit.md).

**Legend:**

- **Implemented:** the ADR's normative requirements that apply today are met, with tests.
- **Partial:** some requirements are met, and the rest are listed.
- **Not started:** no code yet.

| ADR | Subject | Status | What exists | What remains |
|---|---|---|---|---|
| 0001 | Kill Bill behind Baobab façade | Partial | Baobab API, temporary provider, KillBillAdapter, bounded worker and provider operation journal | Live Kill Bill/DB certification, commercial authority and protected dispatch |
| 0002 | Domain model and authority | Partial | Revisioned CP-owned ProductSubscription projection and usage, tenant isolation | Billing accounts, immutable terms, cycles, charges and invoices |
| 0003 | Projection lifecycle, sync and reconciliation | Partial | CP current reader, authenticated lifecycle inbox, revision controls, provider observation | Durable lifecycle convergence, scheduled reconciliation and delayed termination |
| 0004 | Usage metering and rating | Partial | Deduplicated usage intake | Aggregation, rating, windows and late usage |
| 0005 | Catalogue, pricing and billing terms | Not started | No qualified price/term authority | Versioned catalogue and effective-dated commercial terms |
| 0006 | Classification-driven monetary policy | Partial | Shared policy, INTERNAL zero-charge, current CP checks, commercial BLOCKED | Monetary decision provenance, effective-dated transitions |
| 0007 | Lifecycle amendments and renewal | Partial | Governed suspend/resume/terminate | Amendments, renewal and effective dates |
| 0008 | Billing periods and proration | Not started | — | Period/calendar engine |
| 0009 | Charges, adjustments and credits | Not started | — | Monetary calculation and correction |
| 0010 | Tax | Not started | — | Tax jurisdiction, exemptions, provenance |
| 0011 | Billing account and payer | Not started | — | Payer/mandate/invoice recipient authority |
| 0012 | Billing cycles and invoices | Not started | — | Billing cycle/finalisation/invoice projection |
| 0013 | Payment obligation handoff | Not started | PaymentsPort deny-only; INTERNAL cannot pay | Payments/ERP obligations and settlement convergence |
| 0014 | Events and transactional messaging | Partial | Transactional outbox, CP/founding inbox, V10 lease-fenced custody, retry/quarantine and simulated ACK seam | Live authenticated transport, publishing worker, authorised replay, reconciliation |
| 0015 | Kill Bill adapter and portability | Partial | KillBillAdapter, V5–V9 journal, observation-only worker | Native provider certification and authorised dispatch |
| 0016 | Security and audit | Partial | Workload JWT/issuer-current checks, tenant/revision/idempotency fences and audit | Protected credential, authority and read-access qualification |
| 0017 | Observability | Partial | Health, logs, provider counts and SUB-01 outbox health read model | SLIs/SLOs, metrics/alerts, incidents |
| 0018 | Availability and DR | Partial | PostgreSQL persistence, stateless app and V10 crash-recoverable custody | HA, PITR/restore and failover evidence |

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
