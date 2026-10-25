# Durable provider operation custody and reconciliation (ADR-SUB-0003/0015)

**Implementation:** billing projections, PostgreSQL V5/V6 provider journal, `ProviderReconciliationService`.  
**Status:** **engineering integration only** — no Kill Bill HTTP transport, no settlement, no production-ready commercial invoices.  
**Base:** ADR-SUB-0001/0002/0003/0006/0014/0015/0016/0018 and Shared EA v2.3.

## Scope and ownership

- CP continues to own ProductSubscription, authoritative revision, classification and eligibility; Subscriptions never grants entitlements.
- Subscriptions owns its provider operation journal and provider reference mapping; neither ID becomes a CP canonical ID.
- Payments executes money; ERP does accounting. Provider observation never declares a settled payment, invoice, accounting post or entitlement.
- The temporary provider remains nonfinancial, with no operation rows. Production still refuses it.
- The engine currently lacks approved billable pricing, BillingAccount/Payer binding, Kill Bill adapter and payments path. **Commercial readiness remains BLOCKED even if a provider observation succeeds.**

## Transaction boundary

1. A current CP-authorised ensure creates/updates a tenant-scoped BillingProjection and a PREPARED provider intent in **one local transaction**, for priced policy on a non-simulated provider only.
2. PREPARED records canonical billing identity, tenant, provider, CP revision, intent kind, operation identity, idempotency key, request SHA-256 and timestamps. All are stored under a PostgreSQL 17 migration with unique operation and provider-mapping constraints.
3. `claimForDispatch` commits `SENT`, worker, positive fencing epoch and expiry **before any external IO**. The current increment makes no external dispatch; a future qualified adapter may consume this lease.
4. A real provider dispatcher must call `recordDispatchOutcome` while the **same lease epoch is current**. A late response is refused, even if the provider may have executed the action.
5. A lost response or crashed SENT worker **must never repeat ensure blindly**. Once its lease expires, a different worker invokes `reconcile` to claim an observational lease, without attempting another mutation.
6. Reconciliation calls only `observeSubscription` outside a database transaction. It never silently creates/cancels a provider resource. No capability, network outage, invalid/null response or missing provider ID retains `UNKNOWN_OUTCOME`.
7. A confirmed observation binds one opaque external reference to one Baobab billing projection, atomically with its journal state and audit; conflicting existing IDs are refused and require an independent operator decision. Only the observed reference and journal may converge — **not** subscription classification or financial readiness.

## Lease and crash model

- A new CP commercial revision may supersede a `PREPARED` predecessor as `SUPERSEDED` (no provider request could yet have been sent). It may also prepare a new intent while an earlier SENT/UNKNOWN outcome exists, **but may not dispatch it** until all earlier uncertainty has been resolved. This fence is scoped to the tenant, provider and canonical billing subscription, not merely a request key.
- Dispatch claim: `PREPARED -> SENT`, increment epoch; once SENT, retrying dispatch is forbidden even after expiry.
- Dispatch outcome: `SENT -> CONVERGED | ALREADY_CONVERGED | RETRYABLE_FAILURE | PERMANENT_FAILURE | CONFLICT | UNKNOWN_OUTCOME` under current owner+epoch and unexpired lease. `RETRYABLE_FAILURE` does **not** automatically permit a new monetary dispatch.
- Recovery: expired SENT or free/expired UNKNOWN_OUTCOME -> leased UNKNOWN_OUTCOME with incremented epoch -> RECONCILED on observed matching ID or free UNKNOWN_OUTCOME on inability to establish the outcome.
- Once RECONCILED, automatic replay/re-dispatch is refused. Any unresolved or conflicting mapping remains an operator case.
- A changed CP revision fences **new dispatch**, but permits **read-only recovery of an earlier dispatched operation** when the current subscription is still priced and belongs to the same tenant/provider. Foreign tenants, missing projections, terminal lifecycle, INTERNAL classification and simulated providers fail closed. Historical recovery cannot authorise new commercial terms. Reconciliation does not trust the historical event as current authority.
- An observation worker whose lease expires during external IO cannot commit its stale result. Another worker may later observe safely using a new epoch.

## Execution and security gates

The service intentionally registers **no public provider-dispatch or reconciliation endpoint** and **no scheduler**. Activating a real dispatch later requires: authenticated IAM-scoped worker, fresh CP current-policy context and distinct financially approved actor; approved provider catalog/version and PostgreSQL compatibility; pricing, billing-account/payer data; secret isolation; bounded HTTP timeouts; outbox/quarantine observability and material audit review. No simulated provider credential can be promoted to staging/production.

Run `./mvnw verify` and `TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/baobab_subscriptions_ci TEST_DATABASE_USER=postgres ./mvnw verify` against an isolated PostgreSQL **17** service. The tests prove simulated/non-monetary observer, tenant denial, single dispatch, stale lease rejection, lost response, recovery, provider mapping and unchanged BLOCKED commercial readiness. Tests are not a real Kill Bill integration certification.

## Follow-up work (not implemented)

- Provider-authorised dispatch worker and independent current-CP/actor/resource proof before operation creation and execution.
- Persistent bounded retry cadence and reconciliation job checkpoints/alerts, dead-letter operator handling, reconciliation of paid provider state against canonical billing policy and safe commercial->INTERNAL cancellation.
- Kill Bill exact release, JVM/PostgreSQL 17 compatibility and plugin pin/negative-provider integration suite.
- Monetary catalogue/terms, account, usage rating, tax/invoice, payment obligation handoff and ERP postings.
- DR/PITR/restore and a protected runtime acceptance record for the exact tenant/provider/market.

**Operating rule:** A source-level green check is not a valid receipt for live provider financial correctness.
