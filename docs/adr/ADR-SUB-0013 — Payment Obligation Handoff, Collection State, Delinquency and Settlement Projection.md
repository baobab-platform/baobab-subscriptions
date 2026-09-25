# ADR-SUB-0013 — Payment Obligation Handoff, Collection State, Delinquency and Settlement Projection

**Status:** Accepted  
**Date:** 2026-09-25  
**Decision Type:** Architecture / Subscription Billing / Payment Boundary  
**Repository:** `baobab-platform/baobab-subscriptions`  
**Scope:** Baobab Platform  
**Owners:** Baobab Platform Architecture  
**Supersedes:** None

## Related Decisions

- ADR-SUB-0002 — Subscription Billing Domain Model, Aggregate Boundaries and Authority
- ADR-SUB-0003 — Control Plane to Billing Projection Lifecycle, Synchronisation and Reconciliation
- ADR-SUB-0006 — Classification-Driven Billing Policy and Monetary Treatment
- ADR-SUB-0007 — Subscription Commercial Lifecycle, Amendments, Renewal, Suspension and Termination
- ADR-SUB-0009 — Charge Calculation, Adjustments, Credits and Monetary Calculation Model
- ADR-SUB-0010 — Tax Jurisdiction, Tax Determination, Exemptions and Tax Calculation Boundary
- ADR-SUB-0011 — Billing Account, Payer, Invoice Recipient and Financial Responsibility Model
- ADR-SUB-0012 — Billing Cycles, Invoice Projection, Charges, Credits and Financial Obligation Lifecycle
- ADR-SUB-0015 — Kill Bill Adapter, BillingProvider Port and Provider Portability
- ADR-SUB-0016 — Security, Workload Identity, Audit and Controlled Mutation
- ADR-PAY-0001 — Adopt HyperSwitch
- ADR-SHARED-011 — Subscription Classification, Billing and Payment Contracts
- ADR-BCP-005 — Product, Capability Composition, Subscription, Entitlement and Digital Estate Provisioning Model
- ADR-BCP-020 — Separation of Duties
- ADR-BCP-021 — Controlled Mutation

---

# 1. Context

ADR-SUB-0012 establishes the boundary:

```text
Charges
   │
   ▼
InvoiceProjection
   │
   ▼
FinancialObligation
```

A `FinancialObligation` states that a monetary amount is due under an authorised billing relationship.

It does **not** mean that money has moved.

The next lifecycle crosses an engine boundary:

```text
baobab-subscriptions
        │
        │ FinancialObligation
        ▼
baobab-payments
        │
        ▼
HyperSwitch
        │
        ▼
Payment Provider / Rail
```

Several states can subsequently occur:

```text
authorised
captured
settled
partially paid
failed
declined
cancelled
refunded
partially refunded
disputed
```

Those states must not be collapsed into the subscription billing domain.

Conversely, `baobab-subscriptions` needs sufficient payment outcome information to determine the status of its own FinancialObligation and billing account.

The architecture must therefore distinguish:

```text
Billing obligation
Payment execution
Settlement
Delinquency
Entitlement
Accounting
```

These are related processes owned by different engines.

---

# 2. Decision

Baobab SHALL use an explicit asynchronous boundary between:

```text
baobab-subscriptions
```

and:

```text
baobab-payments
```

`baobab-subscriptions` SHALL own:

- FinancialObligation;
- payment requirement determination;
- amount due;
- currency due;
- due date;
- billing-account responsibility;
- obligation lifecycle projection;
- delinquency evaluation from billing facts and trusted payment outcomes.

`baobab-payments` SHALL own:

- payment orchestration;
- payment method interaction;
- payment routing;
- processor/provider selection;
- authorisation;
- capture;
- refund execution;
- payment retry execution;
- payment-provider interaction;
- HyperSwitch integration;
- payment transaction state.

Neither engine SHALL assume the other's authority.

---

# 3. Core separation

The following objects SHALL remain distinct:

```text
Charge
   !=
InvoiceProjection
   !=
FinancialObligation
   !=
PaymentAttempt
   !=
Payment
   !=
Settlement
   !=
ERP Receivable
```

---

# 4. Authority model

| Concern | Authority |
|---|---|
| ProductSubscription | Control Plane |
| Classification | Control Plane |
| Entitlement / CapabilityGrant | Control Plane |
| BillingAccountProjection | Subscriptions |
| InvoiceProjection | Subscriptions |
| FinancialObligation | Subscriptions |
| Amount due | Subscriptions |
| Payment execution | Payments |
| Processor routing | Payments |
| HyperSwitch interaction | Payments |
| Capture | Payments |
| Refund execution | Payments |
| Billing delinquency | Subscriptions |
| Entitlement consequence | Control Plane |
| Receivable accounting | ERP |
| Cash / settlement accounting | ERP |

No downstream engine may silently assume an upstream authority.

---

# 5. FinancialObligation

A FinancialObligation represents an amount that the billing domain has determined requires monetary settlement.

Conceptually:

```text
FinancialObligation
────────────────────────────

financial_obligation_id

billing_account_id
billing_subscription_id?

invoice_projection_id

payer_reference

currency

amount_due

amount_paid

amount_outstanding

due_at

payment_policy_reference

status

created_at
```

Exact implementation MAY evolve.

---

# 6. FinancialObligation is not PaymentIntent

A Baobab FinancialObligation SHALL NOT be defined in terms of a HyperSwitch or underlying payment-provider object.

Conceptually:

```text
FinancialObligation
       │
       ▼
Payment Request
       │
       ▼
baobab-payments
       │
       ▼
provider-specific intent
```

Provider replacement therefore does not alter subscription-domain identity.

---

# 7. Stable obligation identity

Every FinancialObligation SHALL have a stable Baobab identifier.

That identifier SHALL survive:

- payment retries;
- payment-provider changes;
- multiple payment attempts;
- partial payment;
- refund;
- reconciliation.

---

# 8. Obligation creation safety barrier

Immediately before creating a collectible FinancialObligation, subscriptions SHALL validate:

```text
classification
+
billing policy
+
invoice state
+
amount
+
currency
+
payer authority
+
payment requirement
```

---

# 9. INTERNAL prohibition

For an INTERNAL subscription:

```text
payment_required = false
```

and:

```text
payment_execution = prohibited
```

A FinancialObligation requiring external collection SHALL NOT be created merely because:

- a BillingAccountProjection exists;
- a payment method exists;
- Kill Bill generated an item;
- a price exists;
- HyperSwitch is available.

---

# 10. COMMERCIAL zero balance

A COMMERCIAL subscription may legitimately result in:

```text
amount_due = 0
```

because of:

- credits;
- allowances;
- trial;
- contractual zero price;
- zero usage.

A zero-value invoice does not require a payment transaction merely to prove completion.

---

# 11. PaymentRequest

When collection is required, subscriptions SHALL send a canonical payment request to `baobab-payments`.

Conceptually:

```text
PaymentRequest
────────────────────────

payment_request_id

financial_obligation_id

billing_account_reference

payer_reference

tenant_context

amount

currency

due_context

idempotency_reference

correlation_id
```

The normative cross-engine contract belongs in Shared.

---

# 12. Payment request contains intent, not routing

Subscriptions MAY state:

```text
collect ZAR 1,150
for obligation O-123
```

It SHALL NOT ordinarily state:

```text
use processor X
merchant account Y
payment rail Z
```

Routing belongs to `baobab-payments`.

---

# 13. Payment method ownership

Subscriptions SHALL NOT own:

- raw card numbers;
- bank credentials;
- payment tokens whose authority belongs to Payments;
- processor credentials;
- HyperSwitch secrets.

It MAY retain safe payment-reference identifiers where required.

---

# 14. PaymentAttempt

A PaymentAttempt belongs to the payment domain.

One FinancialObligation MAY produce several PaymentAttempts.

```text
FinancialObligation
        │
        ├── Attempt 1 → FAILED
        │
        ├── Attempt 2 → FAILED
        │
        └── Attempt 3 → SUCCEEDED
```

Subscriptions SHALL NOT create three obligations merely because three attempts occurred.

---

# 15. Payment retry identity

Retrying payment SHALL preserve:

```text
financial_obligation_id
```

while payment-attempt identity changes.

---

# 16. Idempotent handoff

FinancialObligation handoff SHALL be idempotent.

Repeated delivery of the same payment request SHALL NOT create duplicate economic collection.

---

# 17. Idempotency layers

Baobab SHALL distinguish:

```text
billing-operation idempotency

financial-obligation idempotency

payment-request idempotency

provider-operation idempotency
```

These MAY share correlation but SHALL not be assumed to be the same identifier.

---

# 18. Payment outcome

`baobab-payments` SHALL communicate canonical payment outcomes back to subscriptions.

Subscriptions SHALL consume payment-domain facts rather than querying HyperSwitch directly.

---

# 19. Payment state projection

Subscriptions MAY maintain a `PaymentStateProjection` associated with a FinancialObligation.

Conceptually:

```text
PaymentStateProjection
────────────────────────

financial_obligation_id

payment_reference

amount_authorised

amount_captured

amount_settled?

amount_refunded

currency

payment_status

observed_at

payment_revision
```

This is a projection.

It is not payment authority.

---

# 20. Payment state vocabulary

Canonical contracts SHOULD distinguish states equivalent to:

```text
PENDING

AUTHORISED

PARTIALLY_PAID

PAID

FAILED

CANCELLED

REFUNDED

PARTIALLY_REFUNDED

DISPUTED
```

where relevant.

Exact payment-domain vocabulary remains governed by Payments/Shared contracts.

---

# 21. Authorised does not mean paid

A payment authorisation SHALL NOT automatically satisfy a FinancialObligation unless applicable payment policy explicitly treats it as sufficient.

Ordinarily:

```text
AUTHORISED
!=
PAID
```

---

# 22. Capture does not necessarily mean settlement

Likewise:

```text
CAPTURED
```

and:

```text
SETTLED
```

may represent different payment lifecycle states.

Baobab SHALL not collapse them where the distinction is operationally or financially material.

---

# 23. Subscription obligation status

The billing domain MAY derive obligation states equivalent to:

```text
OPEN

PAYMENT_PENDING

PARTIALLY_SATISFIED

SATISFIED

OVERDUE

CANCELLED

ADJUSTED
```

These SHALL be billing-domain states, not copies of processor state names.

---

# 24. Derived state

For example:

```text
FinancialObligation
amount = 1,000

trusted payments projection
paid = 1,000
```

may yield:

```text
obligation = SATISFIED
```

The payment provider does not directly set the subscription aggregate's status.

---

# 25. Partial payment

Baobab SHALL support:

```text
amount_due
>
amount_paid
>
0
```

without treating the obligation as either completely unpaid or completely satisfied.

---

# 26. Outstanding balance

Conceptually:

```text
outstanding =
valid obligation
- valid payment satisfaction
- applicable billing adjustments
```

The exact calculation SHALL preserve currency and revision semantics.

---

# 27. Currency integrity

A payment in a different currency SHALL NOT automatically satisfy an obligation without an explicit governed currency-conversion/payment policy.

---

# 28. Overpayment

If payment exceeds the FinancialObligation:

```text
payment
>
amount due
```

subscriptions SHALL NOT silently invent a billing Credit.

The condition SHALL be reconciled according to explicit policy.

Possible downstream outcomes may include:

- payment-domain refund;
- approved account credit;
- manual review.

---

# 29. Refund

Refund execution belongs to `baobab-payments`.

A refund SHALL reference the relevant payment transaction and, where applicable, billing reason.

---

# 30. Credit is not refund

As established by ADR-SUB-0009:

```text
Credit
!=
Refund
```

A Credit changes billing consequences.

A Refund reverses previously executed monetary movement.

---

# 31. Refund does not erase payment history

If:

```text
Payment
1,000
```

is later:

```text
Refunded
200
```

Baobab SHALL preserve both facts.

It SHALL NOT rewrite the original payment to 800.

---

# 32. Billing consequence of refund

A refund MAY or MAY NOT reopen a FinancialObligation.

That depends on why the refund occurred.

Example:

```text
billing correction
     │
     ▼
Credit
     │
     ▼
Refund
```

may leave the corrected obligation satisfied.

But:

```text
payment reversed
without billing reduction
```

may make an amount outstanding again.

The distinction SHALL be explicit.

---

# 33. Payment reversal

Reversal/chargeback/dispute events SHALL not automatically be interpreted as billing credits.

They represent payment-domain changes.

Subscriptions determines their billing consequence.

---

# 34. Dispute

A payment dispute SHALL be projected as a payment condition.

It SHALL NOT automatically:

- cancel ProductSubscription;
- revoke CapabilityGrant;
- delete InvoiceProjection;
- erase Charge.

---

# 35. Payment failure

Payment failure means:

```text
collection attempt failed
```

It does not mean:

```text
subscription no longer exists
```

---

# 36. Billing delinquency

Subscriptions SHALL own a billing-domain concept of delinquency.

Conceptually:

```text
DelinquencyState
────────────────────────

CURRENT

PAYMENT_PENDING

PAST_DUE

DELINQUENT

RESOLVED

WRITE_OFF_CANDIDATE
```

Exact vocabulary MAY evolve.

---

# 37. Delinquency is not payment failure

A single failed payment attempt SHALL NOT necessarily mean the account is delinquent.

Delinquency derives from policy and obligation state.

---

# 38. Due date

Delinquency SHALL consider the FinancialObligation's authoritative:

```text
due_at
```

rather than arbitrary payment-attempt timing.

---

# 39. Grace period

Billing policy MAY define a grace period.

Conceptually:

```text
Due Date
   │
   ▼
Grace Period
   │
   ▼
Delinquency Threshold
```

---

# 40. Grace period is policy

No universal Baobab grace period SHALL be assumed.

It may depend on:

- contract;
- product;
- customer class;
- market;
- commercial agreement.

---

# 41. Dunning

Baobab MAY support dunning workflows around overdue FinancialObligations.

Dunning SHALL be distinct from payment execution.

---

# 42. Dunning actions

A dunning policy MAY conceptually include:

```text
reminder

retry request

payer notification

account warning

escalation

entitlement-review signal
```

The exact workflow SHALL be governed separately from processor retry mechanics.

---

# 43. Processor retry versus dunning

The distinction SHALL remain:

```text
Payment Retry
= attempt to execute collection

Dunning
= business process for unresolved debt
```

---

# 44. Retry authority

`baobab-payments` owns execution of payment retries.

Subscriptions MAY request collection according to billing policy but SHALL not directly manipulate provider retry mechanics.

---

# 45. Retry safety

Retries SHALL be:

- bounded;
- idempotent;
- observable;
- policy-driven.

An ambiguous payment outcome SHALL be reconciled before unsafe re-execution.

---

# 46. Unknown payment outcome

A timeout may occur after a provider has committed a transaction.

Therefore:

```text
timeout
!=
payment failed
```

The result SHALL be represented as uncertain/unknown until reconciled.

---

# 47. No blind retry after unknown outcome

If payment outcome is unknown:

```text
PaymentRequest
     │
     ▼
UNKNOWN
     │
     ▼
Reconcile
```

SHALL precede unsafe retry.

This prevents duplicate collection.

---

# 48. Entitlement boundary

Payment state SHALL NOT directly mutate CapabilityGrants.

This is a fundamental platform boundary.

```text
Payment Failure
      │
      ▼
Billing Delinquency
      │
      ▼
Governed Lifecycle Signal
      │
      ▼
Control Plane
      │
      ▼
Entitlement Decision
```

---

# 49. Control Plane remains entitlement authority

Only Control Plane may authoritatively decide the resulting subscription/entitlement consequence according to canonical policy.

Subscriptions SHALL NOT directly delete or revoke a CapabilityGrant.

---

# 50. Payments cannot revoke entitlement

`baobab-payments` SHALL have no authority to revoke:

- ProductSubscription;
- CapabilityGrant;
- tenant;
- DigitalEstate;
- product access.

---

# 51. HyperSwitch cannot revoke entitlement

HyperSwitch provider state is even further removed from entitlement authority.

A processor decline SHALL never directly disable a Baobab tenant.

---

# 52. Delinquency signal

Subscriptions MAY publish a canonical signal equivalent to:

```text
billing.account.delinquent
```

or:

```text
billing.obligation.overdue
```

Exact event naming SHALL be defined in Shared.

---

# 53. Signal, not command

A delinquency event reports a billing fact.

It SHALL NOT semantically mean:

```text
revoke entitlement now
```

unless a separate canonical Control Plane command has been authorised.

---

# 54. Suspension

If platform policy determines that prolonged delinquency warrants service suspension, the lifecycle remains:

```text
Subscriptions
detects delinquency
      │
      ▼
Canonical billing signal
      │
      ▼
Control Plane
evaluates policy
      │
      ▼
ProductSubscription /
CapabilityGrant decision
      │
      ▼
Downstream projection
```

---

# 55. Restoration

Likewise, payment resolution SHALL not independently grant entitlement.

Subscriptions publishes the resolved billing fact.

Control Plane decides restoration where applicable.

---

# 56. INTERNAL subscriptions

INTERNAL subscriptions SHALL not enter commercial payment delinquency merely because no payment exists.

For INTERNAL:

```text
payment requirement = PROHIBITED / NOT REQUIRED
```

therefore:

```text
absence of payment
!=
delinquency
```

---

# 57. COMMERCIAL manual-payment arrangements

Not every COMMERCIAL obligation must use automated collection.

Payment policy MAY permit:

```text
bank transfer

manual settlement

invoice terms

other governed payment route
```

The architecture SHALL not equate COMMERCIAL with automatic card collection.

---

# 58. PaymentPolicy

Conceptually:

```text
PaymentPolicy
────────────────────────

payment_policy_id

collection_mode

payment_terms

automatic_collection

grace_policy

dunning_policy

effective_from

version
```

---

# 59. Collection modes

Policy MAY support equivalents of:

```text
AUTOMATIC

INVOICE_TERMS

MANUAL

EXTERNAL_SETTLEMENT
```

where required.

Exact vocabulary belongs in canonical contracts.

---

# 60. Payment policy is versioned

Historical obligations SHALL retain the PaymentPolicy applicable when they were established.

---

# 61. Payment policy does not redefine classification

A manual payment arrangement does not make a COMMERCIAL subscription INTERNAL.

Likewise automatic payment does not prove classification.

---

# 62. Payment policy and payer

ADR-SUB-0011 determines who is financially responsible.

ADR-SUB-0013 determines how the resulting obligation interacts with payment execution.

---

# 63. Payer changes

A payer change SHALL NOT silently transfer an already-established FinancialObligation unless explicitly authorised by billing policy.

Historical responsibility SHALL remain traceable.

---

# 64. Central payer

A corporate group MAY use a central payer for several tenant subscriptions.

For example:

```text
Subsidiary A ─┐
Subsidiary B ─┼──► Group Billing Account
Subsidiary C ─┘
                     │
                     ▼
                  Payer P
```

This does not merge the tenants.

---

# 65. Payment failure isolation

A payment failure associated with one FinancialObligation SHALL NOT automatically contaminate unrelated tenant obligations merely because they share a PlatformAccount or corporate parent.

Any account-wide consequence requires explicit billing policy.

---

# 66. Shared billing account delinquency

Where several obligations legitimately share one BillingAccountProjection, account-level delinquency MAY be derived.

The affected scope SHALL be explicit.

---

# 67. No implicit cross-tenant suspension

Even if a shared payer becomes delinquent, subscriptions SHALL not infer that every tenant under the payer should lose entitlement.

Control Plane remains responsible for any cross-subscription lifecycle decision.

---

# 68. Settlement

Payment settlement state MAY be projected where operationally required.

Subscriptions SHALL not become settlement-ledger authority.

---

# 69. Settlement is not billing satisfaction by definition

Whether:

```text
CAPTURED
```

or:

```text
SETTLED
```

is sufficient to satisfy an obligation SHALL be explicit policy.

---

# 70. ERP boundary

ERP remains authoritative for:

- accounts receivable;
- cash accounting;
- payment accounting;
- write-offs;
- revenue recognition;
- general ledger.

Subscriptions does not become an accounting ledger because it tracks obligation status.

---

# 71. Receivable does not equal FinancialObligation

Conceptually:

```text
FinancialObligation
       │
       ▼
ERP projection
       │
       ▼
Accounts Receivable
```

They may correspond but remain separate domain objects.

---

# 72. Write-off

Subscriptions MAY observe or participate in a governed write-off lifecycle.

It SHALL NOT independently post accounting write-offs.

---

# 73. Write-off candidate

Persistent delinquency MAY produce:

```text
WRITE_OFF_CANDIDATE
```

or equivalent billing signal.

ERP/accounting governance determines actual write-off treatment.

---

# 74. Write-off does not delete billing history

Even after accounting write-off, original:

- Charges;
- InvoiceProjection;
- FinancialObligation;
- PaymentAttempts

remain historical facts.

---

# 75. Payment events

Canonical payment outcomes SHOULD be communicated asynchronously.

Conceptually:

```text
payments.payment.authorised
payments.payment.failed
payments.payment.captured
payments.payment.settled
payments.payment.refunded
payments.payment.disputed
```

Exact event names and schemas belong in Shared/payment contracts.

---

# 76. Event trust

Subscriptions SHALL trust payment events only after verifying:

- trusted producer identity;
- schema;
- event identity;
- tenant/context consistency;
- obligation reference;
- revision/order semantics.

Payload claims alone are not authority.

---

# 77. At-least-once delivery

Payment events SHALL be assumed capable of duplicate delivery.

Subscriptions SHALL durably deduplicate them.

---

# 78. No global ordering assumption

The system SHALL NOT assume all payment events arrive in perfect global order.

Revision/version semantics SHALL prevent stale events from regressing newer state.

---

# 79. Example out-of-order events

The engine may observe:

```text
CAPTURED revision 4
```

before a delayed:

```text
AUTHORISED revision 3
```

Revision 3 SHALL NOT regress the projected state.

---

# 80. Transactional boundary

Subscriptions SHALL NOT hold a local database transaction open while waiting for payment execution.

The correct pattern is:

```text
Local transaction
  │
  ├── FinancialObligation
  ├── audit
  └── outbox
  │
 COMMIT
  │
  ▼
Payment request delivery
```

---

# 81. No distributed ACID transaction

Baobab SHALL NOT attempt a distributed transaction across:

```text
subscriptions DB
payments DB
HyperSwitch
processor
ERP
```

Consistency SHALL instead use:

- local transactions;
- idempotency;
- durable events;
- outbox/inbox;
- retries;
- reconciliation.

---

# 82. Reconciliation

Subscriptions SHALL reconcile:

```text
FinancialObligation
        │
        ▼
Expected Payment Requirement
        │
        ▼
Payment Projection
        │
        ▼
Outstanding Balance
```

---

# 83. Cross-engine reconciliation

Where necessary:

```text
Subscriptions
FinancialObligation
      │
      ├─────────────┐
      ▼             ▼
Payments State    ERP State
```

may be compared without any engine reading another engine's database directly.

---

# 84. Reconciliation anomalies

At minimum, detect equivalents of:

```text
PAYMENT_REQUEST_MISSING

DUPLICATE_COLLECTION_RISK

PAYMENT_AMOUNT_MISMATCH

PAYMENT_CURRENCY_MISMATCH

UNKNOWN_PAYMENT_OUTCOME

ORPHAN_PAYMENT

STALE_PAYMENT_PROJECTION

OVERPAYMENT

REFUND_MISMATCH

INTERNAL_PAYMENT_VIOLATION

SATISFACTION_MISMATCH
```

---

# 85. Orphan payment

A payment referring to no known FinancialObligation SHALL NOT automatically create one.

It requires reconciliation.

Downstream payment facts cannot manufacture upstream billing authority.

---

# 86. Amount mismatch

If Payments reports:

```text
FinancialObligation:
1,000 ZAR

Payment:
900 ZAR
```

subscriptions SHALL represent partial satisfaction rather than rewriting the obligation to 900.

---

# 87. Currency mismatch

A currency mismatch SHALL be treated as a serious reconciliation condition unless explicit conversion policy applies.

---

# 88. Duplicate collection risk

If reconciliation finds two successful payment executions against the same intended collection, the system SHALL not silently count both as ordinary satisfaction.

The condition requires explicit overpayment/refund/credit handling.

---

# 89. Automatic reconciliation repair

Automatic repair MAY occur where:

- payment identity is unambiguous;
- obligation identity is unambiguous;
- no additional monetary movement is required;
- correction is idempotent;
- tenant scope is valid.

---

# 90. Manual review

Manual review SHALL be required for:

- ambiguous duplicate payment;
- unexplained overpayment;
- cross-currency mismatch;
- orphan payment;
- disputed payer;
- unknown settlement after repeated reconciliation failure;
- INTERNAL payment execution;
- conflicting refund history.

---

# 91. Security

All subscriptions-to-payments calls SHALL use authenticated workload identity.

Static long-lived production bearer secrets SHALL not be the normal trust mechanism.

---

# 92. Least privilege

The subscriptions workload SHALL possess only the payment capabilities required to submit and inspect authorised billing-related payment operations.

It SHALL not receive broad provider-administration authority.

---

# 93. Trusted amount

`baobab-payments` SHALL receive the monetary amount from an authenticated, authorised Baobab billing workflow.

A browser or digital estate SHALL NOT be able to arbitrarily replace:

```text
1,150
```

with:

```text
1
```

during payment handoff.

---

# 94. Payment request integrity

A payment request SHALL bind at least:

```text
financial obligation
payer
amount
currency
tenant context
idempotency identity
```

---

# 95. Audit

Subscriptions SHALL preserve audit evidence for:

- obligation creation;
- payment request;
- payment result projection;
- partial satisfaction;
- delinquency transition;
- payment resolution;
- refund consequence;
- reconciliation repair;
- privileged override.

---

# 96. Correlation

Correlation identifiers SHALL propagate:

```text
InvoiceProjection
       │
       ▼
FinancialObligation
       │
       ▼
PaymentRequest
       │
       ▼
Payment
       │
       ▼
ERP projection
```

without becoming identity or authorization credentials.

---

# 97. Observability

Subscriptions SHOULD expose telemetry for:

- open obligations;
- overdue obligations;
- payment handoff failures;
- payment pending duration;
- partial payments;
- failed collections;
- unknown outcomes;
- delinquent billing accounts;
- reconciliations;
- overpayments;
- INTERNAL payment violations.

---

# 98. Sensitive telemetry

Telemetry SHALL NOT expose:

- card data;
- bank credentials;
- payment secrets;
- provider credentials;
- unnecessary personal financial data.

---

# 99. Domain invariants

### INV-PAY-01

FinancialObligation is not Payment.

### INV-PAY-02

FinancialObligation has stable Baobab identity independent of payment provider identity.

### INV-PAY-03

Subscriptions owns amount due.

### INV-PAY-04

Payments owns monetary execution.

### INV-PAY-05

Subscriptions does not select underlying payment processors directly.

### INV-PAY-06

Subscriptions does not store raw payment credentials.

### INV-PAY-07

INTERNAL subscriptions cannot create collectible payment obligations.

### INV-PAY-08

A COMMERCIAL zero balance does not require artificial payment execution.

### INV-PAY-09

Payment handoff is idempotent.

### INV-PAY-10

Multiple payment attempts do not create duplicate FinancialObligations.

### INV-PAY-11

A failed PaymentAttempt does not imply subscription termination.

### INV-PAY-12

An unknown payment outcome is reconciled before unsafe retry.

### INV-PAY-13

Partial payment does not rewrite the original obligation.

### INV-PAY-14

Overpayment does not automatically create a billing Credit.

### INV-PAY-15

Credit is not Refund.

### INV-PAY-16

Refund does not erase original payment history.

### INV-PAY-17

Payment dispute does not erase Charge or InvoiceProjection.

### INV-PAY-18

Delinquency is a billing-domain consequence, not a processor status.

### INV-PAY-19

Payment failure cannot directly revoke CapabilityGrant.

### INV-PAY-20

Payments cannot directly mutate ProductSubscription.

### INV-PAY-21

Control Plane remains entitlement authority.

### INV-PAY-22

INTERNAL absence of payment is not delinquency.

### INV-PAY-23

Payment events are deduplicated.

### INV-PAY-24

Stale payment events cannot regress newer projected state.

### INV-PAY-25

No distributed ACID transaction spans billing and payment engines.

### INV-PAY-26

Payment provider state does not manufacture FinancialObligation authority.

### INV-PAY-27

FinancialObligation is not ERP Accounts Receivable.

### INV-PAY-28

Write-off does not destroy billing history.

### INV-PAY-29

Cross-tenant payment effects require explicit authority.

### INV-PAY-30

Payment reconciliation never silently invents monetary movement.

---

# 100. Failure handling

| Condition | Required behaviour |
|---|---|
| Payments unavailable | Preserve obligation; retry handoff safely |
| Provider unavailable | Payments concern; obligation remains |
| Payment declined | Project failure; evaluate billing policy |
| Timeout / uncertain result | Reconcile before unsafe retry |
| Partial payment | Preserve outstanding balance |
| Duplicate success | Reconciliation / overpayment handling |
| Refund | Preserve original payment and refund |
| Dispute | Project dispute; no automatic entitlement mutation |
| INTERNAL payment request | Reject and alert |
| Currency mismatch | Block/reconcile |
| Unknown obligation reference | Do not manufacture obligation |
| Event duplication | Deduplicate |
| Stale event | Ignore/regard as historical according to revision |

---

# 101. Alternatives considered

## 101.1 Let subscriptions call HyperSwitch directly

**Rejected.**

This bypasses `baobab-payments` and destroys the payment orchestration boundary.

## 101.2 Treat invoice as payment request

**Rejected.**

An invoice can exist without automated collection and may contain different payment terms.

## 101.3 Treat failed payment as failed subscription

**Rejected.**

Billing, payment and entitlement are separate domains.

## 101.4 Let Payments suspend subscriptions

**Rejected.**

Payments does not own ProductSubscription or CapabilityGrant.

## 101.5 Retry every payment timeout

**Rejected.**

Timeout-after-commit can create duplicate collection.

## 101.6 Make payment provider state canonical

**Rejected.**

Providers execute payment; they do not own Baobab billing semantics.

## 101.7 Convert overpayment automatically into credit

**Rejected.**

This creates a new billing fact without explicit billing authority.

## 101.8 Store card/payment credentials in subscriptions

**Rejected.**

It unnecessarily expands security and compliance scope.

## 101.9 Use distributed transactions across billing and payment

**Rejected.**

The architecture is polyrepo, polyglot and independently deployable; reliable asynchronous convergence is required.

---

# 102. Consequences

## Positive

- Billing and payment authority remain cleanly separated.
- HyperSwitch stays behind `baobab-payments`.
- Duplicate collection risk is reduced.
- Partial payment becomes representable.
- Payment failures do not accidentally destroy subscriptions.
- Entitlement authority remains in Control Plane.
- INTERNAL subscriptions are protected from accidental collection.
- Dunning becomes policy-driven.
- Provider replacement does not alter canonical billing identity.
- Reconciliation becomes explicit.

## Negative

- Additional projections are required.
- Eventual consistency must be handled deliberately.
- Payment reconciliation becomes operationally important.
- Delinquency policy adds lifecycle complexity.
- Cross-engine observability is required.

These costs are accepted because monetary execution must not collapse the architectural separation between billing, payment, entitlement and accounting.

---

# 103. Implementation requirements

A conforming implementation SHALL provide:

1. stable FinancialObligation identity;
2. explicit PaymentPolicy;
3. canonical subscriptions-to-payments handoff;
4. workload authentication;
5. idempotent payment requests;
6. payment-state projection;
7. partial-payment handling;
8. outstanding-balance calculation;
9. unknown-outcome handling;
10. refund projection;
11. dispute projection;
12. delinquency state;
13. grace-period policy;
14. dunning hooks;
15. entitlement-boundary enforcement;
16. INTERNAL payment prohibition;
17. payment reconciliation;
18. durable event deduplication;
19. stale-event protection;
20. transactional outbox/inbox integration;
21. audit;
22. observability;
23. ERP boundary;
24. security controls.

---

# 104. Required tests

At minimum:

## Obligation

- valid COMMERCIAL obligation;
- COMMERCIAL zero balance;
- INTERNAL prohibition;
- duplicate obligation handoff.

## Payment

- successful collection;
- failed collection;
- authorisation only;
- capture;
- settlement;
- partial payment;
- multiple attempts;
- manual payment.

## Failure

- payment service unavailable;
- provider timeout;
- unknown outcome;
- safe reconciliation;
- stale event;
- duplicate event.

## Refund / dispute

- full refund;
- partial refund;
- payment reversal;
- dispute;
- refund without billing reduction;
- refund following billing credit.

## Delinquency

- payment failure before due date;
- past due;
- grace period;
- delinquency;
- resolution;
- no INTERNAL delinquency.

## Entitlement

- payment failure cannot revoke CapabilityGrant;
- delinquency emits signal;
- Control Plane determines lifecycle consequence;
- payment resolution cannot independently grant entitlement.

## Multi-tenant

- shared payer;
- separate tenant obligations;
- cross-tenant isolation;
- shared billing account with explicit authority.

## Security

- arbitrary amount replacement rejected;
- unauthenticated workload rejected;
- wrong tenant rejected;
- duplicate idempotency key with changed payload rejected.

---

# 105. Recommended implementation sequence

```text
1. FinancialObligation boundary
          │
          ▼
2. PaymentPolicy
          │
          ▼
3. Canonical PaymentRequest
          │
          ▼
4. Payments workload identity
          │
          ▼
5. Outbox handoff
          │
          ▼
6. Payment result inbox
          │
          ▼
7. PaymentStateProjection
          │
          ▼
8. Partial payment / balance
          │
          ▼
9. Unknown-outcome reconciliation
          │
          ▼
10. Refund / dispute projection
          │
          ▼
11. Delinquency
          │
          ▼
12. Control Plane lifecycle signals
          │
          ▼
13. ERP integration
          │
          ▼
14. Security / observability hardening
```

---

# 106. End-to-end architecture

```text
                    CONTROL PLANE
                         │
             ProductSubscription
             Classification
             CapabilityGrant
                         │
                         ▼
                 SUBSCRIPTIONS
                         │
             BillingSubscription
                         │
                         ▼
                      Charge
                         │
                         ▼
                 Tax / Credits
                         │
                         ▼
                InvoiceProjection
                         │
                         ▼
              FinancialObligation
                         │
                         │ canonical,
                         │ authenticated,
                         │ idempotent
                         ▼
                    PAYMENTS
                         │
                         ▼
                    HyperSwitch
                         │
                         ▼
               Payment Provider
                         │
                         ▼
                 Payment Outcome
                         │
                         ▼
                    PAYMENTS
                         │
                  canonical event
                         ▼
                 SUBSCRIPTIONS
                         │
              PaymentStateProjection
                         │
                 ┌───────┴────────┐
                 ▼                ▼
            Obligation        Delinquency
            satisfaction         signal
                                    │
                                    ▼
                              CONTROL PLANE
                                    │
                                    ▼
                            Entitlement decision


                  SUBSCRIPTIONS
                         │
                  billing facts
                         ▼
                       ERP
                         │
                         ▼
              Accounting / AR / GL
```

---

# 107. Worked example — successful collection

```text
InvoiceProjection
ZAR 1,150
      │
      ▼
FinancialObligation O-100
amount_due = 1,150
      │
      ▼
PaymentRequest
obligation = O-100
amount = 1,150 ZAR
      │
      ▼
baobab-payments
      │
      ▼
HyperSwitch
      │
      ▼
Provider
SUCCESS
      │
      ▼
Canonical payment outcome
      │
      ▼
PaymentStateProjection
paid = 1,150
      │
      ▼
FinancialObligation
SATISFIED
```

The payment system does not modify the invoice itself.

---

# 108. Worked example — failed collection

```text
FinancialObligation
due = 01 Oct
      │
      ▼
PaymentAttempt 1
FAILED
```

The correct conclusion is:

```text
payment attempt failed
```

not:

```text
ProductSubscription cancelled
```

Billing policy subsequently determines:

```text
retry
   │
   ▼
grace period
   │
   ▼
past due
   │
   ▼
delinquency
```

Only an authorised Control Plane lifecycle decision may subsequently affect entitlement.

---

# 109. Worked example — timeout after provider commit

```text
PaymentRequest
1,000 ZAR
      │
      ▼
Provider accepts payment
      │
      X
network timeout
      │
      ▼
Baobab sees UNKNOWN
```

The unsafe approach is:

```text
UNKNOWN
   │
   ▼
retry immediately
   │
   ▼
possible second 1,000 payment
```

The required approach is:

```text
UNKNOWN
   │
   ▼
Reconciliation
   │
   ├── payment exists
   │      ▼
   │    project success
   │
   └── payment absent
          ▼
       safe retry
```

---

# 110. Worked example — partial payment

```text
FinancialObligation
ZAR 10,000

Payment A
ZAR 6,000
```

Subscriptions projects:

```text
amount_due        10,000
amount_satisfied   6,000
amount_outstanding 4,000

status:
PARTIALLY_SATISFIED
```

The original FinancialObligation remains ZAR 10,000.

---

# 111. Worked example — shared corporate payer

```text
Corporate Group
      │
      ├── Tenant A
      │     Obligation A
      │
      └── Tenant B
            Obligation B

             │
             ▼
       Billing Account P
             │
             ▼
         Central Payer
```

Failure to pay Obligation A SHALL NOT automatically revoke Tenant B's entitlements.

Shared payer responsibility does not collapse tenant isolation.

---

# 112. Worked example — INTERNAL safety

Suppose an INTERNAL subscription has:

```text
shadow rated usage:
ZAR 25,000
```

and a payment method happens to exist on the associated billing account.

The required policy remains:

```text
classification:
INTERNAL

analytical value:
25,000 ZAR

collectible obligation:
NONE

PaymentRequest:
PROHIBITED
```

Neither Kill Bill nor `baobab-payments` may override that decision.

---

# 113. Final Decision

Baobab SHALL treat **billing obligation and monetary execution as separate domains joined through explicit, authenticated, idempotent and reconcilable contracts**.

The principal authority rule is:

> **Subscriptions determines what is owed; Payments determines how authorised money is moved.**

The principal identity rule is:

> **A FinancialObligation is a canonical Baobab billing object and remains stable across payment attempts, providers, retries and reconciliation.**

The principal failure rule is:

> **Payment failure is evidence that collection failed; it is not evidence that the ProductSubscription ceased to exist.**

The principal uncertainty rule is:

> **An unknown payment outcome must be reconciled before an unsafe retry can create duplicate collection.**

The principal delinquency rule is:

> **Delinquency is derived by the billing domain from outstanding obligations and policy, not copied from a processor status.**

The principal entitlement rule is:

> **Neither Subscriptions, Payments, HyperSwitch nor a payment provider may directly revoke or grant CapabilityGrants because of payment state; Control Plane remains entitlement authority.**

The principal INTERNAL rule is:

> **INTERNAL subscriptions may remain metered and analytically valued, but cannot enter commercial collection or delinquency merely because no payment occurs.**

The principal historical rule is:

> **Payments, refunds, disputes and corrections remain append-oriented financial history rather than destructive rewrites.**

The principal integration rule is:

> **Subscriptions and Payments converge through canonical contracts, idempotency, outbox/inbox processing and reconciliation—not shared databases or distributed transactions.**

And the principal accounting rule is:

> **FinancialObligation is not Accounts Receivable: Subscriptions owns the billing obligation, Payments owns monetary execution, and ERP owns its accounting consequence.**