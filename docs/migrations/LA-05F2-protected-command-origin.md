# LA-05F2 — Protected Subscription command-origin enforcement

**Basis:** Accepted ADR-BCP-026/027, Package 4, LA-05 / PEO-02. Complements the already implemented CP current INTERNAL sponsorship/eligibility checks.

Subscriptions is the **billing executor**, not a source of tenant identity, subscription classification, company incorporation, legal-actor mandate or payment permission. `billing:manage` and `usage:record` scopes identify permitted API operations, not who may authoritatively decide a subscription revision or the legally liable party.

## Implemented defensive controls

- The authenticated HTTP dispatcher now checks the exact client ID of the **Control Plane governed command producer** before **all billing-management and usage-recording mutations**, including idempotent replays, whenever the runtime is staging or protected.
- Production or an unknown environment refuses all these mutations with `503 BILLING_EXECUTION_NOT_CERTIFIED`; the current temporary provider remains intrinsically nonproduction.
- In staging, the exact CP workload ID must be explicitly provisioned in `BAOBAB_CP_BILLING_COMMAND_CLIENT_ID`. Missing configuration refuses service startup, and if directly constructed without a configured client, the HTTP handler refuses the operation with `403 CP_COMMAND_AUTHORITY_REQUIRED`. It never infers a trusted CP identity from a broad IAM allowlist.
- Development/integration retain synthetic test workflows; HTTP authentication and scopes still apply. Staging may persist simulated projections only for the designated CP command producer. The flag never authorises actual money movement.
- Non-CP usage senders are denied in protected runtimes until a separate accepted source/tenant authority contract exists; their JWT scope alone is not sufficient.
- Existing `BillingService.requireInternalAuthority` still checks current CP INTERNAL eligibility before idempotency replay. CP command identity does not replace documentary eligibility, product entitlement, legal-actor resolution or PSP certification.

## Remaining operational gates

Actual IAM-issued workload token validation in staging, approved command producer identity, Subscription projection to ERP/Trade correlation, legal-actor responsibility for chargeable invoices and independent provider acceptance must be demonstrated in the protected end-to-end qualification. No fake Nabhold/Thamani affiliation or pre-incorporation legal entity may be used to satisfy these gates. This change is deliberately a **fail-closed enforcement boundary**; it does not grant provider readiness or make billing production-capable.
