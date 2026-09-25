# ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Security / Authorization / Audit  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None

**Related:**

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment
- ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-018 — Canonical Organisation and Tenant Relationships
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-PAY-0001 — Adopt HyperSwitch as the Headless Baobab Payment Orchestration Engine
- ADR-SHARED-007 — Capability Contracts
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- Baobab IAM accepted ADR series
- `shared/contracts/subscriptions/v1`
- `shared/contracts/product/v1`
- `shared/contracts/payments/v1`

---

# 1. Context

`baobab-subscriptions` is a financially sensitive platform engine.

It maintains operational billing projections derived from authoritative ProductSubscriptions and can cause consequential actions including:

- creation of billing projections;
- commercial billing configuration;
- provider provisioning;
- suspension and cancellation;
- usage recording;
- charge calculation;
- payment obligations;
- billing-provider mutation;
- reconciliation and repair.

The service therefore sits at several trust boundaries:

```text
                         USERS / OPERATORS
                                │
                                ▼
                         Digital Estates
                                │
                                ▼
                          Baobab APIs

──────────────────── TRUST BOUNDARY ────────────────────

                         baobab-cp
                            │
                            │ trusted workload
                            ▼
                   baobab-subscriptions
                       /          \
                      /            \
                     ▼              ▼
              Kill Bill       baobab-payments
                                     │
                                     ▼
                              payment providers

──────────────────── TRUST BOUNDARY ────────────────────

                         baobab-erp
```

Incorrect authorization at this layer could cause:

- cross-tenant data disclosure;
- cross-tenant mutation;
- unauthorised zero-charge treatment;
- unauthorised commercial billing;
- duplicate charges;
- unauthorised cancellation;
- provider corruption;
- misleading financial history;
- bypass of platform governance.

Security therefore cannot consist merely of validating that a request contains a token.

Baobab must distinguish:

```text
Authentication
      │
      ▼
Who/what are you?

Authorization
      │
      ▼
What are you permitted to do?

Trusted Context
      │
      ▼
For which tenant/account/resource?

Domain Authority
      │
      ▼
Does this service have authority
to make this decision?

Controlled Mutation
      │
      ▼
Can the permitted operation be
performed safely and audibly?
```

---

# 2. Decision

`baobab-subscriptions` SHALL implement a layered security model based on:

1. authenticated workload and actor identity;
2. trusted tenant/context propagation;
3. least-privilege authorization;
4. explicit domain authority boundaries;
5. controlled domain mutations;
6. mandatory idempotency for material mutations;
7. durable audit evidence;
8. strict provider credential isolation;
9. fail-closed tenant isolation;
10. privileged but governed reconciliation and administrative operations.

The security model SHALL follow:

```text
REQUEST
   │
   ▼
Authenticate
   │
   ▼
Establish trusted principal
   │
   ▼
Establish trusted context
   │
   ▼
Authorize requested operation
   │
   ▼
Validate domain authority
   │
   ▼
Validate lifecycle transition
   │
   ▼
Validate idempotency
   │
   ▼
Execute controlled mutation
   │
   ▼
Persist state + audit/outbox
   │
   ▼
Return deterministic result
```

No individual layer SHALL substitute for the others.

---

# 3. Security principles

The subscriptions engine SHALL follow these principles:

### SEC-01 — Deny by default

Access is denied unless explicitly authorised.

### SEC-02 — Least privilege

Every workload and actor receives only the permissions required for its role.

### SEC-03 — Trusted context

Tenant/account context must originate from trusted platform mechanisms.

### SEC-04 — Separation of duties

Identity, subscription authority, billing, payments and accounting remain separate responsibilities.

### SEC-05 — Controlled mutation

Financially meaningful state cannot be modified as arbitrary CRUD.

### SEC-06 — Idempotency

Retries cannot duplicate material financial effects.

### SEC-07 — Auditability

Material operations must be reconstructable.

### SEC-08 — Secret minimisation

Provider credentials are exposed only to components requiring them.

### SEC-09 — Fail closed

Uncertainty about authority, context or classification does not result in permissive behaviour.

### SEC-10 — Provider isolation

External provider trust does not propagate into canonical Baobab authority.

---

# 4. Identity domains

Baobab SHALL distinguish at least:

```text
Human Identity
      │
      ├── tenant user
      ├── staff/operator
      └── privileged administrator

Workload Identity
      │
      ├── baobab-cp
      ├── baobab-subscriptions
      ├── baobab-payments
      └── other authorised engines

Provider Identity
      │
      └── Kill Bill / provider callback

Resource Identity
      │
      ├── tenant_id
      ├── platform_account_id
      ├── product_subscription_id
      └── billing_subscription_id
```

These identities SHALL not be conflated.

---

# 5. Authentication versus domain authority

Authentication proves the identity of a principal.

It does not prove that the principal owns a domain decision.

For example:

```text
authenticated tenant user
          │
          X
          ▼
assign INTERNAL classification
```

is prohibited.

Likewise:

```text
authenticated subscriptions workload
          │
          X
          ▼
create ProductSubscription
```

is prohibited.

The caller may be authentic while still lacking domain authority.

---

# 6. Baobab IAM boundary

Baobab IAM SHALL provide identity and authentication capabilities according to the accepted IAM architecture.

IAM SHALL NOT become the owner of subscription business semantics.

Conceptually:

```text
Baobab IAM
     │
     │ proves identity / issues trusted claims
     ▼
Subscriptions
     │
     │ evaluates domain authorization
     ▼
Domain operation
```

Not:

```text
IAM role
   │
   ▼
defines subscription classification
```

IAM identifies principals.

The appropriate Baobab domain owns domain decisions.

---

# 7. Workload identity

Service-to-service communication SHALL use authenticated workload identity.

At minimum, the architecture requires trusted identities for:

```text
baobab-cp
      │
      ▼
baobab-subscriptions
```

and:

```text
baobab-subscriptions
      │
      ▼
baobab-payments
```

as well as other authorised engine interactions.

Production SHALL NOT rely on long-lived static bearer secrets as the normal workload identity model.

---

# 8. Workload identity properties

A production workload identity mechanism SHALL provide or enable:

- strong service identity;
- short-lived credentials where supported;
- audience restriction;
- issuer validation;
- expiry validation;
- rotation;
- least-privilege authorization;
- revocation or equivalent containment;
- observable authentication failures.

The exact transport and credential mechanism SHALL conform to the platform IAM and infrastructure architecture.

This ADR does not create a competing identity protocol.

---

# 9. Workload audience

Credentials issued for one service SHALL not be treated as universally valid across the platform.

For example:

```text
credential intended for baobab-subscriptions
```

SHOULD NOT automatically be accepted by:

```text
baobab-payments
baobab-erp
baobab-cp
```

unless the platform identity architecture explicitly authorises the relevant audience.

Audience restriction reduces credential replay across services.

---

# 10. Workload authorization

An authenticated workload SHALL be authorised for specific operations.

Conceptually:

```text
baobab-cp
   │
   ├── may project authorised subscription lifecycle
   └── may request reconciliation of relevant projection

baobab-subscriptions
   │
   ├── may invoke approved billing-provider operations
   └── may submit approved payment obligations

arbitrary workload
   │
   └── DENIED
```

Authentication alone SHALL not imply broad internal trust.

---

# 11. Trusted tenant context

Every tenant-scoped operation SHALL execute inside trusted tenant context.

The effective security context SHOULD conceptually contain:

```text
SecurityContext
──────────────────────────

principal_id
principal_type

workload_id / actor_id

tenant_id
platform_account_id
organisation_id where relevant

permissions / authorised capabilities

correlation_id
request_id
```

The exact canonical contract belongs in Shared/IAM/Control Plane architecture.

---

# 12. Tenant identity cannot come from untrusted input alone

A request such as:

```text
X-Tenant-ID: tenant-B
```

does not by itself establish authority to act for `tenant-B`.

Likewise:

```json
{
  "tenant_id": "tenant-B"
}
```

does not establish authority.

Tenant context SHALL be validated against authenticated platform context.

---

# 13. Resource lookup must be tenant-scoped

The unsafe pattern:

```text
SELECT *
FROM billing_subscription
WHERE id = :id
```

followed by a later authorization check SHALL be avoided where practical.

The logical access rule is:

```text
resource
WHERE
    id = requested_id
AND tenant_id = trusted_tenant_id
```

or an equivalent repository-level enforcement mechanism.

Tenant isolation SHOULD be difficult to bypass accidentally.

---

# 14. Cross-tenant not-found semantics

Where disclosure of resource existence would itself leak tenant information, cross-tenant access SHOULD behave as resource-not-found rather than confirming:

> This resource exists, but belongs to another tenant.

Conceptually:

```text
tenant A requests tenant B resource
             │
             ▼
          NOT FOUND
```

rather than exposing cross-tenant ownership.

---

# 15. Platform-level operations

Some operational functions legitimately span multiple tenants, including:

- bounded reconciliation;
- platform health analysis;
- migration;
- disaster recovery;
- authorised support operations.

Such operations SHALL require explicit platform-level authority.

They SHALL NOT be implemented by simply omitting the tenant filter from ordinary tenant-scoped code.

---

# 16. Explicit privileged context

Cross-tenant administrative work SHALL execute through an explicit privileged path.

Conceptually:

```text
ordinary tenant context
        │
        ▼
tenant-scoped operation

privileged platform context
        │
        ▼
explicitly authorised
cross-tenant operation
```

The two modes SHALL be distinguishable in code and audit.

---

# 17. Classification protection

Subscription classification is financially sensitive.

As established by ADR-SUB-0006:

```text
classification = INTERNAL
```

means zero monetary charge.

Therefore an attacker obtaining INTERNAL classification could obtain service without monetary billing.

Subscriptions SHALL consequently treat classification as authoritative upstream state.

---

# 18. Caller cannot manufacture INTERNAL treatment

The following SHALL NOT result in INTERNAL treatment:

```json
{
  "classification": "INTERNAL"
}
```

from:

- browser input;
- tenant API request;
- supplier/customer portal;
- digital estate;
- arbitrary internal workload.

The engine SHALL resolve classification from authoritative Control Plane state or trusted canonical lifecycle information.

---

# 19. Defence in depth for INTERNAL

Before a monetary obligation is generated, subscriptions SHALL verify the effective billing decision.

Conceptually:

```text
charge candidate
      │
      ▼
authoritative projected classification
      │
      ▼
billing policy
      │
      ├── INTERNAL
      │      │
      │      ▼
      │     STOP
      │
      └── COMMERCIAL
             │
             ▼
      continue if authorised
```

This provides a financial safety barrier even if an earlier component behaves incorrectly.

---

# 20. Controlled mutation

Material subscription operations SHALL be modelled as explicit domain commands rather than arbitrary field mutation.

Preferred:

```text
SuspendBillingSubscription

ResumeBillingSubscription

TerminateBillingSubscription

RecordUsage

ReconcileBillingSubscription

ApplyAuthoritativeSubscriptionChange
```

Not:

```text
PATCH /billing-subscriptions/{id}

{
  "status": "...",
  "classification": "...",
  "provider_id": "...",
  "amount": "..."
}
```

where callers can directly manufacture domain state.

---

# 21. State is consequence, not caller input

For protected lifecycle fields:

```text
status
operational_status
classification
provider_state
monetary_treatment
```

the resulting value SHALL normally be a consequence of a validated command.

Example:

```text
Suspend command
      │
      ▼
authorize
      │
      ▼
validate current state
      │
      ▼
execute lifecycle transition
      │
      ▼
status = SUSPENDED
```

The caller requests the operation.

The domain determines the state.

---

# 22. Mutation authorization

Every controlled mutation SHALL answer:

1. Who is requesting this?
2. For which tenant?
3. For which resource?
4. Is the caller authorised?
5. Does this engine own the decision?
6. Is the transition valid?
7. Is the request idempotent?
8. Is the operation safe under current authoritative state?

Only then SHALL mutation proceed.

---

# 23. Mutation transaction

Where practical, a material local mutation SHALL atomically persist:

```text
domain state
     +
audit evidence
     +
outbox intent
```

Conceptually:

```text
BEGIN

validate command

mutate aggregate

write audit record

write outbox event

COMMIT
```

This reduces the risk of financially meaningful state changing without corresponding evidence or event intent.

---

# 24. Idempotency requirement

Every material externally initiated mutation SHALL require an idempotency identity.

ADR-SUB-0001 establishes the HTTP-facing rule:

```text
Idempotency-Key
```

for mutations.

The underlying invariant applies beyond HTTP.

Retries of the same logical command SHALL converge on one business effect.

---

# 25. Idempotency scope

Idempotency SHALL be scoped sufficiently to avoid collisions across:

- tenants;
- operations;
- resources;
- callers where applicable.

Conceptually:

```text
idempotency identity =
    tenant
  + operation
  + idempotency_key
```

with additional scope where required.

---

# 26. Idempotency persistence

Material idempotency records SHALL survive process restart.

In-memory deduplication is insufficient for financially consequential operations.

The platform SHOULD retain:

```text
idempotency_key
operation
tenant_id

request_fingerprint

status

result_reference

created_at
completed_at
```

or equivalent durable evidence.

---

# 27. Idempotency request mismatch

If the same idempotency key is reused with materially different input:

```text
key = ABC
request 1 = suspend subscription X

key = ABC
request 2 = terminate subscription Y
```

the service SHALL reject the second use rather than treating it as the same operation.

A request fingerprint or equivalent mechanism SHOULD detect this misuse.

---

# 28. Concurrent mutation

Idempotency does not by itself prevent conflicting concurrent commands.

The engine SHALL additionally use appropriate concurrency control.

Possible implementation mechanisms include:

- aggregate revision;
- optimistic locking;
- transactional row locking;
- compare-and-set semantics.

The exact mechanism is implementation-specific.

The invariant is:

> Concurrent operations must not silently overwrite one another.

---

# 29. Revision-aware mutation

Where aggregate revision is available:

```text
expected_revision = 14
current_revision = 15
```

a command based on stale state SHOULD fail or reconcile rather than overwrite revision 15.

This is particularly important for:

- classification changes;
- suspension/resumption;
- termination;
- provider reconciliation.

---

# 30. Replay resistance

Security-sensitive commands SHALL be resistant to unintended replay through:

- credential validation;
- expiry;
- idempotency;
- revision checking;
- bounded acceptance of event versions;
- duplicate event detection.

A previously valid command SHALL not be blindly reusable forever.

---

# 31. Event authentication

Events affecting billing state SHALL be accepted only from trusted producers.

An event payload claiming:

```text
producer = baobab-cp
```

does not establish producer identity by itself.

Producer authenticity SHALL derive from the trusted messaging/workload infrastructure.

---

# 32. Event tenant validation

Incoming events SHALL be checked for consistency among:

- trusted producer;
- tenant context;
- authoritative resource;
- projected resource mapping.

A mismatch SHALL fail closed.

Example:

```text
event tenant = A
subscription resolves to tenant = B
```

must not be applied.

---

# 33. Event schema validation

Security includes semantic integrity.

Incoming events SHALL be validated against their supported canonical schema version.

Malformed or unsupported events SHALL not be partially applied.

They SHALL enter appropriate failure/dead-letter/operational handling according to platform messaging architecture.

---

# 34. Audit versus application logging

Audit records and application logs serve different purposes.

```text
Application log
     │
     └── operational diagnostics

Audit record
     │
     └── evidence of material action
```

A material financial mutation SHALL not be considered audited merely because a log line happened to be emitted.

---

# 35. Auditable operations

At minimum, audit evidence SHALL be generated for:

- billing projection creation;
- classification projection/change;
- billing-policy application where financially material;
- suspension;
- resumption;
- termination;
- commercial configuration changes;
- provider provisioning;
- provider cancellation;
- payment-obligation generation;
- credits/adjustments;
- manual reconciliation;
- automatic material repair;
- privileged cross-tenant operations;
- administrative provider interventions;
- security-sensitive configuration changes.

---

# 36. Audit record

A material audit record SHOULD contain, where applicable:

```text
audit_id

occurred_at

principal_type
principal_id

workload_id
actor_id

tenant_id
platform_account_id

resource_type
resource_id

operation

previous_state
resulting_state

reason_code
reason

idempotency_key

correlation_id
causation_id

authoritative_revision

policy_version

provider_reference

outcome
```

Sensitive fields SHALL be minimised or redacted.

---

# 37. Human actor through workload

When a human action reaches subscriptions through another trusted service, the system SHOULD preserve both identities where available:

```text
actor:
    user-123

workload:
    baobab-cp
```

This allows the audit trail to answer both:

> Which human initiated the action?

and:

> Which workload executed the trusted call?

The workload SHALL not erase actor provenance.

---

# 38. Machine-initiated operations

For automated operations, actor identity may be absent.

The audit SHALL still identify the workload and cause.

Example:

```text
actor = NONE
workload = subscriptions-reconciler
reason = PROVIDER_STATE_DRIFT
```

Automated does not mean unaudited.

---

# 39. Reason codes

Privileged and corrective mutations SHOULD require stable machine-readable reason codes.

Examples:

```text
PROVIDER_STATE_DRIFT
AUTHORITATIVE_STATE_RECONCILIATION
DUPLICATE_PROVIDER_RESOURCE
DISASTER_RECOVERY
OPERATOR_CORRECTION
```

Free-form text MAY supplement the code but SHALL not replace structured reason semantics.

---

# 40. Audit immutability

Normal application operations SHALL not update or delete historical audit evidence.

Corrections SHOULD create new audit evidence rather than rewriting history.

If stronger tamper-evidence mechanisms are adopted platform-wide, subscriptions SHALL integrate with them.

This ADR does not claim cryptographic immutability unless the supporting infrastructure actually provides it.

---

# 41. Audit access

Audit information itself is sensitive.

Access SHALL be restricted according to:

- tenant scope;
- operational role;
- platform administrative authority;
- legal/compliance need.

A tenant SHALL not gain access to another tenant's audit trail.

---

# 42. Sensitive data minimisation

`baobab-subscriptions` SHALL store only data required for its billing responsibilities.

It SHOULD avoid duplicating:

- unnecessary identity-profile data;
- authentication credentials;
- payment credentials;
- card details;
- bank credentials;
- provider secrets.

References SHOULD be preferred over replicated sensitive records where practical.

---

# 43. Payment data boundary

Subscriptions SHALL NOT store raw payment credentials merely because it creates payment obligations.

The architecture is:

```text
Subscriptions
     │
     │ payment obligation/reference
     ▼
Payments
     │
     ▼
HyperSwitch/payment providers
```

Sensitive payment instruments remain within the payment architecture.

---

# 44. Provider credential isolation

Kill Bill credentials SHALL be accessible only to the provider integration component requiring them.

They SHALL NOT be:

- exposed through tenant APIs;
- included in events;
- committed to Git;
- copied into Shared contracts;
- logged;
- distributed to unrelated engines.

---

# 45. Secret management

Production secrets SHALL be supplied through approved infrastructure secret-management mechanisms.

Secret handling SHALL support:

- environment separation;
- least privilege;
- rotation;
- revocation;
- non-disclosure in logs;
- non-disclosure in traces;
- non-disclosure in error messages.

Static secrets in source-controlled `.env` files are prohibited for production.

---

# 46. Secret rotation

Provider credential rotation SHOULD be possible without rewriting domain state.

The adapter SHALL not use credentials as provider identity mappings.

Rotation affects authentication to the provider, not canonical resource identity.

---

# 47. Logging policy

Logs SHALL NOT contain:

- bearer tokens;
- workload credentials;
- Kill Bill secrets;
- payment credentials;
- raw card data;
- authentication secrets;
- unnecessary personal data.

Request/response logging SHALL use sanitisation and allowlists rather than assuming arbitrary payloads are safe.

---

# 48. Error responses

Error responses SHALL provide sufficient diagnostic value without exposing:

- internal stack traces;
- credentials;
- provider secrets;
- database details;
- cross-tenant resource existence;
- sensitive financial information.

Detailed diagnostic information belongs in protected operational telemetry.

---

# 49. Provider callback security

Provider-originated callbacks SHALL terminate at a provider-specific ingress boundary as defined by ADR-SUB-0015.

The ingress SHALL:

```text
verify provider authenticity
        │
        ▼
validate payload
        │
        ▼
deduplicate
        │
        ▼
resolve ProviderReference
        │
        ▼
derive trusted Baobab context
        │
        ▼
process observation
```

Provider-supplied tenant identifiers SHALL not establish Baobab tenant authority.

---

# 50. SSRF and provider endpoint control

Provider endpoints SHALL be operator-controlled configuration.

Tenant input SHALL NOT be allowed to select arbitrary Kill Bill endpoints.

Otherwise an attacker could transform provider integration into server-side request forgery.

The provider adapter SHALL communicate only with approved configured destinations.

---

# 51. Input validation

All externally supplied data SHALL be validated before domain execution.

Validation SHALL include as applicable:

- identifier format;
- currency;
- decimal amount precision;
- timestamp;
- usage quantity;
- effective date;
- enum/classification support;
- reason code;
- pagination bounds;
- provider callback structure.

Financial values SHALL use exact decimal semantics rather than binary floating-point arithmetic.

---

# 52. Usage security

Usage recording can create financial consequences for COMMERCIAL subscriptions.

Therefore usage ingestion SHALL be treated as financially sensitive.

The service SHALL establish:

- authorised producer;
- tenant;
- ProductSubscription/billing projection;
- usage metric;
- quantity;
- event identity;
- effective timestamp.

Untrusted arbitrary usage submission SHALL not directly create billable consumption.

---

# 53. Usage idempotency

Usage records SHALL carry sufficient identity to prevent duplicate financial counting.

Conceptually:

```text
usage_event_id
tenant_id
subscription_id
metric
quantity
occurred_at
```

Re-delivery of the same usage event SHALL not create duplicate rated usage.

---

# 54. Usage correction

Usage correction SHALL not silently rewrite financially material history.

Where corrections affect prior billing, they SHALL be represented through controlled correction/adjustment semantics with audit evidence.

---

# 55. Reconciliation authority

Reconciliation is powerful because it can mutate local/provider state.

It SHALL therefore have explicit authorization.

Reconciliation SHALL distinguish:

```text
READ-ONLY RECONCILIATION
        │
        ▼
detect drift

REPAIR RECONCILIATION
        │
        ▼
mutate state
```

Permission to inspect drift SHALL not automatically imply permission to repair it.

---

# 56. Automatic reconciliation

Automated repair MAY execute under a dedicated workload identity with narrowly scoped authority.

For example:

```text
subscriptions-reconciler
       │
       ├── inspect subscription projection
       ├── inspect provider state
       └── repair explicitly safe drift
```

It SHALL not have unrestricted authority to modify Control Plane ProductSubscriptions.

---

# 57. Manual reconciliation

Manual repair SHALL require:

- privileged identity;
- explicit target;
- reason;
- tenant context;
- permitted repair operation;
- audit evidence.

A generic database-edit capability SHALL not be the normal reconciliation mechanism.

---

# 58. No routine direct database mutation

Operators SHALL NOT normally repair billing state by directly updating database tables.

Preferred:

```text
operator
   │
   ▼
controlled repair command
   │
   ▼
authorization
   │
   ▼
domain validation
   │
   ▼
mutation + audit
```

Not:

```text
operator
   │
   ▼
UPDATE billing_subscription
SET status = ...
```

Direct database intervention is an exceptional disaster-recovery mechanism and SHALL be governed separately.

---

# 59. Provider administration

Direct Kill Bill administration is likewise exceptional.

Provider-side manual intervention SHALL require:

- privileged operational access;
- documented reason;
- change tracking;
- subsequent Baobab reconciliation.

A Kill Bill administrator SHALL not silently change canonical Baobab state.

---

# 60. Break-glass access

If platform operations support emergency/break-glass access, it SHALL be:

- exceptional;
- strongly authenticated;
- time-bounded where infrastructure permits;
- narrowly authorised;
- prominently audited;
- reviewable after use.

Break-glass access SHALL not be implemented as a permanent superuser credential shared by operators.

---

# 61. Separation of duties

No single component SHALL casually accumulate all of:

```text
subscription authority
billing policy
payment execution
accounting authority
identity authority
```

The intended separation remains:

```text
IAM
 │ identity
 ▼

Control Plane
 │ subscription/classification/entitlement authority
 ▼

Subscriptions
 │ billing
 ▼

Payments
 │ monetary execution
 ▼

ERP
 │ accounting
 ▼
```

This separation reduces both accidental and malicious blast radius.

---

# 62. Subscription-to-payment trust

When subscriptions requests payment execution, payments SHALL authenticate the subscriptions workload independently.

The request SHOULD contain trusted references sufficient to identify:

- tenant;
- billing obligation;
- amount;
- currency;
- correlation;
- idempotency identity.

Payments SHALL not assume that any internal caller is permitted to request monetary execution.

---

# 63. Payment amount integrity

The monetary amount sent to `baobab-payments` SHALL derive from validated billing state.

A caller SHALL not be able to bypass billing logic by submitting an arbitrary amount directly through the subscriptions mutation interface.

The flow is:

```text
BillingTerms
     │
     ▼
usage/recurring calculation
     │
     ▼
Charge
     │
     ▼
payment obligation
     │
     ▼
baobab-payments
```

---

# 64. Currency integrity

Currency SHALL be explicit and validated.

Amounts SHALL never be interpreted without currency context.

The pair:

```text
amount
currency
```

is financially meaningful.

A mutation that changes currency is therefore not a cosmetic field update.

---

# 65. Credits and adjustments

Credits, adjustments and other monetary corrections SHALL be controlled mutations.

They SHALL require:

- authorization;
- reason;
- affected obligation;
- amount/currency;
- audit evidence;
- idempotency.

The platform SHALL not expose unrestricted:

```text
set balance = X
```

operations.

---

# 66. Authorization decision model

The conceptual authorization decision is:

```text
ALLOW =
    authenticated(principal)
AND trusted_context(context)
AND permitted(principal, operation)
AND tenant_authorized(principal, context)
AND domain_authority_valid(operation)
AND transition_valid(resource, operation)
```

Only then may the operation proceed.

---

# 67. Authorization is operation-specific

Permissions SHOULD correspond to domain operations rather than broad generic roles alone.

Conceptually:

```text
billing.subscription.read
billing.subscription.suspend
billing.subscription.terminate
billing.usage.record
billing.reconciliation.read
billing.reconciliation.repair
billing.adjustment.create
billing.provider.admin
```

The exact canonical permission vocabulary SHALL align with Baobab IAM and Shared contracts.

This ADR does not independently establish a conflicting permission registry.

---

# 68. Least privilege example

A workload that records usage may need:

```text
billing.usage.record
```

but does not consequently need:

```text
billing.subscription.terminate
billing.reconciliation.repair
billing.provider.admin
```

Similarly, a tenant billing viewer need not receive mutation authority.

---

# 69. Authorization caching

If authorization decisions are cached, cache lifetime SHALL respect:

- credential expiry;
- permission changes;
- revocation semantics;
- tenant context.

Long-lived authorization caches SHALL not defeat IAM revocation/deprovisioning controls.

---

# 70. Time handling

Security-sensitive timestamps SHALL use a consistent authoritative time basis.

This includes:

- token expiry;
- idempotency records;
- audit timestamps;
- effective billing transitions;
- provider operation timestamps;
- reconciliation evidence.

Time-zone presentation is a UI concern.

Persisted security and financial timestamps SHOULD use an unambiguous canonical representation.

---

# 71. Correlation

Every material distributed operation SHOULD propagate:

```text
correlation_id
```

and where applicable:

```text
causation_id
```

across:

```text
Control Plane
      │
      ▼
Subscriptions
      │
      ├──► Kill Bill
      │
      └──► Payments
```

This permits security and financial investigations to reconstruct distributed execution.

---

# 72. Request identity

Correlation IDs SHALL not be treated as authentication credentials.

A caller knowing a valid correlation ID receives no additional authority.

Likewise, idempotency keys SHALL not be bearer secrets granting access to prior results across tenant boundaries.

---

# 73. Audit and correlation separation

A correlation ID links related operations.

An audit ID identifies an audit record.

An idempotency key identifies a logical mutation attempt.

These identifiers SHALL not be conflated.

```text
correlation_id
      !=
audit_id
      !=
idempotency_key
```

---

# 74. Security observability

Subscriptions SHALL expose security-relevant telemetry for conditions including:

- authentication failures;
- authorization failures;
- tenant-context mismatch;
- cross-tenant access attempts;
- invalid workload audience;
- invalid/expired credentials;
- idempotency conflicts;
- stale-revision conflicts;
- unsupported classification attempts;
- INTERNAL payment prevention;
- provider authentication failure;
- reconciliation repair operations;
- privileged administrative operations.

Telemetry SHALL avoid leaking sensitive data.

---

# 75. Alert-worthy events

High-severity operational alerts SHOULD include:

```text
payment attempted for INTERNAL subscription

cross-tenant provider mapping detected

repeated provider authentication failures

unexpected privileged repair volume

duplicate provider resources with monetary risk

classification authority mismatch

audit persistence failure during financial mutation
```

The precise alert thresholds belong to operational policy.

---

# 76. Audit failure

For operations requiring durable audit evidence, inability to persist mandatory audit evidence SHALL normally fail the mutation rather than execute an unaudited financial change.

Where exceptional availability requirements justify otherwise, that exception must be explicitly designed and governed.

Silent loss of required audit evidence is prohibited.

---

# 77. Security failure modes

The service SHALL fail closed for:

| Condition | Behaviour |
|---|---|
| Missing authentication | Reject |
| Invalid workload identity | Reject |
| Wrong audience | Reject |
| Missing trusted tenant context | Reject |
| Tenant/resource mismatch | Not found/reject |
| Insufficient permission | Reject |
| Unsupported classification | Block |
| Caller-supplied INTERNAL override | Ignore/reject |
| Idempotency conflict | Reject |
| Stale mutation revision | Conflict/reconcile |
| Provider credential failure | Stop/alert |
| Unknown provider result | Reconcile |
| Missing required audit persistence | Fail material mutation |
| Unauthorised repair request | Reject |

---

# 78. Threat: cross-tenant identifier enumeration

An attacker may attempt:

```text
GET /billing-subscriptions/1
GET /billing-subscriptions/2
GET /billing-subscriptions/3
...
```

Mitigations SHALL include:

- authenticated access;
- tenant-scoped lookup;
- non-disclosing cross-tenant responses;
- appropriate rate limiting;
- non-sequential/opaque identifiers where canonical identity strategy provides them.

Identifier opacity SHALL not replace authorization.

---

# 79. Threat: INTERNAL classification injection

Attack:

```text
tenant request
{
  "classification": "INTERNAL"
}
```

Mitigation:

```text
ignore untrusted classification
        │
        ▼
resolve authoritative CP state
        │
        ▼
apply canonical billing policy
```

---

# 80. Threat: duplicate financial command

Attack or failure:

```text
same payment-producing command
sent repeatedly
```

Mitigation:

```text
authentication
     +
idempotency
     +
request fingerprint
     +
aggregate revision
     =
single business effect
```

---

# 81. Threat: stale lifecycle replay

Attack/failure:

```text
old ACTIVE event replayed
after TERMINATED revision
```

Mitigation:

```text
event identity
     +
aggregate revision
     +
authoritative reconciliation
```

The subscription SHALL not be resurrected by stale delivery.

---

# 82. Threat: provider callback spoofing

Attack:

```text
attacker sends fake Kill Bill callback
```

Mitigation:

```text
provider authentication/verification
        +
payload validation
        +
provider reference lookup
        +
deduplication
        +
trusted tenant derivation
```

---

# 83. Threat: provider credential theft

Mitigation SHALL include:

- least-privilege provider credentials;
- secret-management controls;
- restricted network access;
- credential rotation;
- no credential logging;
- adapter-only access;
- operational detection.

Provider credential compromise SHALL not automatically grant Control Plane authority.

---

# 84. Threat: confused deputy

A trusted workload may be tricked into acting for a tenant it is not authorised to represent.

Mitigation:

```text
authenticated workload
        +
trusted tenant context
        +
operation authorization
        +
resource tenant validation
```

The service SHALL not assume:

> internal caller = authorised for every tenant.

---

# 85. Threat: mass assignment

Generic request-to-entity binding SHALL NOT permit clients to mutate protected fields such as:

```text
tenant_id
classification
status
provider_reference
billing_amount
policy_version
audit fields
```

Explicit command DTOs and allowlisted mutation fields SHALL be preferred.

---

# 86. Threat: privilege escalation through reconciliation

An ordinary operator might attempt to use reconciliation to perform mutations otherwise forbidden.

Mitigation:

```text
reconciliation.read
        !=
reconciliation.repair
        !=
provider.admin
```

Repair authority SHALL be independently controlled.

---

# 87. Threat: log exfiltration

Provider payloads, tokens or payment information may accidentally enter logs.

Mitigation:

- structured logging;
- field allowlists;
- redaction;
- secret scanning;
- tests for sensitive-data leakage;
- restricted log access.

---

# 88. Threat: direct database bypass

An operator with database access could bypass domain authorization.

Mitigation SHOULD include:

- restricted production database access;
- application service accounts with least privilege;
- separation between application and operator credentials;
- controlled migration tooling;
- audited emergency access;
- operational policy prohibiting routine manual mutation.

Database privilege is infrastructure security and SHALL complement, not replace, application authorization.

---

# 89. Network posture

The subscriptions service and Kill Bill SHOULD not be unnecessarily exposed to the public Internet.

Kill Bill in particular SHOULD reside behind controlled network boundaries and be reachable only by authorised integration components and required operational tooling.

Network isolation is defence in depth.

It does not replace application authentication.

---

# 90. Public API boundary

Digital estates SHOULD interact with Baobab-owned APIs.

They SHALL NOT receive direct Kill Bill access.

```text
ZuriBeans / Thamani / future estate
              │
              ▼
        Baobab API boundary
              │
              ▼
       baobab-subscriptions
              │
              ▼
        KillBillAdapter
              │
              ▼
           Kill Bill
```

This protects both provider portability and security.

---

# 91. Environment separation

Development, staging and production SHALL use distinct credentials and appropriately isolated provider resources.

Production credentials SHALL not be reused for routine development.

`TemporaryProvider` SHALL remain clearly non-production.

A developer configuration error SHALL not silently point local development at production billing infrastructure.

---

# 92. Production safety

Production startup/configuration SHALL fail closed where critical security configuration is absent.

Examples include:

- missing workload authentication configuration;
- missing required provider credentials;
- insecure provider mode where production policy forbids it;
- unsupported provider configuration.

A production environment SHALL not silently downgrade to development security.

---

# 93. Dependency and supply-chain security

The subscriptions service and Kill Bill adapter SHALL follow platform dependency-management and CI security controls.

This SHOULD include appropriate:

- dependency review;
- vulnerability scanning;
- container scanning;
- secret scanning;
- pinned deployment artifacts;
- provenance controls where adopted platform-wide.

Provider dependencies SHALL not bypass normal repository security gates.

---

# 94. Kill Bill version integrity

As established by ADR-SUB-0015, Kill Bill production versions SHALL be explicitly pinned.

The deployed artifact SHALL be traceable to the approved version.

Mutable `latest` tags SHALL not be relied upon for production billing infrastructure.

---

# 95. Security testing

Automated testing SHALL include:

### Authentication

- missing credentials;
- invalid credentials;
- expired credentials;
- wrong audience;
- unauthorised workload.

### Tenant isolation

- valid same-tenant read;
- cross-tenant read;
- cross-tenant mutation;
- forged tenant header;
- forged tenant body field;
- provider-reference tenant mismatch.

### Authorization

- permitted operation;
- denied operation;
- read versus mutation;
- reconciliation read versus repair;
- provider administration denial.

### Classification

- caller attempts INTERNAL injection;
- unsupported classification;
- authoritative classification mismatch;
- payment attempt for INTERNAL.

### Mutation

- missing Idempotency-Key;
- repeated identical command;
- repeated key with different payload;
- concurrent mutation;
- stale revision.

### Provider

- invalid Kill Bill credentials;
- callback spoofing;
- provider timeout;
- unknown outcome;
- forged provider resource mapping.

### Audit

- audit produced for material mutation;
- actor/workload provenance preserved;
- failed operation appropriately recorded where policy requires;
- audit persistence failure prevents protected mutation.

---

# 96. Security integration tests

Integration testing SHOULD verify trust boundaries across:

```text
IAM
 │
 ▼
Control Plane
 │
 ▼
Subscriptions
 │
 ├──► Kill Bill
 │
 └──► Payments
```

Mock-only security tests SHALL not be considered sufficient for production readiness.

At least representative workload authentication and tenant-context propagation SHALL be exercised against production-equivalent infrastructure before go-live.

---

# 97. Domain invariants

The following invariants SHALL hold.

### INV-SEC-01

Every protected operation requires authenticated identity.

### INV-SEC-02

Authentication alone does not grant domain authority.

### INV-SEC-03

Tenant context originates from trusted platform context.

### INV-SEC-04

Caller-supplied tenant identity cannot override trusted tenant context.

### INV-SEC-05

Cross-tenant resources fail closed.

### INV-SEC-06

Classification cannot be mutated through subscriptions APIs.

### INV-SEC-07

Caller-supplied INTERNAL classification cannot create zero-charge treatment.

### INV-SEC-08

INTERNAL subscriptions cannot generate payment obligations.

### INV-SEC-09

Material mutations are explicit domain operations.

### INV-SEC-10

Material mutations are idempotent.

### INV-SEC-11

Idempotency survives process restart.

### INV-SEC-12

Conflicting reuse of an idempotency key is rejected.

### INV-SEC-13

Concurrent mutation cannot silently overwrite newer state.

### INV-SEC-14

Material financial operations are auditable.

### INV-SEC-15

Normal application operations cannot rewrite historical audit evidence.

### INV-SEC-16

Provider credentials are isolated to authorised provider integration.

### INV-SEC-17

Subscriptions does not store raw payment credentials.

### INV-SEC-18

Provider callbacks cannot establish tenant authority from untrusted payload fields.

### INV-SEC-19

Reconciliation repair requires stronger authority than reconciliation inspection.

### INV-SEC-20

Routine operator repair occurs through controlled domain commands, not direct database mutation.

### INV-SEC-21

Kill Bill administration cannot redefine canonical Baobab state.

### INV-SEC-22

Security uncertainty fails closed.

### INV-SEC-23

Workload identity does not imply unrestricted cross-tenant authority.

### INV-SEC-24

Audit, correlation and idempotency identities remain distinct.

---

# 98. Alternatives considered

## 98.1 Trust all internal network traffic

**Rejected.**

Internal location does not establish workload identity, tenant authority or permission.

---

## 98.2 Use static bearer tokens permanently between services

**Rejected as the normal production model.**

Long-lived shared credentials increase replay and compromise risk and provide poor workload isolation.

---

## 98.3 Trust tenant IDs from request headers

**Rejected.**

Headers are input, not authority.

---

## 98.4 Let IAM roles define billing semantics

**Rejected.**

IAM proves identity and contributes authorization context; it does not own ProductSubscription classification or billing policy.

---

## 98.5 Expose generic CRUD for billing entities

**Rejected.**

Generic mutation enables invalid lifecycle transitions and mass assignment of protected financial fields.

---

## 98.6 Depend only on database constraints

**Rejected.**

Database constraints are useful defence in depth but cannot represent the complete cross-engine authority and authorization model.

---

## 98.7 Depend only on API gateway authorization

**Rejected.**

The subscriptions engine must independently enforce its domain and tenant invariants.

---

## 98.8 Allow operators to repair state directly in the database

**Rejected as normal practice.**

It bypasses lifecycle validation, idempotency, events and audit.

---

## 98.9 Give reconciliation unrestricted administrative authority

**Rejected.**

Detection and repair are distinct privileges.

---

## 98.10 Store payment credentials in subscriptions

**Rejected.**

Payment-sensitive data belongs to the payments architecture.

---

# 99. Consequences

## Positive

- Tenant isolation becomes explicit.
- INTERNAL treatment cannot be manufactured through client input.
- Workload compromise has a more limited blast radius.
- Material financial mutations become deterministic and auditable.
- Retries cannot casually duplicate financial effects.
- Reconciliation remains powerful but controlled.
- Provider credentials remain isolated.
- Payment-sensitive data remains outside subscriptions.
- Security aligns with separation-of-duties architecture.
- Investigations can reconstruct actor, workload, tenant and causal chain.

## Negative

- Service-to-service authorization becomes more sophisticated.
- Idempotency persistence is required.
- Audit persistence becomes part of transaction design.
- Privileged reconciliation requires separate authorization paths.
- Testing must include security and tenant-isolation scenarios.
- Operators cannot rely on convenient direct database edits as normal repair tooling.

These costs are accepted because subscription billing is a financially sensitive multi-tenant domain.

---

# 100. Implementation requirements

An implementation conforming to this ADR SHALL provide:

1. authenticated workload identity;
2. operation-specific authorization;
3. trusted tenant context;
4. tenant-scoped resource access;
5. fail-closed cross-tenant behaviour;
6. explicit controlled domain commands;
7. protection against mass assignment;
8. durable idempotency;
9. idempotency request fingerprinting;
10. concurrency/revision protection;
11. event producer authentication;
12. event deduplication;
13. provider callback verification;
14. provider credential isolation;
15. payment-data separation;
16. durable audit evidence;
17. actor/workload provenance where available;
18. privileged reconciliation authorization;
19. read-versus-repair reconciliation separation;
20. protected provider administration;
21. sanitised logs and errors;
22. security observability;
23. production secret-management integration;
24. production-safe configuration;
25. automated security and tenant-isolation tests.

---

# 101. Recommended request pipeline

A protected mutation SHOULD conceptually execute:

```text
Incoming Request
       │
       ▼
Authentication
       │
       ▼
Principal Construction
       │
       ▼
Trusted Context Resolution
       │
       ▼
Tenant Boundary Validation
       │
       ▼
Operation Authorization
       │
       ▼
Domain Authority Validation
       │
       ▼
Idempotency Validation
       │
       ▼
Revision / Concurrency Check
       │
       ▼
Domain Command
       │
       ▼
Local Transaction
   ┌───┼────────────┐
   ▼   ▼            ▼
State Audit       Outbox
   └───┴────────────┘
       │
       ▼
Provider / downstream work
       │
       ▼
Deterministic response
```

---

# 102. Recommended security architecture

```text
                           ┌─────────────────┐
                           │   Baobab IAM    │
                           │ Identity/AuthN  │
                           └────────┬────────┘
                                    │
                                    ▼
┌──────────────────┐       ┌──────────────────┐
│ Digital Estates  │──────►│   Baobab APIs    │
└──────────────────┘       └────────┬─────────┘
                                    │
                                    ▼
                           ┌──────────────────┐
                           │    baobab-cp     │
                           │ Domain Authority │
                           └────────┬─────────┘
                                    │
                           workload identity
                                    │
                                    ▼
                    ┌───────────────────────────┐
                    │   baobab-subscriptions    │
                    │                           │
                    │ AuthN                     │
                    │ Trusted Context           │
                    │ Authorization             │
                    │ Controlled Mutation       │
                    │ Audit                     │
                    │ Idempotency               │
                    └──────────┬───────┬────────┘
                               │       │
                               │       │ workload identity
                               │       ▼
                               │ ┌──────────────────┐
                               │ │ baobab-payments │
                               │ └──────────────────┘
                               │
                               │ isolated provider
                               │ credentials
                               ▼
                         ┌───────────┐
                         │ Kill Bill │
                         └───────────┘
```

---

# 103. Implementation sequencing

Recommended implementation order:

```text
1. SecurityContext / trusted context boundary
                 │
                 ▼
2. Workload authentication integration
                 │
                 ▼
3. Operation-level authorization
                 │
                 ▼
4. Tenant-scoped repository enforcement
                 │
                 ▼
5. Controlled command handlers
                 │
                 ▼
6. Durable idempotency
                 │
                 ▼
7. Revision/concurrency controls
                 │
                 ▼
8. Audit persistence
                 │
                 ▼
9. Provider credential isolation
                 │
                 ▼
10. Reconciliation privilege separation
                 │
                 ▼
11. Security observability
                 │
                 ▼
12. Adversarial/integration testing
```

Security SHALL be implemented as part of the application architecture rather than added after the provider integration is complete.

---

# 104. Production security gate

`baobab-subscriptions` SHALL NOT be considered production-ready until the following are verified:

| Control | Production requirement |
|---|---|
| Workload identity | Production mechanism operational |
| Static service secrets | Not normal production identity mechanism |
| Audience validation | Enforced where applicable |
| Tenant context | Trusted and validated |
| Tenant isolation | Integration tested |
| Cross-tenant access | Fails closed |
| Authorization | Operation-specific |
| INTERNAL protection | Client cannot manufacture INTERNAL treatment |
| Controlled mutation | Generic protected-field mutation unavailable |
| Idempotency | Durable and tested |
| Concurrency | Conflicting mutations controlled |
| Audit | Durable for material operations |
| Actor/workload provenance | Preserved where available |
| Provider credentials | Secret-managed and isolated |
| Payment credentials | Not stored by subscriptions |
| Provider callbacks | Verified and deduplicated |
| Reconciliation | Read and repair authority separated |
| Administrative repair | Controlled and audited |
| Logging | Secrets/sensitive fields redacted |
| Observability | Security signals available |
| Tests | Authentication, authorization and tenant-isolation suites passing |
| Kill Bill access | Restricted to approved integration boundary |

---

# 105. Relationship to the preceding ADRs

Together, the selected ADR set now forms a coherent architecture:

```text
ADR-SUB-0001
Adopt Kill Bill
       │
       ▼
ADR-SUB-0002
Domain Model and Authority
       │
       ▼
ADR-SUB-0003
Lifecycle, Synchronisation
and Reconciliation
       │
       ▼
ADR-SUB-0006
Classification-Driven
Billing Policy
       │
       ▼
ADR-SUB-0015
BillingProvider Port and
Kill Bill Adapter
       │
       ▼
ADR-SUB-0016
Security, Identity, Audit
and Controlled Mutation
```

The responsibilities now resolve as:

| Domain | Authority |
|---|---|
| Identity/authentication | `baobab-iam` |
| Organisation/tenant/PlatformAccount | `baobab-cp` |
| ProductSubscription | `baobab-cp` |
| Classification | `baobab-cp` |
| INTERNAL eligibility | `baobab-cp` |
| Capability/entitlement | `baobab-cp` |
| Billing projection | `baobab-subscriptions` |
| Billing policy execution | `baobab-subscriptions` |
| Usage/rating | `baobab-subscriptions` |
| Billing-provider integration | `baobab-subscriptions` |
| Provider implementation | Kill Bill |
| Payment orchestration | `baobab-payments` |
| Payment provider abstraction | HyperSwitch behind Payments |
| Accounting/ledger | `baobab-erp` |

---

# 106. Final decision

Baobab SHALL secure subscription billing through **authenticated identity, trusted context, explicit authorization, domain authority, controlled mutation, idempotency and durable audit**.

The governing security model is:

```text
IDENTITY
   │
   ▼
AUTHENTICATION
   │
   ▼
TRUSTED CONTEXT
   │
   ▼
AUTHORIZATION
   │
   ▼
DOMAIN AUTHORITY
   │
   ▼
CONTROLLED MUTATION
   │
   ▼
IDEMPOTENT EXECUTION
   │
   ▼
AUDITABLE RESULT
```

The central identity rule is:

> **A valid identity proves who or what the caller is; it does not prove that the caller owns the requested business decision.**

The central tenancy rule is:

> **Tenant identity is trusted context, not arbitrary request data. Every tenant-scoped resource operation must be constrained by that trusted context, and cross-tenant ambiguity fails closed.**

The central financial rule is:

> **No caller may manufacture INTERNAL treatment, arbitrary monetary amounts, provider state or protected lifecycle state through untrusted input. Financial state is the consequence of authoritative context, canonical policy and validated domain commands.**

The central mutation rule is:

> **Material billing changes are commands, not arbitrary CRUD. They are authorised, tenant-scoped, revision-aware, idempotent and auditable.**

The central operational rule is:

> **Reconciliation and administrative repair are privileged capabilities. Detection does not imply repair authority, and repair does not imply authority to rewrite upstream Control Plane truth.**

And the final trust-boundary rule is:

> **IAM proves identity; Control Plane establishes subscription authority; Subscriptions establishes billing consequences; Kill Bill executes provider operations; Payments moves money; ERP accounts for the financial result. No credential, role, provider object or internal-network position is permitted to collapse those boundaries.**