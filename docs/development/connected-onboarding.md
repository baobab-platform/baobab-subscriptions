# Connected Subscriptions development qualification

Governing: Accepted EA-DEV, ADR-SHARED-043/044 and ADR-SUB-0003/0014–0018.
Use only run-owned synthetic data, the separately registered development CP
producer and Subscriptions assessor, actual Hydra-issued audience-specific
tokens, CP-owned guarded fixtures and a fresh PostgreSQL 17 store. No CMS,
Payments, ERP, production identity or monetary provider is a prerequisite.

The engine now embeds the immutable current-authority owner schema. It validates
closed snapshot/request/deny-decision types using the same offline packaged-JAR
registry as INTERNAL and events. Runtime coercion cannot accept a string revision,
unknown status or invented positive financial decision. Golden wire vectors are
independently compared with original Shared Git blobs.

Set `CP_SUBSCRIPTION_PROJECTION_AUTHORITY_ENABLED=true` with explicit
`CP_SUBSCRIPTION_CURRENT_AUTHORITY_BASE_URL` and an absolute
`CP_SUBSCRIPTION_CURRENT_AUTHORITY_TOKEN_FILE`. The separate CP-audience token is
read anew for every call. A fresh ACTIVE exact tenant/subscription/product,
revision and immutable classification plus current primary Organisation must
match before ensure, resume or usage, including before a successful replay.
This nonproduction opt-in does not authorize INTERNAL: its existing independent
eligibility port must also permit the operation. State-reducing suspend/terminate
retain their existing CP-producer boundary rather than depending on ACTIVE state.
No positive authority survives CP outage or revision drift. This is a short-lived
snapshot consistency gate, not distributed atomic CP/engine transaction fencing.

For connected caller revocation, set `WORKLOAD_INTROSPECTION_ENDPOINT` (HTTPS),
`WORKLOAD_INTROSPECTION_CLIENT_ID` and an absolute
`WORKLOAD_INTROSPECTION_SECRET_FILE`. After signature/issuer/audience/scope checks,
the authenticator checks current issuer status on every request with a bounded
complete-body deadline, no redirects and a bounded response. Active subject,
client, audience and scopes must match the already verified JWT. Missing status,
credentials, outages and revocation deny; no positive cache is used. Existing
signature-only deployments are not silently reclassified as revocation-qualified.

The native Infrastructure suite must demonstrate genuine CP reads, ordinary
projection persistence (commercial readiness BLOCKED, never billable READY),
tenant and classification/revision denials, before-replay current checks,
issuer expiry/revocation, durable event replay/conflicts, CP outage/recovery and
owned teardown. A COMMERCIAL fixture does not establish INTERNAL eligibility;
that independent positive and its differential remain BLOCKED until the actual
prerequisite exists. Kill Bill dispatch remains disabled in Application regardless
of this profile. No actual payment or settlement is enabled.

Rollback: disable this disposable profile, revoke owned issuer credentials and
remove only its labelled containers/volumes/network/private state. Restore owner
source/contract pins together. Retain original sanitized receipts; never reuse
old physical PostgreSQL volumes or promote development credentials.
