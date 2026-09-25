# ADR-SUB-0011 — Billing Account, Payer, Invoice Recipient and Financial Responsibility Model

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Billing Accounts / Financial Responsibility  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None

## Related Decisions

- ADR-SUB-0001 — Adopt Kill Bill as the Foundational Headless Baobab Subscription Billing Engine
- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-SUB-0004 — Usage Metering, Rating, Aggregation and Billable Consumption Model
- ADR-SUB-0005 — Subscription Catalogue, Pricing, Plan Versioning and Billing Terms Resolution
- ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0015 — Kill Bill Adapter, Billing Provider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-017 — Organisation Admission, Subscription Classification and Tenant Onboarding Lifecycle Model
- ADR-BCP-018 — Canonical Organisation and Tenant Relationships
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- `shared/contracts/subscriptions/v1`
- `shared/contracts/payments/v1`

---

# 1. Context

Baobab is a multi-tenant platform capable of serving both internal group companies and unrelated external customers.

The organisational structures using Baobab may be simple:

```text
Organisation
    │
    └── Tenant
```

or considerably more complex:

```text
Customer Group
     │
     ├── Subsidiary A
     │      └── Tenant A
     │
     ├── Subsidiary B
     │      └── Tenant B
     │
     └── Subsidiary C
            └── Tenant C
```

Commercial responsibility does not necessarily follow the same structure as operational tenancy.

For example:

```text
Subsidiary A
    consumes service

Parent Company
    receives consolidated invoice

Shared Services Company
    pays invoice
```

Similarly, a Baobab ProductSubscription may belong operationally to one tenant while another authorised entity carries financial responsibility.

Therefore:

```text
consumer
    != necessarily
subscriber
    != necessarily
tenant
    != necessarily
legal entity
    != necessarily
PlatformAccount
    != necessarily
invoice recipient
    != necessarily
payer
```

Collapsing these concepts would create serious problems for:

- corporate groups;
- subsidiaries;
- central procurement;
- shared-service organisations;
- delegated payment;
- consolidated billing;
- external customer groups;
- cross-market billing;
- future reseller or partner arrangements.

At the same time, billing flexibility must never weaken tenant isolation.

A parent organisation paying several subsidiaries' invoices does not become entitled to those subsidiaries' operational data merely because it pays their bills.

Baobab therefore requires an explicit model of **financial responsibility** independent of organisational identity and subscription consumption.

---

# 2. Decision

Baobab SHALL introduce a first-class `BillingAccountProjection` within `baobab-subscriptions`.

The BillingAccountProjection SHALL represent the subscriptions engine's operational view of an authorised billing relationship.

It SHALL NOT redefine:

- Organisation;
- Tenant;
- LegalEntity;
- PlatformAccount;
- ProductSubscription;
- organisational hierarchy;
- entitlement;
- IAM identity.

The conceptual relationship is:

```text
CONTROL PLANE
────────────────────────────

Organisation
Tenant
Legal Entity
PlatformAccount
ProductSubscription
Corporate Relationships

             │
             │ authoritative references
             ▼

BAOBAB-SUBSCRIPTIONS
────────────────────────────

BillingAccountProjection
        │
        ├── Financial Responsibility
        ├── Payer Arrangement
        ├── Invoice Recipient
        ├── Billing Preferences
        └── BillingSubscriptionProjection[]
```

The BillingAccountProjection is therefore a **financial projection**, not a new organisational authority.

---

# 3. Fundamental distinction

Baobab SHALL distinguish:

```text
Organisation
     !=
Tenant
     !=
LegalEntity
     !=
PlatformAccount
     !=
ProductSubscription
     !=
BillingAccountProjection
     !=
Payer
     !=
InvoiceRecipient
```

Relationships between them SHALL be explicit.

---

# 4. Authority model

| Concept | Authority |
|---|---|
| Organisation | Control Plane |
| Organisation relationship | Control Plane |
| Tenant | Control Plane |
| Legal entity | Control Plane |
| PlatformAccount | Control Plane |
| ProductSubscription | Control Plane |
| Subscription classification | Control Plane |
| CapabilityGrant | Control Plane |
| BillingSubscriptionProjection | Subscriptions |
| BillingAccountProjection | Subscriptions |
| Payer arrangement projection | Subscriptions |
| Invoice recipient projection | Subscriptions |
| Charges | Subscriptions |
| InvoiceProjection | Subscriptions |
| FinancialObligation | Subscriptions |
| Payment execution | `baobab-payments` |
| Accounting consequence | `baobab-erp` |

---

# 5. BillingAccountProjection

Conceptually:

```text
BillingAccountProjection
────────────────────────────────

billing_account_id

platform_account_reference

financial_responsibility_type

billing_currency

billing_market_reference

invoice_recipient_reference

payer_reference

payment_terms_reference

billing_contact_projection

tax_profile_reference

status

effective_from
effective_until

source_revision

created_at
updated_at
```

The exact schema MAY evolve.

Its semantic role SHALL remain stable.

---

# 6. BillingAccountProjection is not identity

A BillingAccountProjection SHALL NOT become the authoritative representation of a customer or organisation.

For example:

```text
billing_account_id = BA-123
```

does not mean:

```text
tenant_id = BA-123
```

or:

```text
platform_account_id = BA-123
```

Canonical identities SHALL remain distinct.

---

# 7. PlatformAccount relationship

A BillingAccountProjection MAY reference a PlatformAccount.

That reference exists to connect billing responsibility to authoritative platform context.

Subscriptions SHALL NOT copy the PlatformAccount domain into its own database and then treat the copy as authoritative identity.

---

# 8. Billing account cardinality

Baobab SHALL NOT assume a universal:

```text
1 tenant = 1 billing account
```

The architecture SHALL permit governed relationships such as:

```text
one billing account
      │
      ├── subscription A
      ├── subscription B
      └── subscription C
```

and, where policy requires:

```text
one organisation
      │
      ├── billing account ZA
      └── billing account UG
```

without redefining the organisation.

---

# 9. Consumer

The **consumer** is the operational context consuming the subscribed capability.

For example:

```text
Tenant A
   │
   ▼
uses Baobab Trade
```

Tenant A is the consumer.

It need not be the payer.

---

# 10. Subscriber

The subscriber is the authoritative ProductSubscription context recognised by Control Plane.

Subscriptions SHALL not redefine subscriber identity merely because billing is delegated elsewhere.

---

# 11. Financially responsible party

The financially responsible party is the authorised entity/account responsible for satisfying a commercial billing obligation.

It SHALL be represented explicitly.

The engine SHALL NOT infer financial responsibility merely from:

- corporate parentage;
- common ownership;
- similar names;
- tenant hierarchy;
- shared directors;
- shared PlatformAccount metadata.

---

# 12. Payer

A payer is the party/account authorised to execute or fund payment.

Conceptually:

```text
Financial Obligation
       │
       ▼
Payer
       │
       ▼
baobab-payments
```

The payer may coincide with the consuming legal entity.

It need not.

---

# 13. Invoice recipient

The invoice recipient is the party/contact to whom billing documentation is directed.

It MAY differ from the payer.

Example:

```text
Invoice Recipient
    Accounts Payable Department

Payer
    Corporate Treasury
```

The architecture SHALL support this distinction.

---

# 14. Billing contact

A billing contact is a communication destination.

It SHALL NOT automatically become:

- payer;
- financial authority;
- administrator;
- tenant member;
- IAM principal with billing mutation privileges.

Communication authority and financial authority are distinct.

---

# 15. Financial responsibility relationship

Financial responsibility SHALL be explicit and effective-dated.

Conceptually:

```text
FinancialResponsibility
────────────────────────────

relationship_id

billing_account_id

responsible_party_reference

scope

effective_from
effective_until

authority_reference

status
```

---

# 16. Scope

A financial-responsibility relationship MAY apply to:

```text
specific ProductSubscription

set of authorised subscriptions

billing account

authorised group billing arrangement
```

The scope SHALL be explicit.

---

# 17. No financial responsibility by inference

The following is prohibited:

```text
Organisation A
    owns
Organisation B

therefore:

Organisation A automatically pays B
```

Corporate relationship alone does not establish billing authority.

---

# 18. Group billing

Baobab SHALL support authorised group billing.

For example:

```text
Acme Group
    │
    ├── Acme Kenya
    │      └── Subscription K
    │
    ├── Acme Uganda
    │      └── Subscription U
    │
    └── Acme South Africa
           └── Subscription S
```

may legitimately have:

```text
Acme Group Billing Account
        │
        ├── Subscription K
        ├── Subscription U
        └── Subscription S
```

if an authoritative commercial arrangement permits it.

---

# 19. Group billing does not merge tenancy

Even under consolidated billing:

```text
Tenant K
Tenant U
Tenant S
```

remain independent tenant contexts.

Therefore:

```text
shared payer
    !=
shared tenant
```

and:

```text
consolidated invoice
    !=
consolidated authorization domain
```

---

# 20. External customer groups

The architecture SHALL work identically for external customers.

Baobab SHALL NOT encode special billing semantics based on whether an organisation belongs to Nabhold Group Africa.

For example:

```text
External Customer Group
       │
       ├── Subsidiary X
       └── Subsidiary Y
```

may use the same governed billing-account mechanisms as any other authorised organisational structure.

---

# 21. Internal group companies

Nabhold-owned subsidiaries MAY use central billing arrangements where explicitly configured.

However:

```text
Nabhold ownership
```

does not itself grant billing aggregation or cross-tenant visibility.

The same authority rules apply.

---

# 22. Classification remains subscription-scoped

ADR-SUB-0006 establishes that classification belongs to the ProductSubscription.

Therefore:

```text
BillingAccount = commercial payer
```

does not imply every attached subscription is COMMERCIAL.

Likewise:

```text
BillingAccount used internally
```

does not imply every subscription is INTERNAL.

---

# 23. Mixed classification

A BillingAccountProjection MAY reference subscriptions with different classifications where policy permits.

Example:

```text
Billing Account
    │
    ├── Subscription A — INTERNAL
    └── Subscription B — COMMERCIAL
```

The billing consequences remain subscription-specific.

---

# 24. INTERNAL protection

Attaching an INTERNAL ProductSubscription to a BillingAccountProjection SHALL NOT make it payable.

The classification barrier remains:

```text
INTERNAL ProductSubscription
          │
          ▼
Billing projection
          │
          ▼
usage / reporting
          │
          X
          ▼
collectible obligation
```

---

# 25. COMMERCIAL responsibility

For a COMMERCIAL ProductSubscription, subscriptions SHALL be able to determine:

```text
Who consumes?
Who subscribed?
Who is financially responsible?
Who receives the invoice?
Who pays?
```

before creating a collectible financial obligation.

---

# 26. Missing payer configuration

Where commercial policy requires a payer and no valid payer can be resolved, the subscription SHALL NOT invent one.

The appropriate outcome SHALL be equivalent to:

```text
PENDING_CONFIGURATION

blocker:
PAYER_CONFIGURATION_MISSING
```

---

# 27. Ambiguous payer

Where multiple candidate payers exist without deterministic precedence:

```text
Payer A?
Payer B?
```

the system SHALL fail closed.

It SHALL NOT choose arbitrarily.

---

# 28. Billing account resolution

Conceptually:

```text
ProductSubscription
        │
        ▼
BillingSubscriptionProjection
        │
        ▼
Resolve Financial Responsibility
        │
        ▼
Resolve BillingAccountProjection
        │
        ▼
Resolve Invoice Recipient
        │
        ▼
Resolve Payer
        │
        ▼
Validate Currency / Market / Terms
        │
        ▼
Billing Ready
```

---

# 29. Resolution determinism

Given identical authoritative context and effective time, billing-account resolution SHALL be deterministic.

---

# 30. Effective dating

Payer and financial-responsibility relationships SHALL be effective-dated.

Example:

```text
Jan–Jun
Subsidiary pays directly

Jul onward
Parent company pays centrally
```

Historical invoices SHALL remain associated with the financial arrangement applicable to their billing period.

---

# 31. Historical preservation

A payer change SHALL NOT rewrite historical invoices.

For example:

```text
Invoice March
    payer = Subsidiary

Invoice August
    payer = Parent
```

Changing the current payer to Parent SHALL NOT alter March's historical billing provenance.

---

# 32. Payer transition

A payer transition SHALL be explicit.

Conceptually:

```text
Current Payer
      │
      ▼
Controlled Change
      │
      ├── effective_at
      ├── authority
      ├── reason
      └── audit
      │
      ▼
New Payer
```

---

# 33. Existing obligations

Changing payer SHALL NOT silently transfer already-established debt to another party.

Existing obligations retain the financial responsibility under which they were created unless an explicit governed reassignment process exists.

---

# 34. Obligation reassignment

Where commercial/legal policy permits reassignment of an existing obligation, it SHALL be a first-class controlled operation.

It SHALL preserve:

```text
original payer
new payer
reason
authority
effective time
affected obligation
audit trail
```

No silent foreign-key update is permitted.

---

# 35. Billing account and currency

A BillingAccountProjection MAY have a preferred or contractual billing currency.

However, that preference SHALL NOT override authoritative PricingPlan/BillingTerms rules without policy.

Currency resolution remains governed by ADR-SUB-0005.

---

# 36. Multi-currency billing accounts

A single organisation MAY require multiple BillingAccountProjections where commercial arrangements differ materially.

For example:

```text
Organisation
    │
    ├── ZA Billing Account
    │      ZAR
    │
    └── UG Billing Account
           UGX
```

This is preferable to hiding incompatible financial contexts inside one ambiguous account.

---

# 37. Market context

Billing market and service-consumption market MAY differ.

For example:

```text
Service consumed:
    Uganda

Contracting/payer entity:
    South Africa
```

The platform SHALL preserve these contexts separately where relevant.

---

# 38. Billing address

Billing address is billing-document information.

It SHALL not become canonical organisation identity.

Historical issued invoices SHOULD preserve the applicable billing-address snapshot/reference necessary for reproduction.

---

# 39. Tax identity

A payer or invoice recipient MAY require:

- tax registration identifier;
- VAT number;
- other jurisdiction-specific billing identifiers.

Subscriptions SHALL reference authoritative tax/legal identity information where available rather than becoming the organisation master.

---

# 40. Snapshotting

Where invoice reproducibility requires historical details, subscriptions MAY retain immutable snapshots of:

- legal name;
- billing address;
- tax identifier;
- invoice recipient details.

A snapshot records what was used for billing.

It does not become the current canonical identity.

---

# 41. Snapshot provenance

A snapshot SHOULD record:

```text
source reference
source revision/version
captured_at
```

where supported.

---

# 42. Billing account lifecycle

A BillingAccountProjection SHOULD support semantic states equivalent to:

```text
PENDING_CONFIGURATION
        │
        ▼
ACTIVE
        │
        ▼
SUSPENDED
        │
        ▼
CLOSED
```

Operational/reconciliation conditions SHOULD remain separate where practical.

---

# 43. PENDING_CONFIGURATION

A BillingAccountProjection SHALL remain `PENDING_CONFIGURATION` where required information is missing.

Examples:

```text
PAYER_MISSING
INVOICE_RECIPIENT_MISSING
BILLING_CURRENCY_MISSING
FINANCIAL_RESPONSIBILITY_UNRESOLVED
PAYMENT_TERMS_MISSING
TAX_CONFIGURATION_INCOMPLETE
```

as applicable.

---

# 44. ACTIVE

`ACTIVE` means the billing account is sufficiently configured for the billing operations allowed by its policy.

It does not imply:

- payment method exists;
- payment has succeeded;
- every subscription is commercially billable.

---

# 45. SUSPENDED

Suspending a BillingAccountProjection SHALL not erase:

- historical invoices;
- charges;
- obligations;
- payer history;
- audit evidence.

Suspension effects on new billing SHALL be policy-driven.

---

# 46. CLOSED

Closing a billing account prevents inappropriate future billing association.

It SHALL NOT delete financial history.

---

# 47. Payment method boundary

Subscriptions SHALL NOT store raw payment credentials.

The architecture is:

```text
BillingAccountProjection
       │
       │ payment reference/context
       ▼
baobab-payments
       │
       ▼
HyperSwitch
       │
       ▼
Payment Provider
```

Sensitive payment-instrument handling remains outside subscriptions.

---

# 48. Payment method reference

Subscriptions MAY retain an opaque canonical reference indicating the payment arrangement required for billing.

It SHALL NOT store:

- raw PAN;
- CVV;
- private banking credentials;
- processor secrets.

---

# 49. Payment terms

Billing accounts MAY reference terms such as:

```text
due on receipt
Net 7
Net 30
contract-specific
```

These SHALL be explicit commercial terms.

They SHALL not be inferred from payment-provider behaviour.

---

# 50. Automatic collection

A billing account MAY be configured for automatic collection where commercial and payment policy permit.

The flow remains:

```text
InvoiceProjection
       │
       ▼
FinancialObligation
       │
       ▼
baobab-payments
       │
       ▼
authorised payment execution
```

Subscriptions SHALL NOT directly charge the underlying processor.

---

# 51. Manual settlement

A billing account MAY support non-automatic settlement such as governed bank-transfer workflows.

This does not change invoice authority.

Payment confirmation/reconciliation remains within the appropriate payment/financial boundary.

---

# 52. Billing account versus Kill Bill account

The following distinction is mandatory:

```text
Baobab BillingAccountProjection
             !=
Kill Bill Account
```

A Kill Bill account is a provider representation.

---

# 53. Provider projection

Where provider participation is required:

```text
BillingAccountProjection
          │
          ▼
BillingProvider
          │
          ▼
KillBillAdapter
          │
          ▼
Kill Bill Account
```

The direction of authority SHALL not reverse.

---

# 54. Provider account identity

Kill Bill account identifiers SHALL be stored as provider references.

They SHALL NOT replace:

```text
billing_account_id
platform_account_id
tenant_id
legal_entity_id
```

---

# 55. Provider mapping

Conceptually:

```text
ProviderAccountMapping
────────────────────────────

billing_account_id

provider

provider_account_id
provider_external_key

mapping_status

created_at
verified_at
```

Mappings SHALL be unique and auditable.

---

# 56. Provider account duplication

If an ambiguous provider outcome results in potentially duplicate Kill Bill accounts, subscriptions SHALL reconcile before retrying creation blindly.

The adapter SHALL use ADR-SUB-0015 idempotency semantics.

---

# 57. Provider drift

Reconciliation SHALL detect conditions including:

| Condition | Meaning |
|---|---|
| Missing provider account | Required Kill Bill projection absent |
| Duplicate provider account | Multiple provider accounts mapped ambiguously |
| Wrong mapping | Provider account mapped to wrong Baobab billing account |
| Currency drift | Provider context conflicts with resolved billing terms |
| Stale payer information | Provider projection differs from expected state |
| Orphan provider account | Provider resource lacks legitimate Baobab projection |

---

# 58. Provider is not organisation authority

Provider account metadata SHALL never be used to establish:

- tenant ownership;
- legal-entity relationship;
- organisation hierarchy;
- INTERNAL eligibility;
- entitlement.

Those facts remain upstream authorities.

---

# 59. Invoice grouping

ADR-SUB-0012 MAY group charges into an InvoiceProjection using the BillingAccountProjection.

Conceptually:

```text
BillingSubscription A ─┐
BillingSubscription B ─┼──► BillingAccount
BillingSubscription C ─┘          │
                                  ▼
                         InvoiceProjection
```

only where the billing relationship explicitly permits such grouping.

---

# 60. Consolidated invoice

A consolidated invoice MAY contain charges arising from multiple authorised subscriptions.

Each line SHALL preserve:

- source subscription;
- source tenant where applicable;
- charge identity;
- pricing provenance;
- billing period.

Consolidation SHALL not destroy lineage.

---

# 61. Cross-tenant privacy

A consolidated payer view SHALL expose only the billing information authorised by the payer arrangement.

Financial responsibility SHALL NOT automatically grant access to:

- operational records;
- customer data;
- supplier data;
- trade documents;
- private tenant configuration;
- capability administration.

---

# 62. Billing authorization scope

Baobab SHALL distinguish permissions such as:

```text
VIEW_BILLING_ACCOUNT
VIEW_INVOICES
VIEW_USAGE_BILLING
MANAGE_BILLING_CONTACTS
MANAGE_PAYMENT_ARRANGEMENT
APPROVE_PAYER_CHANGE
ISSUE_CREDIT
```

or equivalent canonical authorities.

Possession of one SHALL not imply possession of all.

---

# 63. Separation of duties

High-risk operations SHOULD enforce separation of duties where appropriate.

For example:

```text
Actor A
    proposes payer reassignment

Actor B
    approves payer reassignment
```

for material financial relationships.

The detailed authorization mechanism is governed by ADR-SUB-0016 and platform IAM decisions.

---

# 64. No arbitrary payer PATCH

The platform SHALL NOT expose unrestricted operations equivalent to:

```text
PATCH /billing-account

{
  "payer": "some-other-company"
}
```

without domain validation and authority.

Payer changes SHALL use controlled commands.

---

# 65. Controlled commands

Examples may include:

```text
AssignPayer
ChangePayer
AssignInvoiceRecipient
ChangeBillingContact
ActivateBillingAccount
SuspendBillingAccount
CloseBillingAccount
ReconcileBillingAccount
```

Exact API names may vary.

The domain semantics SHALL remain explicit.

---

# 66. Idempotency

Every material mutation SHALL support stable idempotency.

For example:

```text
ChangePayer
Idempotency-Key: XYZ
```

replayed multiple times SHALL not create multiple payer-transition records.

---

# 67. Revision safety

Mutations affecting financial responsibility SHOULD carry expected revision/version where appropriate.

This prevents stale administrative operations from overwriting newer decisions.

---

# 68. Event model

Subscriptions MAY emit canonical events equivalent to:

```text
billing.account.created
billing.account.activated
billing.account.suspended
billing.account.closed

billing.payer.assigned
billing.payer.changed

billing.invoice-recipient.changed
```

Exact names and schemas SHALL be governed through Shared.

---

# 69. Events do not transfer authority

A `billing.payer.changed` event describes a subscriptions-domain fact.

It does not redefine the authoritative Organisation, Tenant or PlatformAccount.

---

# 70. Transactional outbox

Material billing-account state transitions SHALL use the transactional outbox pattern where events must be emitted.

```text
BEGIN

validate command

persist state

persist audit

persist outbox event

COMMIT
```

External systems consume asynchronously.

---

# 71. Reconciliation

Billing-account reconciliation SHALL compare:

```text
Authoritative platform context
          │
          ▼
BillingAccountProjection
          │
          ▼
Provider account projection
          │
          ▼
Payment configuration/reference
```

without allowing downstream state to manufacture upstream authority.

---

# 72. Reconciliation outcomes

Reconciliation SHOULD distinguish equivalent outcomes:

```text
IN_SYNC

REPAIRABLE

BLOCKED

MANUAL_REVIEW_REQUIRED
```

---

# 73. Auto-repair

Automatic repair MAY occur where:

- authoritative source is unambiguous;
- repair is idempotent;
- no financial responsibility is transferred;
- no cross-tenant ambiguity exists;
- operation is reversible or safely repeatable.

---

# 74. Manual review

Manual review SHALL be required for conditions such as:

- ambiguous payer;
- cross-tenant mapping;
- conflicting financial responsibility;
- duplicate provider accounts with monetary history;
- historical obligation reassignment ambiguity;
- inconsistent legal/tax identity;
- unexplained currency conflict.

---

# 75. No downstream authority reconstruction

If subscriptions discovers a Kill Bill account with payer metadata, it SHALL NOT create a Control Plane organisation or legal relationship from that information.

Provider state cannot manufacture canonical organisation authority.

---

# 76. Security

ADR-SUB-0016 governs security.

Billing-account operations SHALL require:

```text
authenticated workload/user
        +
authorised action
        +
trusted tenant/account context
        +
controlled mutation
        +
idempotency
        +
audit
```

---

# 77. Tenant isolation

A caller from Tenant A SHALL not gain access to Tenant B's billing account merely by knowing:

```text
billing_account_id
```

Cross-tenant identifiers SHOULD follow platform not-found semantics where appropriate.

---

# 78. Central payer access

A central payer MAY legitimately receive billing access across several tenant contexts.

Such access SHALL derive from explicit billing authority.

It SHALL not be implemented by weakening tenant isolation globally.

---

# 79. Least privilege

A payer user who can:

```text
VIEW_INVOICES
```

need not be allowed to:

```text
CHANGE_PAYER
```

or:

```text
ISSUE_CREDIT
```

Billing roles SHALL be least-privilege.

---

# 80. Audit requirements

Material billing-account audit evidence SHALL answer:

```text
Who performed the action?

Under what authority?

For which billing account?

Which subscriptions were affected?

What changed?

What was the previous state?

What is the new state?

When did it become effective?

What correlation/idempotency identity applies?
```

---

# 81. Data minimisation

BillingAccountProjection SHALL contain only information necessary for subscription billing.

It SHALL NOT become a shadow CRM containing arbitrary customer information.

---

# 82. Sensitive data

Sensitive financial/payment data SHALL remain in systems specifically authorised to hold it.

Subscriptions SHOULD retain opaque references wherever possible.

---

# 83. Availability

A temporary outage of:

- Control Plane;
- Payments;
- Kill Bill;

SHALL NOT corrupt the BillingAccountProjection.

Operations requiring unavailable authoritative information SHALL fail safely or remain pending.

---

# 84. Eventual consistency

Billing-account projections are distributed state.

Temporary lag is expected.

The architecture SHALL distinguish:

```text
temporarily stale
```

from:

```text
authoritatively invalid
```

Reconciliation restores convergence.

---

# 85. No distributed transaction

Baobab SHALL NOT require one ACID transaction across:

```text
Control Plane
Subscriptions
Kill Bill
Payments
ERP
```

Correctness SHALL rely on:

- local transactions;
- idempotency;
- outbox/inbox;
- stable references;
- retries;
- reconciliation;
- audit.

---

# 86. Billing-account deletion

Financially used BillingAccountProjections SHALL NOT normally be hard-deleted.

Closure and retention SHALL preserve historical billing provenance.

---

# 87. Right-to-erasure considerations

Privacy obligations SHALL be reconciled with statutory financial-record retention.

Where personal contact data may lawfully be removed or anonymised, doing so SHALL not destroy required financial provenance.

Detailed retention policy may be specified separately.

---

# 88. Observability

Subscriptions SHOULD expose telemetry for:

- active billing accounts;
- pending configuration;
- unresolved payer relationships;
- payer changes;
- failed payer resolution;
- provider-account drift;
- payment-reference failures;
- reconciliation backlog;
- cross-tenant access denials;
- privileged billing-account mutations.

Sensitive identities SHALL not become uncontrolled metric labels.

---

# 89. Billing readiness

Billing readiness SHALL be decomposed.

Conceptually:

```text
Subscription authorised?
        │
        ▼
Billing projection ready?
        │
        ▼
Pricing ready?
        │
        ▼
Billing account ready?
        │
        ▼
Payer resolved?
        │
        ▼
Provider ready where required?
        │
        ▼
Payment path ready where required?
```

A single generic `ready=true` SHALL not hide these distinctions.

---

# 90. Machine-readable blockers

Where applicable, blockers SHOULD use canonical equivalents of:

```text
BILLING_ACCOUNT_MISSING

BILLING_ACCOUNT_INACTIVE

FINANCIAL_RESPONSIBILITY_MISSING

FINANCIAL_RESPONSIBILITY_AMBIGUOUS

PAYER_MISSING

PAYER_AMBIGUOUS

INVOICE_RECIPIENT_MISSING

BILLING_CURRENCY_MISSING

PAYMENT_TERMS_MISSING

PAYMENT_PATH_NOT_READY

PROVIDER_ACCOUNT_NOT_READY

CROSS_TENANT_BILLING_AUTHORITY_MISSING
```

---

# 91. Domain invariants

### INV-ACC-01

BillingAccountProjection is not canonical organisation identity.

### INV-ACC-02

BillingAccountProjection is not Tenant.

### INV-ACC-03

BillingAccountProjection is not PlatformAccount.

### INV-ACC-04

Consumer and payer are not assumed identical.

### INV-ACC-05

Invoice recipient and payer are not assumed identical.

### INV-ACC-06

Corporate parentage does not automatically establish financial responsibility.

### INV-ACC-07

Group billing requires explicit authority.

### INV-ACC-08

Shared payer does not merge tenants.

### INV-ACC-09

A payer arrangement does not create entitlement.

### INV-ACC-10

Billing-account configuration does not determine subscription classification.

### INV-ACC-11

INTERNAL subscriptions cannot become payable merely by attachment to a billing account.

### INV-ACC-12

Missing payer configuration does not result in an invented payer.

### INV-ACC-13

Ambiguous payer resolution fails closed.

### INV-ACC-14

Payer relationships are effective-dated.

### INV-ACC-15

Changing the current payer does not rewrite historical invoices.

### INV-ACC-16

Existing obligations are not silently reassigned.

### INV-ACC-17

Kill Bill account identity never replaces canonical Baobab billing-account identity.

### INV-ACC-18

Provider metadata cannot establish organisation or tenant authority.

### INV-ACC-19

Subscriptions does not store raw payment credentials.

### INV-ACC-20

Cross-tenant billing authority does not imply cross-tenant operational access.

### INV-ACC-21

Financially material billing-account mutations are controlled and auditable.

### INV-ACC-22

Billing-account lifecycle history survives closure.

### INV-ACC-23

Reconciliation cannot manufacture upstream authority.

### INV-ACC-24

Billing readiness exposes precise blockers rather than false readiness.

---

# 92. Alternatives considered

## 92.1 Use Tenant as the billing account

**Rejected.**

Tenant is an isolation/operational concept, not necessarily the payer.

---

## 92.2 Use PlatformAccount directly as the billing account

**Rejected.**

It would collapse platform identity and billing projection authority.

---

## 92.3 Assume the consuming legal entity always pays

**Rejected.**

This fails for group billing, central procurement and delegated payment.

---

## 92.4 Infer payer from corporate parentage

**Rejected.**

Ownership does not establish financial responsibility.

---

## 92.5 Use Kill Bill Account as canonical billing identity

**Rejected.**

This would make Baobab billing provider-dependent.

---

## 92.6 Store payment credentials in subscriptions

**Rejected.**

Payment-instrument custody belongs to the payment architecture.

---

## 92.7 Merge tenants for consolidated billing

**Rejected.**

Billing aggregation is not a tenancy relationship.

---

## 92.8 Update payer in place without history

**Rejected.**

Historical financial responsibility must remain explainable.

---

## 92.9 Automatically move historical debt when payer changes

**Rejected.**

Existing obligations require explicit reassignment semantics.

---

# 93. Consequences

## Positive

- Corporate-group billing becomes possible without compromising tenancy.
- External customers with subsidiaries are supported.
- Consumer, subscriber and payer relationships remain explicit.
- Central procurement and treasury models are supported.
- Historical financial responsibility remains reproducible.
- Kill Bill remains replaceable.
- Payment credentials remain outside subscriptions.
- Cross-market billing can evolve without redefining tenant identity.
- ADR-SUB-0012 receives a clear payer/account model before invoice generation.

## Negative

- Billing relationships require explicit configuration.
- Group billing requires additional authorization.
- Payer transitions require lifecycle management.
- Reconciliation spans Control Plane, subscriptions, provider and payments.
- Historical billing snapshots add persistence requirements.
- Cross-tenant payer access requires careful IAM design.

These costs are accepted because inferring financial responsibility from tenancy or corporate structure would create unacceptable financial and security ambiguity.

---

# 94. Implementation requirements

A conforming implementation SHALL provide:

1. canonical `billing_account_id`;
2. BillingAccountProjection persistence;
3. PlatformAccount/reference linkage without identity duplication;
4. explicit financial-responsibility relationships;
5. explicit payer relationships;
6. explicit invoice-recipient relationships;
7. effective dating;
8. historical payer preservation;
9. deterministic billing-account resolution;
10. group-billing support;
11. strict tenant isolation;
12. cross-tenant billing authorization;
13. classification-independent payer resolution;
14. INTERNAL monetary safeguards;
15. missing/ambiguous payer fail-closed behaviour;
16. payment-reference boundary;
17. no raw payment credentials;
18. Kill Bill account mapping;
19. provider reconciliation;
20. controlled mutations;
21. idempotency;
22. audit;
23. machine-readable readiness blockers;
24. automated tests covering the invariants in this ADR.

---

# 95. Required tests

At minimum:

### Identity boundaries

- BillingAccountProjection does not replace PlatformAccount;
- Tenant ID cannot be used as billing account implicitly;
- provider account ID cannot be used as canonical billing ID.

### Payer resolution

- consuming entity pays itself;
- authorised parent pays subsidiary;
- authorised shared-services entity pays;
- missing payer blocks;
- ambiguous payer blocks;
- unrelated organisation rejected.

### Group billing

- several subscriptions share one authorised billing account;
- tenant isolation remains intact;
- unauthorised cross-tenant consolidation rejected.

### Classification

- INTERNAL attached to billing account remains non-payable;
- COMMERCIAL resolves payer;
- mixed classification account preserves subscription-specific treatment.

### Temporal behaviour

- payer changes prospectively;
- old invoices retain old payer;
- existing obligations are not silently reassigned.

### Provider

- Kill Bill account created/mapped idempotently;
- duplicate provider account detected;
- missing mapping reconciled;
- wrong mapping blocks automatic unsafe repair.

### Security

- unauthorised payer change rejected;
- stale revision rejected;
- duplicate mutation idempotent;
- cross-tenant identifier probing does not expose foreign billing state;
- privileged action creates audit evidence.

---

# 96. Recommended implementation sequence

```text
1. BillingAccountProjection
          │
          ▼
2. FinancialResponsibility
          │
          ▼
3. Payer / InvoiceRecipient
          │
          ▼
4. Effective-dated relationships
          │
          ▼
5. Billing-account resolver
          │
          ▼
6. Tenant isolation
          │
          ▼
7. Group billing
          │
          ▼
8. Payment references
          │
          ▼
9. Kill Bill account mapping
          │
          ▼
10. Reconciliation
          │
          ▼
11. Controlled mutation + audit
          │
          ▼
12. ADR-SUB-0012 invoice integration
```

---

# 97. Relationship to ADR-SUB-0002

ADR-SUB-0002 establishes `BillingAccountProjection` as a subscriptions-owned aggregate/projection.

This ADR defines its semantics in detail.

The boundary remains:

```text
Control Plane
     │
     │ canonical identity/context
     ▼
BillingAccountProjection
     │
     │ financial relationship
     ▼
Subscription Billing
```

---

# 98. Relationship to ADR-SUB-0005

ADR-SUB-0005 determines:

> What commercial pricing and terms apply?

ADR-SUB-0011 determines:

> To which billing account and financial-responsibility arrangement do those terms apply?

Thus:

```text
PricingPlan
     │
     ▼
BillingTerms
     │
     ▼
BillingAccountProjection
     │
     ▼
Payer
```

Pricing authority and payer authority remain distinct.

---

# 99. Relationship to ADR-SUB-0006

ADR-SUB-0006 determines whether monetary treatment is permitted.

ADR-SUB-0011 determines who is financially responsible where monetary treatment requires a payer.

Therefore:

```text
Classification
     │
     ├── INTERNAL
     │      ▼
     │  zero payable
     │
     └── COMMERCIAL
            │
            ▼
       Billing Account
            │
            ▼
          Payer
```

A payer relationship can never override INTERNAL classification.

---

# 100. Relationship to ADR-SUB-0012

ADR-SUB-0011 answers:

```text
Who is financially responsible?
```

ADR-SUB-0012 answers:

```text
What financial obligation exists?
```

Together:

```text
BillingSubscriptionProjection
            │
            ▼
     BillingAccountProjection
            │
            ▼
           Payer
            │
            ▼
       Billing Cycle
            │
            ▼
     InvoiceProjection
            │
            ▼
   FinancialObligation
```

This ordering prevents invoice creation from having to infer payer identity.

---

# 101. Relationship to ADR-SUB-0015

ADR-SUB-0015 SHALL project the Baobab BillingAccountProjection into Kill Bill only where required.

```text
Baobab BillingAccountProjection
             │
             ▼
       BillingProvider
             │
             ▼
       KillBillAdapter
             │
             ▼
       Kill Bill Account
```

Provider state remains subordinate to Baobab billing authority.

---

# 102. Relationship to ADR-SUB-0016

Financial-responsibility changes are privileged operations.

ADR-SUB-0016 governs:

```text
payer assignment
payer change
invoice-recipient change
billing-account activation
billing-account suspension
billing-account closure
cross-tenant billing authority
provider mapping repair
```

Each SHALL be authenticated, authorised, idempotent where applicable, revision-safe and auditable.

---

# 103. Final Decision

Baobab SHALL treat **billing responsibility as an explicit financial relationship rather than an inferred property of tenancy, legal ownership or provider configuration**.

The canonical model is:

```text
ORGANISATION / TENANT / LEGAL ENTITY
                 │
                 │ authoritative context
                 ▼
         ProductSubscription
                 │
                 ▼
      BillingSubscriptionProjection
                 │
                 ▼
       BillingAccountProjection
                 │
          ┌──────┴──────┐
          ▼             ▼
   Invoice Recipient   Payer
          │             │
          └──────┬──────┘
                 ▼
         InvoiceProjection
                 │
                 ▼
       FinancialObligation
                 │
                 ▼
         baobab-payments
```

The principal identity rule is:

> **A billing account is a projection of financial responsibility, not a replacement for Organisation, Tenant, LegalEntity or PlatformAccount identity.**

The principal payer rule is:

> **Baobab never assumes that the consumer, subscriber, invoice recipient and payer are the same party. Their relationship must be explicit whenever the distinction matters.**

The principal group rule is:

> **Corporate ownership may explain why a billing arrangement exists, but it does not itself authorise that arrangement. Consolidated billing requires explicit financial authority and never merges tenant boundaries.**

The principal temporal rule is:

> **Financial responsibility is effective-dated. Changing who pays tomorrow does not rewrite who was responsible yesterday, nor does it silently transfer already-established obligations.**

The principal provider rule is:

> **A Kill Bill Account is a provider projection of a Baobab BillingAccountProjection. It is never the canonical customer, tenant, payer or billing identity.**

And the principal security rule is:

> **Financial responsibility may cross tenant boundaries only through explicit, least-privilege billing authority; it never creates general cross-tenant access or entitlement.**