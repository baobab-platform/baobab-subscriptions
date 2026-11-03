# SUB-00–SUB-12 — ADR-driven Subscriptions implementation programme

**Established:** 2026-10-29. **Baseline audited:** main `ea93e6866c3bb7406b78243c279b0734de27639e`.
**Governing authority:** ADR-SUB-0001–0018, accepted Shared contracts and EA v2.3/EA-DEV.
**Status:** Engineering programme; not a new ADR and not authorization to move money.

## Architecture lock

Subscriptions owns billing projections, metering, financial calculation and provider custody.
CP owns ProductSubscription, classification, organisation and entitlement authority.
IAM owns workload identity and current revocation status; Payments owns execution and settlement;
ERP owns ledgers; Shared owns cross-repository contracts. A receipt, projection, historical
INTERNAL status, workload JWT or provider capability is not a current monetary mandate.

**Environment rule:** development/integration may run synthetic, controlled provider exercises
with run-owned identities and teardown. Staging/production require independent qualification,
reviewed approvals and evidence. Production still fails closed; no synthetic test can promote
commercial_billing to READY. Existing development-only policies must not be silently widened.

## Baseline — confirmed code versus missing capability

- Implemented foundation: Java 21/JDBC/PostgreSQL 17, authenticated workload HTTP,
  tenant-scoped projections and usage, policy-derived INTERNAL zero-charge,
  revision/idempotency checks, append-only audit/outbox, founding and CP lifecycle inboxes.
- Implemented but NOT provider-certified: Kill Bill REST adapter, V5–V9 provider journal,
  bounded observation worker, current CP subscription reader, current issuer status,
  simulated transport tests. Worker monetary dispatch is unconditionally disabled.
- Not yet delivered: provider financial runtime qualification; actual positive commercial
  payer/terms/mandate decision; complete CP event convergence; versioned pricing;
  billing account/payer; periods/rating/charges/tax/credit/invoices; financial obligations;
  Payments/ERP end-to-end; real transport publisher; SLOs/PITR/restore evidence.
- Document drift: README claims adapter source not implemented; ADR alignment table still
  describes the former pre-PR #46–52 state; ADR index only links #0001. Update as part of SUB-00.

## Gate register

| Gate | Work and ADRs | Demonstrable acceptance | Dependencies |
| --- | --- | --- | --- |
| **SUB-00** | Reconcile ADR implementation inventory, runtime/CI, dependencies, security and docs (0001–0018) | Signed-off audit against immutable main; no historical scaffolding misrepresented as product completion | Shared, CP, IAM current decisions |
| **SUB-01** | Durable outbox publication custody: tenant-scoped claims, worker lease epochs, bounded retry/quarantine, backlog read model (0014,0017,0018) | PostgreSQL 17 crash/retry, stale-lease, cross-tenant, exhaustion and ACK negatives; no provider IO and no unapproved publisher activated | Local only |
| **SUB-02** | Accepted Baobab transport publisher, trusted workload producer, bounded worker, SLO/metrics, alert/runbook and authorised replay (0014,0016,0017) | Real transport ACK before PUBLISHED; duplicate safe after ACK/crash; poison quarantine; replay authorised and audited; producer authentication proven | Shared event transport agreement, Infrastructure, IAM |
| **SUB-03** | CP lifecycle inbox convergence and scheduled current-state reconciliation, gaps and terminal-state fencing (0003,0007,0014) | CP-issued events replay/reorder/unknown-schema negatives; snapshot read resolves gap; never derives authority from an event | CP, IAM, Shared |
| **SUB-04** | INTERNAL positive, revocation, classification/organisation mutation and accepted test provenance (0003,0006,0016) | Real CP and IAM positive/negative in disposable development runtime; before replay recheck; zero charge/payment | CP, IAM, Infrastructure |
| **SUB-05** | BillingAccountProjection, payer, invoice recipient, legal-mandate, billing terms responsibilities (0002,0011) | CP/PlatformAccount references without identity collapse; version/freshness; missing payer always blocks monetary action | Shared, CP, legal evidence |
| **SUB-06** | Versioned catalogue, price/plan changes, effective dates, commercial decision provenance (0005,0006,0007) | Deterministic immutable terms/price version; no guessed pricing; history and revision tests | CP, Shared, SUB-05 |
| **SUB-07** | Kill Bill 0.24.x plus compatible dedicated database runtime, provider migration/reconciliation and authority fence (0001,0015,0016) | Real native account/subscription read-back; unknown outcomes; no duplicate POST, cross-tenant/key/failover negatives; no financial activation absent accepted positive decision | Provider image/digest, IAM, SUB-05/06 |
| **SUB-08** | Usage windows, aggregation, rating, billing anchors/periods, proration, late usage (0004,0008) | Deterministic replay, timezone and boundary vectors, usage corrections; no double rating | SUB-06 |
| **SUB-09** | Charges, adjustments, credit, monetary rounding, tax jurisdiction/exemptions (0009,0010) | Currency-precise calculations, tax provenance, correction/credit invariants and replay | SUB-06/08, regulations/tax authority |
| **SUB-10** | Billing cycles, invoice projection/finalisation, immutable obligation handoff (0012,0013) | Idempotent invoice/obligation, Payments acceptance/denial, ERP posting/reconcile; no direct settlement or ledger writes | SUB-07/09, Payments, ERP |
| **SUB-11** | Operations: metrics/alerts, retries, backup/PITR, disaster recovery, retention, incident playbooks (0017,0018) | Real restore/replay/duplicate-risk drill and SLO evidence; fail-closed after authority outage | Infrastructure, SRE, SUB-02/10 |
| **SUB-12** | End-to-end protected qualification and release decision (all) | Owner CI + independent evidence, authorised tenant/payer, live IAM/CP, provider/Payments/ERP negatives, teardown/retention; no waived failures | All applicable gates |

## Execution sequence and merge discipline

SUB-00 and SUB-01 may be delivered together as a bounded initial PR; SUB-02
must not invent an HTTP broker or event transport convention. Then execute
SUB-03 and SUB-04 for the existing EA-DEV connected-development programme.
SUB-05/06 provide the missing positive commercial decision inputs before
any money-moving SUB-07 activation. SUB-08/09/10 form the financial domain
vertical; SUB-11/12 complete resilience and protected acceptance.

Each gate must update `docs/adr-alignment.md`, map affected Shared/CP/IAM
contract pins, include scoped unit/contract/PostgreSQL integration tests,
document negative cases and operations, and pass final-head required CI,
security/container checks and code review. No CI-only or fixture-only success
is equivalent to native-provider/staging qualification. Merging requires current
final head, resolved findings, applicable ADRs and required checks green.

## SUB-01 architecture boundary

V10 extends the existing immutable transactional outbox with publication
status, attempt counter, due timestamp, worker lease epoch and expiration,
failure classification and quarantine. `PostgresOutboxDeliveryQueue` is
an internal repository-backed port, not a customer API or an event broker.
`OutboxPublisher` accepts an explicit transport that must return a durable
acknowledgement; **Application does not configure or start one**.

Publication uses stable original `event_id`, row-level SKIP LOCKED claims
and epoch/owner/expiry CAS; no network call is performed inside the SQL
transaction. Uncertain transport outcome retains at-least-once replay
semantics, never exactly-once. Exhausted deliveries quarantine with no
automatic release. Backlog observations are tenant-scoped and never label
high-cardinality tenant identifiers. Future authorised replay needs an explicit
ADR-consistent administrative surface and audit custody; do not edit rows by
hand in production. Financial dispatch stays disabled independent of this gate.

## SUB-02 engineering branch — 29 October 2026

Proposed, **not accepted or deployed**: signed HTTPS binding, fresh IAM
publisher gate, bounded/fair worker, V11 72-hour retry limit, current
operator-authority replay seam and append-only replay record. Details and
remaining acceptance conditions are maintained in
[SUB-02 publication runbook](../development/sub-02-publication-and-replay.md).
The Shared FB-04 signed ingress is ERP→CP only and is still Proposed;
the closed CP ingress accepts no Subscriptions events. Neither a real
receiving endpoint nor a registered issuer-active Subscriptions publisher
is certified. Do not enable dispatch, auto-start workers, merge this
claim into general publication readiness or mark SUB-02 complete until
the owning contracts, IAM/CP/Infrastructure work and connected evidence
are satisfied.

## Explicit remaining risks

- The consumed baobab-dev 1.4.4 full image pins Java17 but this engine
  requires Java21. PostgreSQL17/JDBC Codespace drift is corrected in SUB-00;
  the Java21 published-image profile remains tracked in
  [baobab-dev #52](https://github.com/baobab-platform/baobab-dev/issues/52).
- Existing projection commands still have a bounded CP read-to-commit window;
  distributed atomic CP and Subscriptions commits are not claimed.
- Existing provider synthetic tests are not live Kill Bill 0.24.x/PostgreSQL
  compatibility evidence.
- Outbox transport authentication, placement and accepted ACK semantics have
  not been selected; never infer them from this repository-internal queue.
- ADR-SUB-0018 PITR, restore, failover/writer fencing and independent evidence
  remain operational work, not a SQL migration property.
- CapabilityRegistration is not entitlement and commercial activation
  cannot be derived from the presence of a `KillBillAdapter`.
