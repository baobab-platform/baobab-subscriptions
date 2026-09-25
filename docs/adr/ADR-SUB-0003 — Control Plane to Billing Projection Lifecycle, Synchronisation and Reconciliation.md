# ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Distributed Lifecycle / Integration  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None  

**Related:**

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-018 — Canonical Organisation and Tenant Relationships
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-PAY-0001 — Adopt HyperSwitch as the Headless Baobab Payment Orchestration Engine
- ADR-SHARED-007 — Capability Contracts
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- `shared/contracts/subscriptions/v1`
- `shared/contracts/product/v1`

---

# 1. Context

ADR-SUB-0002 establishes a strict distinction between:

- the authoritative Control Plane `ProductSubscription`; and
- the operational `BillingSubscriptionProjection` owned by `baobab-subscriptions`.

The relationship is intentionally directional:

```text
baobab-cp
ProductSubscription
      │
      │ authorises
      ▼
baobab-subscriptions
BillingSubscriptionProjection
```

The two aggregates have different responsibilities and therefore cannot share a single lifecycle.

A Control Plane subscription can legitimately exist while its billing projection is:

- not yet created;
- waiting for configuration;
- being provisioned;
- operational;
- temporarily degraded;
- suspended;
- being reconciled;
- terminated;
- or unable to communicate with its external billing provider.

Likewise, a billing-provider object may temporarily differ from the desired Baobab billing state because of network failure, provider outage, delayed events, retries, or partial execution.

Baobab is a distributed platform.

The following simplistic model is therefore invalid:

```text
CP state == Subscription state == Kill Bill state
```

The actual model is:

```text
Authoritative          Operational              Provider
business state         billing state            state

Control Plane          Subscriptions            Kill Bill

ProductSubscription -> BillingProjection -----> Provider objects
```

These states are related but independently persisted and independently recoverable.

This ADR defines how those lifecycles interact.

---

# 2. Decision

Baobab SHALL use an **asynchronous, idempotent, eventually consistent projection model** between `baobab-cp` and `baobab-subscriptions`.

The Control Plane SHALL remain authoritative for whether a ProductSubscription exists and its platform lifecycle.

`baobab-subscriptions` SHALL maintain its own billing lifecycle describing whether and how that authoritative subscription has been projected into the billing domain.

Kill Bill SHALL maintain provider implementation state behind the `BillingProvider` abstraction.

The architecture SHALL therefore distinguish three state machines:

```text
┌─────────────────────────┐
│ Control Plane           │
│ ProductSubscription     │
│ Lifecycle               │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ Subscriptions           │
│ Billing Projection      │
│ Lifecycle               │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ Billing Provider        │
│ Kill Bill State         │
└─────────────────────────┘
```

No state machine SHALL be treated as a database replica of another.

---

# 3. Authority model

The lifecycle authority SHALL remain:

| Question | Authority |
|---|---|
| Does the ProductSubscription exist? | `baobab-cp` |
| Which tenant/account owns or uses it? | `baobab-cp` |
| What product/version is subscribed to? | `baobab-cp` |
| What is its classification? | `baobab-cp` |
| Is INTERNAL classification permitted? | `baobab-cp` |
| What capabilities are granted? | `baobab-cp` |
| Is a billing projection required? | Derived from authoritative subscription + canonical policy |
| Has the billing projection been established? | `baobab-subscriptions` |
| Is billing configuration complete? | `baobab-subscriptions` |
| What billing cycle applies? | `baobab-subscriptions` |
| Is provider provisioning complete? | `baobab-subscriptions` |
| What provider object represents it? | `baobab-subscriptions` |
| Did Kill Bill accept the requested provider operation? | Kill Bill, observed through subscriptions |
| Should payment be attempted? | Billing policy + `baobab-subscriptions` |
| Was payment executed? | `baobab-payments` |
| Should capability access change? | `baobab-cp` |
| What is the accounting consequence? | `baobab-erp` |

---

# 4. ProductSubscription lifecycle is not the billing lifecycle

The platform SHALL NOT require the billing state machine to mirror the Control Plane state machine.

For example:

```text
Control Plane:

PENDING
   │
   ▼
ACTIVE
   │
   ├────────► SUSPENDED
   │              │
   │              ▼
   │            ACTIVE
   │
   ▼
TERMINATED
```

The exact authoritative Control Plane vocabulary remains governed by Control Plane contracts and ADRs.

Subscriptions SHALL consume those semantics rather than redefine them.

The billing projection requires a separate lifecycle.

---

# 5. Billing projection lifecycle

The canonical billing projection lifecycle SHALL support, at minimum, the following semantic states:

```text
                 ┌───────────────────────┐
                 │ PENDING_CONFIGURATION │
                 └───────────┬───────────┘
                             │
                             ▼
                    ┌────────────────┐
                    │ PROVISIONING   │
                    └───────┬────────┘
                            │
                            ▼
                       ┌─────────┐
                 ┌────►│ ACTIVE  │◄─────┐
                 │     └────┬────┘      │
                 │          │           │
                 │          ▼           │
                 │    ┌───────────┐     │
                 └────│ SUSPENDED │─────┘
                      └─────┬─────┘
                            │
                            ▼
                    ┌────────────────┐
                    │ TERMINATING    │
                    └───────┬────────┘
                            │
                            ▼
                     ┌────────────┐
                     │ TERMINATED │
                     └────────────┘
```

Additional operational conditions such as provider degradation, reconciliation requirement or retry exhaustion SHOULD be represented separately from the core business lifecycle where possible.

The billing state SHALL not become a dumping ground for infrastructure conditions.

---

# 6. PENDING_CONFIGURATION

`PENDING_CONFIGURATION` means:

> A valid upstream ProductSubscription exists, but the billing engine cannot yet establish a complete operational billing projection.

Examples include:

- commercial pricing is not configured;
- required billing currency is missing;
- provider configuration is incomplete;
- supported plan mapping does not exist;
- required billing-account information is unavailable;
- payment integration required by policy is unavailable;
- provider integration has not yet been implemented.

This state is especially important during incremental Baobab deployment.

The platform SHALL report the blocker precisely.

It SHALL NOT report an unimplemented commercial billing path as operational.

For example:

```text
billing_status = PENDING_CONFIGURATION

blocker:
    code = BILLING_PROVIDER_NOT_CONFIGURED
```

is valid.

Pretending that the subscription is fully billable is not.

---

# 7. PROVISIONING

`PROVISIONING` means that sufficient configuration exists and `baobab-subscriptions` is attempting to establish the billing projection and, where required, provider resources.

Typical work may include:

```text
ensure billing account
       │
       ▼
ensure provider account
       │
       ▼
ensure billing subscription
       │
       ▼
persist provider mappings
       │
       ▼
verify expected provider state
```

All provisioning operations SHALL be idempotent.

A retry SHALL converge on the same intended state rather than create duplicate provider resources.

---

# 8. ACTIVE

`ACTIVE` means that the billing projection is operational according to the applicable billing policy.

For COMMERCIAL subscriptions this generally means:

- required billing configuration is valid;
- billing terms are resolvable;
- billing cycle is established;
- required provider resources exist;
- provider mapping is known;
- required billing operations can proceed.

For INTERNAL subscriptions, ACTIVE SHALL NOT require monetary provider provisioning.

An INTERNAL projection can therefore be:

```text
classification = INTERNAL
billing_status = ACTIVE
monetary_charge = ZERO
metering = ENABLED
payment_required = FALSE
```

No artificial Kill Bill subscription or payment transaction is required merely to make an INTERNAL subscription appear operational.

---

# 9. SUSPENDED

`SUSPENDED` means billing activity has been deliberately suspended according to an authorised lifecycle instruction or billing policy.

Suspension SHALL preserve sufficient state for deterministic resumption or termination.

Suspension does not itself revoke platform entitlement.

Therefore:

```text
BillingProjection SUSPENDED
          │
          X
          │
          ▼
CapabilityGrant revoked
```

is prohibited as an implicit local side effect.

Instead:

```text
Billing state/fact
       │
       ▼
canonical signal
       │
       ▼
Control Plane
       │
       ▼
governed entitlement decision
```

---

# 10. TERMINATING and TERMINATED

Termination SHALL be modelled as a process rather than assuming that all required distributed work completes atomically.

```text
authoritative termination instruction
            │
            ▼
       TERMINATING
            │
            ├── stop future billing
            ├── finalize eligible usage
            ├── resolve final billing
            ├── update provider
            ├── preserve audit trail
            └── reconcile
                    │
                    ▼
               TERMINATED
```

`TERMINATED` SHALL be terminal for that billing projection unless a future ADR explicitly defines reactivation semantics.

A newly authorised upstream subscription SHOULD result in a new projection lifecycle rather than silently resurrecting terminated financial history.

---

# 11. Operational condition is separate from business lifecycle

Infrastructure conditions SHALL, where practical, be represented separately.

For example:

```text
business_status = ACTIVE
operational_status = DEGRADED
```

rather than:

```text
business_status = PROVIDER_TIMEOUT
```

Recommended operational conditions include:

```text
HEALTHY
DEGRADED
RECONCILIATION_REQUIRED
RETRYING
BLOCKED
```

This permits situations such as:

```text
Billing projection:
    ACTIVE

Provider:
    temporarily unreachable

Operational condition:
    DEGRADED
```

without falsely changing the business lifecycle.

---

# 12. Desired state and observed state

Provider integration SHALL distinguish:

- **desired state** — what Baobab intends;
- **observed state** — what the provider is known to contain.

Example:

```text
Baobab desired state:
    ACTIVE

Last observed Kill Bill state:
    SUSPENDED
```

This discrepancy is not resolved by overwriting either fact.

It creates reconciliation work.

Conceptually:

```text
Desired State
     │
     ├──── compare ──── Observed Provider State
     │                        │
     └──────────┬─────────────┘
                ▼
              Drift?
             /     \
           no       yes
           │         │
           ▼         ▼
        healthy   reconcile
```

---

# 13. Projection creation

A billing projection SHALL be created only from an authoritative and sufficiently validated ProductSubscription.

The preferred flow is:

```text
baobab-cp
    │
    │ ProductSubscription lifecycle event
    ▼
canonical event transport
    │
    ▼
baobab-subscriptions
    │
    ├── authenticate producer
    ├── validate schema
    ├── validate tenant context
    ├── deduplicate event
    ├── inspect authoritative references
    ├── resolve billing policy
    └── create/update projection
```

The event is a trigger for projection.

It does not transfer authority over ProductSubscription.

---

# 14. Event-driven propagation

Lifecycle propagation SHOULD be event-driven.

Canonical Control Plane events SHALL carry sufficient references to allow the billing engine to determine which authoritative subscription changed.

Where additional authoritative information is required, `baobab-subscriptions` MAY retrieve it through an authorised Control Plane API.

It SHALL NOT query the Control Plane database.

---

# 15. Event is notification, not unrestricted authority

Receiving an event does not permit the consumer to blindly trust arbitrary fields as permanent authority.

The subscription engine SHALL validate:

- producer identity;
- schema version;
- tenant context;
- event identity;
- authoritative object reference;
- transition validity.

Where correctness requires authoritative refresh, the engine SHOULD resolve current state through the Control Plane API.

This protects against stale, duplicated or reordered events.

---

# 16. Event ordering

Baobab SHALL NOT assume globally ordered event delivery.

For example, a consumer may observe:

```text
Event 103: subscription suspended
Event 101: subscription activated
Event 102: subscription updated
```

The projection model SHALL therefore use appropriate version, revision or authoritative-state checks.

Older events SHALL NOT regress a newer projection.

Conceptually:

```text
incoming_revision <= applied_revision
              │
              ▼
      do not regress state
```

The precise revision contract SHALL live in Shared.

---

# 17. Duplicate delivery

At-least-once delivery SHALL be assumed.

Therefore:

```text
event E42
   │
   ├──── delivery 1
   ├──── delivery 2
   └──── delivery 3
```

must result in:

```text
one business effect
```

The consumer SHALL persist enough information to identify previously applied lifecycle mutations.

Duplicate delivery SHALL be safe.

---

# 18. No distributed transaction

Baobab SHALL NOT attempt to create a distributed ACID transaction spanning:

- `baobab-cp`;
- `baobab-subscriptions`;
- Kill Bill;
- `baobab-payments`;
- `baobab-erp`.

The architecture accepts distributed state transitions.

Correctness SHALL instead be achieved through:

- durable local transactions;
- idempotency;
- canonical events;
- outbox/inbox patterns where applicable;
- deterministic retry;
- reconciliation;
- audit trails.

---

# 19. Transactional outbox

Where `baobab-subscriptions` mutates durable state and publishes a corresponding event, the mutation and event intent SHOULD be committed atomically to local persistence.

Conceptually:

```text
BEGIN LOCAL TRANSACTION

update billing_projection

insert outbox_event

COMMIT
```

A separate publisher then delivers:

```text
outbox
   │
   ▼
event transport
```

This prevents the dangerous condition:

```text
database committed
event lost forever
```

---

# 20. Inbox/deduplication

Incoming lifecycle events SHOULD use durable deduplication where their processing changes financial or billing state.

Conceptually:

```text
incoming event
      │
      ▼
event_id already processed?
      │
   ┌──┴──┐
  yes    no
   │      │
   ▼      ▼
return   process
prior    transactionally
result
```

A process restart SHALL NOT erase duplicate protection for financially meaningful operations.

---

# 21. Projection synchronization

Synchronization SHALL be convergence-oriented rather than replication-oriented.

The goal is:

> Given authoritative Control Plane state and applicable billing policy, the billing engine should deterministically converge on the correct billing projection.

Formally:

```text
desired_billing_state =
    f(
      authoritative_product_subscription,
      classification,
      billing_policy,
      billing_configuration
    )
```

Provider state is then reconciled toward that desired billing state.

```text
desired_provider_state =
    g(
      billing_projection,
      provider_policy
    )
```

---

# 22. Reconciliation is mandatory

Events alone SHALL NOT be treated as sufficient for financial correctness.

`baobab-subscriptions` SHALL support reconciliation capable of detecting divergence among:

1. authoritative Control Plane state;
2. local billing projection;
3. provider state.

Conceptually:

```text
          Control Plane
              │
              ▼
       authoritative state
              │
              │
              ▼
     ┌───────────────────┐
     │  Reconciliation   │
     └──────┬─────┬──────┘
            │     │
            │     └────────► Kill Bill
            │                observed state
            ▼
       Local billing
        projection
```

---

# 23. Reconciliation classes

Reconciliation SHALL detect at least:

| Condition | Meaning |
|---|---|
| Missing projection | CP subscription exists but expected billing projection does not |
| Orphan projection | Billing projection references an upstream subscription no longer valid/known |
| Stale projection | Projection is behind authoritative revision |
| Classification drift | Local classification projection differs from authoritative classification |
| Policy drift | Projection uses an obsolete billing-policy version where migration is required |
| Missing provider mapping | Provider resource expected but mapping absent |
| Missing provider resource | Mapping exists but provider resource cannot be found |
| Provider-state drift | Provider state differs from desired state |
| Duplicate provider resource | More than one provider resource represents one intended Baobab resource |
| Incomplete termination | Upstream termination occurred but billing cleanup remains incomplete |
| Unresolved provisioning | Projection remains provisioning beyond policy threshold |

---

# 24. Reconciliation outcome

A reconciliation result SHALL distinguish:

```text
IN_SYNC
REPAIRABLE
MANUAL_REVIEW_REQUIRED
BLOCKED
```

or equivalent canonical semantics.

Not every discrepancy should be automatically repaired.

---

# 25. Safe automatic repair

Automatic repair MAY occur when:

- authoritative state is unambiguous;
- repair is idempotent;
- no destructive financial reinterpretation is required;
- tenant authority is established;
- provider operation is safe to repeat.

Examples:

```text
missing local provider mapping
but provider object deterministically identifiable
```

or:

```text
local projection one revision behind
and authoritative update is unambiguous
```

---

# 26. Manual review

Automatic repair SHALL NOT be used when doing so could change financial meaning without sufficient evidence.

Examples include:

- ambiguous duplicate provider subscriptions;
- unexplained monetary discrepancy;
- conflicting historical pricing versions;
- unknown payer identity;
- classification provenance conflict;
- potential cross-tenant mapping;
- irreversible provider mutation whose previous outcome cannot be established.

Such conditions SHALL be surfaced for operator review.

---

# 27. Reconciliation must not create upstream authority

Reconciliation SHALL NOT repair a missing Control Plane subscription by creating one from billing state.

Prohibited:

```text
Kill Bill subscription exists
       │
       ▼
billing projection exists
       │
       ▼
create CP ProductSubscription
```

Provider state cannot bootstrap platform authority.

The correct response is to classify the resource as potentially orphaned and escalate or clean it up according to governed policy.

---

# 28. INTERNAL lifecycle

INTERNAL subscriptions require a billing projection where metering, auditing or billing-readiness visibility is required, but they SHALL not generate monetary obligations.

Example:

```text
CP ProductSubscription
classification = INTERNAL
        │
        ▼
BillingSubscriptionProjection
        │
        ├── billing status = ACTIVE
        ├── metering = ACTIVE
        ├── audit = ACTIVE
        ├── monetary charge = ZERO
        └── payment invocation = NEVER
```

The absence of Kill Bill resources MAY therefore be correct for INTERNAL subscriptions.

Reconciliation SHALL understand this policy and SHALL NOT incorrectly create commercial provider resources.

---

# 29. COMMERCIAL lifecycle

COMMERCIAL subscriptions require complete monetary billing configuration before being reported as fully billing-operational.

Example:

```text
CP COMMERCIAL subscription
          │
          ▼
resolve policy
          │
          ▼
pricing available?
     ┌────┴────┐
     │         │
    no        yes
     │         │
     ▼         ▼
PENDING_     resolve billing
CONFIGURATION account/provider
               │
               ▼
           PROVISIONING
               │
               ▼
             ACTIVE
```

If Kill Bill integration required by policy has not yet been implemented, the projection SHALL remain explicitly blocked or pending.

A temporary provider MUST NOT be misrepresented as production commercial readiness.

---

# 30. Classification change

A classification change is financially significant.

For example:

```text
INTERNAL
   │
   ▼
COMMERCIAL
```

cannot be treated as a simple metadata update.

It may require:

- pricing resolution;
- billing account validation;
- provider provisioning;
- billing anchor determination;
- payment readiness;
- audit recording.

Likewise:

```text
COMMERCIAL
   │
   ▼
INTERNAL
```

may require cessation of future monetary billing while retaining usage and audit history.

Classification transitions SHALL therefore be processed as controlled lifecycle operations.

The detailed billing-policy semantics are defined by ADR-SUB-0006.

---

# 31. Classification authority remains upstream

`baobab-subscriptions` SHALL NOT initiate or approve classification changes.

The flow is:

```text
Control Plane
classification decision
       │
       ▼
canonical lifecycle signal
       │
       ▼
Subscriptions
billing consequence
```

Never:

```text
Subscriptions
billing preference
       │
       ▼
change CP classification
```

---

# 32. Subscription modification

ProductSubscription changes may affect billing.

The billing engine SHALL determine whether an authoritative change requires:

- no billing change;
- projection metadata refresh;
- billing-term change;
- provider update;
- billing-cycle change;
- controlled migration;
- termination and replacement.

The engine SHALL NOT assume every ProductSubscription update requires provider mutation.

---

# 33. Version changes

A ProductVersion change SHALL preserve temporal traceability.

Example:

```text
Subscription
     │
     ├── ProductVersion v1
     │       effective until T1
     │
     └── ProductVersion v2
             effective from T1
```

Billing before `T1` must remain explainable using v1 semantics.

Billing after `T1` may use v2 according to applicable policy.

Historical charges SHALL NOT silently be reinterpreted using v2.

---

# 34. Suspension propagation

An authorised Control Plane lifecycle change MAY require billing suspension.

The processing pattern is:

```text
CP authorised state
       │
       ▼
event/API observation
       │
       ▼
local desired state = SUSPENDED
       │
       ▼
persist transition intent
       │
       ▼
BillingProvider.suspend()
       │
       ▼
observe result
       │
       ├── success → converge
       │
       └── failure → retry/reconcile
```

A provider failure does not invalidate the upstream Control Plane decision.

It creates an operational discrepancy to resolve.

---

# 35. Resume propagation

Resumption SHALL likewise be idempotent.

The engine SHALL verify that resumption remains permitted by the current authoritative subscription and billing policy before restoring provider activity.

A delayed resume event SHALL NOT resurrect a subscription that has subsequently been terminated upstream.

---

# 36. Cancellation and termination

Cancellation SHALL distinguish at least:

- request time;
- effective termination time;
- provider execution time;
- completion time.

These timestamps may differ.

For example:

```text
termination requested
        │
        ▼
effective end = month end
        │
        ▼
billing remains valid until effective end
        │
        ▼
finalisation
        │
        ▼
provider termination
        │
        ▼
TERMINATED
```

The precise commercial rules are policy concerns, but the lifecycle SHALL support delayed effectiveness.

---

# 37. Provider failure

Provider unavailability SHALL NOT cause the engine to fabricate success.

Example:

```text
desired = ACTIVE
provider request = timeout
```

The correct state is conceptually:

```text
business status = PROVISIONING or ACTIVE as appropriate
operational status = DEGRADED / RECONCILIATION_REQUIRED
provider outcome = UNKNOWN
```

not:

```text
provider outcome = SUCCESS
```

Unknown is a valid distributed-systems outcome and SHALL be represented honestly.

---

# 38. Unknown provider outcome

A particularly important case occurs when:

1. a request reaches Kill Bill;
2. Kill Bill commits the change;
3. the network fails before Baobab receives the response.

Baobab then cannot safely infer failure.

```text
Baobab ───── create ─────► Kill Bill
                         commits
Baobab ◄──── X response
```

The outcome is:

```text
UNKNOWN
```

The engine SHALL first reconcile using deterministic identifiers/idempotency/provider lookup before attempting any operation capable of creating duplicates.

---

# 39. Retry policy

Retries SHALL be:

- bounded;
- observable;
- idempotent;
- classified by failure type;
- safe for the operation being retried.

The platform SHALL distinguish:

```text
transient failure
permanent validation failure
authorization failure
provider conflict
unknown outcome
```

Blind retry of all failures is prohibited.

---

# 40. Retry exhaustion

When automated retry is exhausted:

```text
operation
   │
   ▼
retry policy
   │
   ▼
exhausted
   │
   ▼
RECONCILIATION_REQUIRED / BLOCKED
   │
   ▼
operator visibility
```

The operation SHALL not disappear into logs without durable operational state.

---

# 41. Idempotency

Every lifecycle mutation SHALL be idempotent.

The same logical request repeated with the same idempotency identity SHALL converge on the same business result.

Examples include:

- create billing projection;
- suspend;
- resume;
- terminate;
- update billing terms;
- provider provisioning.

Idempotency is not merely an HTTP concern.

It is a business invariant.

---

# 42. Correlation and causation

Distributed lifecycle operations SHALL preserve correlation and causation identifiers.

Conceptually:

```text
correlation_id
    │
    ├── CP subscription change
    │
    ├── subscriptions projection update
    │
    ├── provider mutation
    │
    └── resulting event

causation_id
    identifies the immediate predecessor
```

This enables a lifecycle to be reconstructed across engines.

---

# 43. Recommended lifecycle event envelope

Canonical Shared contracts SHOULD support equivalent information to:

```text
event_id
event_type
schema_version

correlation_id
causation_id

tenant_id
platform_account_id
product_subscription_id

aggregate_revision

occurred_at
published_at

producer
payload
```

This is conceptual.

The normative schema remains in `baobab-platform/shared`.

---

# 44. Subscription-originated events

`baobab-subscriptions` SHALL use its own billing event namespace.

Examples established by ADR-SUB-0001 include:

```text
billing-subscription.created
billing-subscription.suspended
billing-subscription.cancelled
usage.recorded
```

It SHALL NOT publish billing lifecycle events under:

```text
product.subscription.*
```

because that namespace implies Control Plane authority.

---

# 45. Projection event semantics

A billing event describes a billing fact.

For example:

```text
billing-subscription.suspended
```

means:

> the billing projection entered its suspended state.

It does not mean:

> the authoritative ProductSubscription has been suspended.

Consumers SHALL respect that distinction.

---

# 46. Readiness

Baobab SHALL distinguish:

```text
subscription authorised
billing projection created
billing configuration complete
provider ready
payment path ready
entitlement active
```

These are separate facts.

A single boolean such as:

```text
ready = true
```

is insufficient to represent the distributed lifecycle.

---

# 47. Readiness example

A valid state may be:

```text
ProductSubscription:
    ACTIVE

classification:
    COMMERCIAL

CapabilityGrant:
    governed by CP

BillingSubscriptionProjection:
    PENDING_CONFIGURATION

billing blocker:
    KILL_BILL_PROVIDER_NOT_CONFIGURED
```

This state is preferable to falsely reporting billing readiness.

---

# 48. Reconciliation cadence

The implementation SHALL support:

1. event-triggered reconciliation;
2. targeted reconciliation after ambiguous provider outcomes;
3. operator-triggered reconciliation;
4. periodic background reconciliation where justified.

Periodic reconciliation SHALL be bounded and tenant-aware.

It SHALL not become an uncontrolled full-platform scan.

---

# 49. Reconciliation scope

A reconciliation operation SHOULD be addressable by:

- billing subscription;
- ProductSubscription;
- PlatformAccount;
- tenant;
- provider reference;
- bounded batch.

Cross-tenant bulk reconciliation SHALL require appropriately privileged workload authority.

---

# 50. Reconciliation record

Material reconciliation activity SHOULD produce durable evidence including:

```text
reconciliation_id
tenant_id
billing_subscription_id

authoritative_revision
local_revision

expected_state
observed_state

provider_state

discrepancies
actions_taken

outcome

started_at
completed_at
```

This record supports operational audit and financial investigation.

---

# 51. Security boundary

Lifecycle synchronization SHALL occur only through authenticated workload identities.

The Control Plane SHALL not authenticate to subscriptions using long-lived static production bearer secrets.

Likewise, subscriptions-to-provider and subscriptions-to-other-Baobab-engine communication SHALL follow platform workload identity standards.

The security mechanics are further specified by ADR-SUB-0016.

---

# 52. Tenant isolation

Every lifecycle mutation SHALL be evaluated inside trusted tenant context.

An identifier alone SHALL NOT establish tenant authority.

Conceptually:

```text
trusted workload identity
          +
trusted tenant context
          +
resource identifier
          │
          ▼
authorised mutation
```

A resource from another tenant SHALL fail closed.

---

# 53. Controlled mutation

External callers SHALL NOT directly force arbitrary billing states.

For example, an API such as:

```text
PATCH /billing-subscription/123

{
  "status": "ACTIVE"
}
```

SHALL NOT bypass lifecycle rules.

Instead callers request or trigger authorised domain operations whose transitions are validated.

Examples:

```text
suspend()
resume()
terminate()
reconcile()
```

where the caller is authorised and the transition is valid.

Detailed mutation controls are governed by ADR-SUB-0016 and ADR-BCP-021.

---

# 54. Observability

Lifecycle synchronization SHALL expose sufficient telemetry to determine:

- event processing latency;
- projection lag;
- provisioning success/failure;
- provider failure rate;
- retry count;
- reconciliation backlog;
- reconciliation failures;
- stale projection count;
- orphan projection count;
- unknown provider outcomes;
- subscriptions blocked by configuration.

Metrics SHALL avoid exposing sensitive tenant or financial information through uncontrolled high-cardinality labels.

---

# 55. Health versus correctness

A healthy process does not imply synchronized billing state.

Therefore:

```text
HTTP /health = 200
```

is not evidence that all subscription projections are correct.

Operational health and domain reconciliation health SHALL be distinguishable.

For example:

```text
service_health = HEALTHY

reconciliation:
    in_sync = 998
    drifted = 2
```

---

# 56. Auditability

Every material lifecycle transition SHALL be attributable to:

- the authoritative cause;
- workload or actor where applicable;
- correlation identifier;
- prior state;
- resulting state;
- timestamp;
- applicable revision;
- provider operation where applicable.

Financially meaningful lifecycle history SHALL not depend solely on ephemeral application logs.

---

# 57. Data retention

Termination SHALL NOT imply immediate deletion of billing history.

Historical projection, usage, charges, provider mappings and reconciliation evidence SHALL be retained according to Baobab's financial, legal, privacy and retention policies.

Deletion and anonymisation rules require separate governance and SHALL not be inferred from lifecycle termination.

---

# 58. Startup recovery

On process restart, `baobab-subscriptions` SHALL recover from durable state.

It SHALL NOT depend on in-memory lifecycle state to determine whether:

- an event was processed;
- provisioning was attempted;
- an operation is pending;
- reconciliation is required;
- a provider outcome is unknown.

Critical workflow state SHALL be durable.

---

# 59. Disaster recovery

After database restoration or regional recovery, the engine SHALL be capable of reconciling restored local state with:

- current Control Plane authority;
- provider state.

Reconciliation is therefore also part of disaster recovery.

A restored database snapshot SHALL not automatically be assumed to represent current distributed truth.

---

# 60. End-to-end lifecycle example

A COMMERCIAL subscription may progress as follows:

```text
CONTROL PLANE

Organisation admitted
       │
       ▼
PlatformAccount established
       │
       ▼
ProductSubscription created
classification = COMMERCIAL
       │
       ▼
canonical event
       │
       │
       ▼

SUBSCRIPTIONS

validate event
       │
       ▼
create BillingSubscriptionProjection
       │
       ▼
PENDING_CONFIGURATION
       │
       ▼
resolve pricing + billing account
       │
       ▼
PROVISIONING
       │
       ▼
BillingProvider.ensureAccount()
       │
       ▼
BillingProvider.ensureSubscription()
       │
       ▼
provider mappings persisted
       │
       ▼
ACTIVE
       │
       ▼
billing cycles / usage / charges
       │
       ▼
payment obligation
       │
       ▼

PAYMENTS

payment execution
```

No step transfers ProductSubscription authority away from the Control Plane.

---

# 61. Failure example

```text
CP
ProductSubscription ACTIVE
       │
       ▼
Subscriptions
PROVISIONING
       │
       ▼
Kill Bill create subscription
       │
       ▼
Kill Bill commits
       │
       X
response lost
```

Subscriptions SHALL record an ambiguous/unknown provider outcome.

Then:

```text
UNKNOWN
   │
   ▼
provider reconciliation
   │
   ├── object found
   │       │
   │       ▼
   │    persist mapping
   │       │
   │       ▼
   │     ACTIVE
   │
   └── object absent
           │
           ▼
      safe idempotent retry
```

Creating a second provider subscription without first resolving the ambiguous outcome is prohibited.

---

# 62. Event-loss example

Suppose a Control Plane event is never delivered.

```text
CP state = ACTIVE revision 17

Subscriptions projection = revision 16
```

Periodic or targeted reconciliation detects:

```text
authoritative_revision > local_revision
```

and refreshes the projection.

This is why events are an acceleration mechanism for convergence, not the sole source of recoverability.

---

# 63. Out-of-order example

```text
CP:

revision 20 = ACTIVE
revision 21 = SUSPENDED
revision 22 = TERMINATED
```

Subscriptions receives:

```text
revision 22
revision 20
revision 21
```

After revision 22 has been applied, revisions 20 and 21 SHALL NOT regress the projection.

The final state remains consistent with revision 22.

---

# 64. Cross-engine sequence

```text
┌──────────────┐
│ baobab-cp    │
└──────┬───────┘
       │ ProductSubscription event
       ▼
┌──────────────────────┐
│ baobab-subscriptions │
└──────┬───────────────┘
       │
       ├── persist desired billing state
       │
       ├── provider operation
       │
       ▼
┌──────────────┐
│ Kill Bill    │
└──────────────┘

Later:

┌──────────────────────┐
│ reconciliation       │
└──────────┬───────────┘
           │
     ┌─────┴─────┐
     ▼           ▼
    CP        Kill Bill
     \           /
      \         /
       ▼       ▼
       compare
          │
          ▼
       converge
```

---

# 65. Domain invariants

The following invariants SHALL hold.

### INV-LIFE-01

Control Plane remains authoritative for ProductSubscription lifecycle.

### INV-LIFE-02

Billing projection lifecycle is distinct from ProductSubscription lifecycle.

### INV-LIFE-03

Provider state is distinct from billing projection state.

### INV-LIFE-04

Lifecycle processing is idempotent.

### INV-LIFE-05

Duplicate event delivery cannot duplicate business effects.

### INV-LIFE-06

Out-of-order events cannot regress authoritative projection revision.

### INV-LIFE-07

Provider failure cannot manufacture success.

### INV-LIFE-08

Unknown provider outcome must be reconciled before unsafe retry.

### INV-LIFE-09

Billing suspension does not directly revoke CapabilityGrants.

### INV-LIFE-10

Billing termination does not delete authoritative ProductSubscription history.

### INV-LIFE-11

INTERNAL subscriptions never produce monetary payment obligations.

### INV-LIFE-12

INTERNAL subscriptions may remain metered and audited.

### INV-LIFE-13

Events are not the only recovery mechanism; reconciliation is mandatory.

### INV-LIFE-14

Reconciliation cannot create upstream authority from downstream/provider state.

### INV-LIFE-15

Cross-engine state is not maintained through distributed ACID transactions.

### INV-LIFE-16

Every material lifecycle transition is auditable.

### INV-LIFE-17

Tenant context is verified independently of resource identifiers.

### INV-LIFE-18

Historical billing state remains explainable after later lifecycle changes.

---

# 66. Alternatives considered

## 66.1 Mirror Control Plane state directly

**Rejected.**

Billing has operational states that have no equivalent in the ProductSubscription lifecycle, including provisioning and billing configuration blockers.

---

## 66.2 Make Kill Bill authoritative for subscription lifecycle

**Rejected.**

Kill Bill is a billing provider, not Baobab's product-subscription authority.

---

## 66.3 Use synchronous CP → subscriptions calls only

**Rejected.**

This would increase temporal coupling and would not solve missed updates, provider outages or disaster recovery.

Synchronous APIs remain useful for authoritative reads and explicit commands, but are insufficient as the sole consistency mechanism.

---

## 66.4 Use events only and omit reconciliation

**Rejected.**

Events may be delayed, duplicated, delivered out of order, or lost through operational faults.

Financial systems require deterministic drift detection.

---

## 66.5 Use distributed transactions

**Rejected.**

Baobab engines and external providers are independently deployed systems. Distributed transaction coordination would increase coupling and still not cover all external failure modes.

---

## 66.6 Retry every provider error

**Rejected.**

Some errors are permanent, some are authorization failures, and some have ambiguous outcomes. Blind retries can create duplicate financial/provider resources.

---

## 66.7 Revoke entitlement directly after billing failure

**Rejected.**

This would make the billing engine an authorization authority and violate separation of duties.

---

# 67. Consequences

## Positive

- Control Plane authority remains clear.
- Billing can evolve independently from subscription entitlement.
- Provider outages do not corrupt platform authority.
- Missed and duplicated events are recoverable.
- Kill Bill drift can be detected and corrected.
- INTERNAL subscriptions are handled without fake monetary flows.
- Commercial readiness can be reported truthfully.
- Disaster recovery has a deterministic convergence mechanism.
- Distributed operations become auditable.

## Negative

- Multiple state machines must be maintained.
- Reconciliation infrastructure is required.
- Some transitions are eventually consistent.
- Operational tooling must expose drift and blocked states.
- Provider ambiguity requires more sophisticated recovery than simple retry.

These costs are accepted because distributed financial state cannot safely be modelled as a synchronous CRUD relationship.

---

# 68. Implementation requirements

Implementation conforming to this ADR SHALL provide:

1. a distinct billing projection lifecycle;
2. explicit desired versus observed provider state where applicable;
3. authoritative revision/version tracking;
4. durable event deduplication for lifecycle mutations;
5. idempotent provisioning;
6. controlled retry;
7. handling for ambiguous provider outcomes;
8. reconciliation against Control Plane authority;
9. reconciliation against provider state;
10. durable representation of reconciliation-required conditions;
11. tenant-scoped reconciliation;
12. correlation and causation propagation;
13. transactional outbox semantics for material outgoing events;
14. no cross-engine database reads;
15. no automatic CapabilityGrant mutation;
16. explicit configuration blockers;
17. truthful distinction between production provider readiness and temporary-provider simulation.

---

# 69. Required tests

At minimum, automated tests SHALL cover:

- creation of a projection from a valid authoritative subscription;
- duplicate creation event;
- out-of-order lifecycle events;
- stale revision rejection;
- missing event recovered by reconciliation;
- provider timeout before execution;
- provider timeout after execution with unknown result;
- safe recovery from ambiguous provider result;
- provider resource missing;
- duplicate provider resource detection;
- provider-state drift;
- suspension;
- resume;
- termination;
- delayed effective termination;
- INTERNAL lifecycle without payment invocation;
- COMMERCIAL subscription blocked by missing provider configuration;
- cross-tenant lifecycle mutation rejection;
- restart during provisioning;
- retry exhaustion;
- reconciliation after restored database state.

---

# 70. Implementation sequencing

The recommended implementation sequence following this ADR is:

```text
1. Projection lifecycle model
        │
        ▼
2. Authoritative revision tracking
        │
        ▼
3. Idempotent event consumer
        │
        ▼
4. Projection reconciliation
        │
        ▼
5. Provider desired/observed state
        │
        ▼
6. Provider reconciliation
        │
        ▼
7. Durable retry / ambiguity handling
        │
        ▼
8. Operational reconciliation tooling
```

Provider-specific implementation SHOULD remain behind the `BillingProvider` abstraction established by ADR-SUB-0001 and ADR-SUB-0002.

---

# 71. Relationship to subsequent ADRs

This ADR deliberately does not fully define the policy deciding whether a subscription is monetary, zero-charge, provider-backed or payment-requiring.

That belongs to:

**ADR-SUB-0006 — Classification-Driven Billing Policy**

Nor does this ADR define the detailed provider adapter contract and Kill Bill anti-corruption layer.

That belongs to:

**ADR-SUB-0015 — Kill Bill Adapter, Provider Port and Provider Portability**

Nor does it define workload authentication and mutation authorization in detail.

That belongs to:

**ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation**

The dependency is:

```text
ADR-SUB-0001
Kill Bill decision
      │
      ▼
ADR-SUB-0002
Domain and authority
      │
      ▼
ADR-SUB-0003
Lifecycle and convergence
      │
      ├────────► ADR-SUB-0006
      │          Billing policy
      │
      ├────────► ADR-SUB-0015
      │          Provider architecture
      │
      └────────► ADR-SUB-0016
                 Security and mutation
```

---

# 72. Final decision

Baobab SHALL treat subscription billing synchronization as a **distributed convergence problem**, not as database replication and not as a distributed transaction.

The governing model is:

```text
                 AUTHORITY
                    │
                    ▼
              baobab-cp
          ProductSubscription
                    │
             canonical change
                    │
                    ▼
                PROJECTION
                    │
                    ▼
         baobab-subscriptions
     BillingSubscriptionProjection
                    │
             desired state
                    │
                    ▼
                 PROVIDER
                    │
                    ▼
                Kill Bill
```

Correctness SHALL be maintained through:

```text
authoritative state
       +
idempotent projection
       +
durable events
       +
revision awareness
       +
controlled retry
       +
provider observation
       +
reconciliation
       =
convergent billing state
```

The central rule is:

> **Control Plane state establishes authority. Subscription state represents the billing consequence of that authority. Provider state represents execution of that billing intent. None of the three may silently substitute for another, and discrepancies must be detected, explained and reconciled rather than hidden.**