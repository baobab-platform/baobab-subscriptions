# CP current ProductSubscription authority — ADR-SHARED-044

**Status:** Engineering connected-workload adapter; current commercial decision **deny-only**. No Kill Bill financial dispatch activated. **Shared authority:** `baobab-platform/shared` ADR-SHARED-044; `contracts/product/v1/current-authority.schema.json`. CP source: `baobab-cp` PR #438, Shared #395.

## Read, not classification mutation

- `GET /internal/subscriptions/v1/tenants/{tenantID}/product-subscriptions/{subscriptionID}/current` reads the CP authority revision, ProductSubscription status and classification/provenance, current primary Organisation association, 3-second CP freshness interval and policy reference. The endpoint is opt-in for nonproduction; a signed short-lived IAM token for **audience baobab-control-plane**, **scope subscription:current-workload-read**, active registry status and exact approved `baobab-subscriptions` client is mandatory. The caller's own tenant claim, if present, must match the URL tenant; no default tenant is guessed.
- `POST .../commercial-authority` is a separate operation- and revision-bound decision using **scope subscription:commercial-authority**, fresh CP status and classification. It always responds **DENIED** in v1 because CP does not own the payer, terms, legal actor's commercial mandate, versioned plan or Kill Bill account. No `AUTHORISED`/money grant exists in Shared v1.
- `CpCurrentSubscriptionAuthority` uses its own projected CP-audience token **read fresh for each call** from a mounted file; redirects are forbidden; HTTPS except loopback; 2–3 second bounded deadlines; bounded JSON response; exact closed required response fields and policy reference; evaluated/expiry short lease. A token for baobab-subscriptions is NOT forwarded as if it were a CP token. It rejects wrong tenant, subscription, product, classification ID/reference, rev, stale/expired leases, missing current Organisation, wrong policy and unknown fields.

## Worker integration and operation recovery

Set `CP_SUBSCRIPTION_CURRENT_AUTHORITY_ENABLED=true`, `CP_SUBSCRIPTION_CURRENT_AUTHORITY_BASE_URL` and `CP_SUBSCRIPTION_CURRENT_AUTHORITY_TOKEN_FILE` in **nonproduction** when `KILLBILL_WORKER_ENABLED=true`, real provider and PostgreSQL V9 journal are configured. The worker uses current CP state before read-only provider observation. No HTTP request is made inside its store transaction. Failures deny observation, do not consume a retry attempt for a missing CP entitlement, and never trigger another monetary POST.

**The worker continues with `dispatchEnabled=false` regardless of environment/config** because the version 1 contract cannot authorize financial execution. `approveDispatch` verifies the CP response but returns empty. No credit/debit/invoice/settlement readiness is asserted. A future positive contract will need a governing ADR plus the canonical payer, finance, catalog version, mandate, provider account, signed decision evidence and connected negative tests.

## Acceptance / negative vectors

`CpCurrentSubscriptionAuthorityTest` uses an isolated CP HTTP fake and proves current read acceptance, mismatch revision, forged tenant, incorrect classification, expired CP lease, wrong IAM bearer, exact operation ID and denied monetary preflight. The CP PR's handler tests cover authorised current read, operation revision mismatch, foreign tenant, human/wrong client and revoked registry status.

**Pending for staging qualification:** actual CP/IAM connected credentials, route deployment, tenant association and legal-mandate evidence, full owner JSON-schema pin in the Subscriptions contract bundle, distributed revocation/revision race tests, and disposable Kill Bill 0.24.22 + PostgreSQL 17 integration with independent teardown.
