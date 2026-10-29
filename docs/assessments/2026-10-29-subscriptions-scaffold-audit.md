# Baobab Subscriptions scaffolding and ADR audit — 2026-10-29

**Inspected main:** `ea93e6866c3bb7406b78243c279b0734de27639e`
**Scope:** ADR-SUB-0001–0018, current repository tree, alignment, migrations V1–V9,
CP current authority and IAM security gating, prior merged PRs #46–52,
Java CI and Shared pinned `ae48e8cf6e4cd47314f36d9d318a33f30d409453`.

## Executive finding

This is a functioning **development billing engine**, not merely a blank
scaffold. Much of the 25 October provider audit is already implemented
via subsequent merged work. The primary risk is mistaking durable provider
circuitry and synthetic conformance for financially qualified operations.
Correctly, the current Application passes `dispatchEnabled=false`; CP
commercial authority is deny-only and the temporary provider is refused
in production. **Do not remove these fences.**

## Current ADR implementation matrix

| ADR | Evidence found on main | Remaining gap |
| --- | --- | --- |
| 0001 | Headless API, temporary provider, Kill Bill adapter present | Live Kill Bill/database version certification and guarded activation |
| 0002 | Revisioned projection and usage types | Billing accounts, cycles, terms and monetary aggregates |
| 0003 | CP current reader, lifecycle receipt inbox, revision fencing, reconciliation seams | Durable application of current CP state, scheduled convergence and delayed effect states |
| 0004 | Source-deduplicated usage intake | Aggregation, rating and late usage |
| 0005 | None of versioned catalogue/terms | Entire catalogue/pricing domain |
| 0006 | Shared policy; INTERNAL zero-charge; missing commercial facts BLOCKED | Monetary decision provenance and effective-dated transition |
| 0007 | Suspend/resume/terminate commands | Amendments, renewals, effective dates |
| 0008 | None of periods/proration | Entire calendar/period engine |
| 0009 | No charge/credit calculation | Entire monetary calculation engine |
| 0010 | No tax engine | Jurisdiction, exemptions and versioned tax provenance |
| 0011 | No payer/account model | Entire financial responsibility authority |
| 0012 | No invoice projection | Cycle/invoice/finalisation |
| 0013 | PaymentsPort not configured; INTERNAL cannot pay | Obligation handoff, collection/settlement projections |
| 0014 | Transactional outbox; authenticated inbox + durable replay | No real outbox publisher, quarantine/replay/transport ACK; gaps in convergence |
| 0015 | KillBillAdapter, provider journal, observation + lease worker | Connected qualified provider and guarded dispatch |
| 0016 | JWT/workload scope, issuer-current-status option, audit, tenant and command controls | Protected authority/credential/read access and acceptance |
| 0017 | Health, JSON logs, provider queue counts | Full outbox metrics, SLIs/SLO/alerts and incident qualification |
| 0018 | PostgreSQL persistence/migrations and stateless app | HA, backups, PITR, restore/failover evidence |

**Documentation defects:** README still says Kill Bill adapter not implemented;
alignment table predates the merged #46–52 work; ADR index is incomplete.
The narrow P0-04A plan covers contract convergence, not the full domain.
See `docs/plans/SUB-00-to-SUB-12-adr-implementation-programme.md`.

## This PR: bounded SUB-00/01 increment

Implement DB-backed outbox custody with atomic tenant-scoped leasing,
fenced acknowledgement, finite retry/backoff, quarantine on poison/exhaustion,
and backlog health summaries. Keep actual event transport and all financial
dispatch disabled. Tests must run against PostgreSQL 17, not just memory.

This is **not SUB-02 completed**: there is no registered publisher workload,
live broker, broker/consumer ACK proof, automatic publication or authorised
replay. Such claims require independent connected evidence.

## Quality gates and release decision

- Java 21 compilation/test, V1–V10 migrations, PostgreSQL 17 tests.
- Complete pinned Shared closure/owner wire vectors, packaged-JAR and
  container/security jobs; no replacement of canonical Shared contracts.
- Review lease SQL, crash/ACK uncertainty, tenant checks and exhaustion.
- All checks on final head green; unresolved review findings block merge.
- **Commercial readiness remains BLOCKED**, and staging/production
  approval/connected evidence gates remain intact.
