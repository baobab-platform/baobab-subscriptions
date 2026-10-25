# Authenticated CP ProductSubscription event intake — ADR-SUB-0003/0014/0016

**Status:** nonproduction durable receive-only slice; no current-CP snapshot adapter, billing-state application or production qualification is claimed. **Shared contract pin:** `a9c53087d63caa356edf54ec8037ba1713c4827f`.

## Canonical wire support

Accept the five ProductSubscription notifications from Shared `product/v1/asyncapi.yaml` only: `created`, `activated`, `suspended`, `cancelled`, `classified`. Validate the exact pinned Shared `product/v1/events.schema.json` payload (not the similar-sounding Subscriptions billing events), closed CloudEvents v1 envelope, CP logical source, subject, tenant ID and expected `dataschema`. Never consume caller-provided INTERNAL eligibility or billing state.

Ingress is `POST /internal/v1/cp-subscription-lifecycle-events`, with IAM-signed short-lived JWT, audience `baobab-subscriptions`, scope `billing:observe`, exact `CP_SUBSCRIPTION_EVENT_CLIENT_ID` (and in the existing workload allowlist). It is off by default. Set `CP_SUBSCRIPTION_LIFECYCLE_RECEIVER_ENABLED=true` only in a private **development/integration/staging** deployment with PostgreSQL 17, verified issuer/JWKS, restricted network and the approved CP producer client ID. Production refuses activation. The API does not trust the `source` property without verified workload identity.

A successful first receipt returns HTTP 202; byte-identical replay under the same verified producer returns HTTP 200. Same source+event ID with different bytes/client/tenant is 409, invalid schemas/tenant/subject are 422, missing/wrong audience token is 401 and wrong scope/client is 403. The insert, source identity, SHA-256 raw body digest and pending status commit together before acknowledgement.

## Truthful processing status

Shared's `product/v1/events.schema.json` does not carry a monotonic `aggregate_revision`, `platform_account_id`, current legal actor or all terms needed for a valid billing projection. Hence no event timestamp or HTTP delivery order is used as a revision fence. A duplicate is a transport replay, **not** proof of a previously applied billing command.

All rows remain `PENDING_CP_REFRESH` until CP supplies a separately accepted authenticated *current-state workload read* with canonical tenant, subscription ID, revision, classification/evidence references, product, PlatformAccount and legal actor. Existing CP classification admin APIs do not authorise a workload to invent missing commercial inputs. `pending(tenant, limit)` supplies a bounded, tenant-specific read queue for that later adapter. Do not mark APPLIED or emit financial events from a notification alone. No background worker/relay is claimed; a 2xx acknowledges durable receive **only**.

## Security, recovery and QA

PostgreSQL migration V8 isolates this queue from the existing founding governance inbox and provider operation journal. Duplicate delivery survives restarts and cannot produce multiple business effects. A foreign tenant cannot list another tenant's notifications. Poison messages are rejected before persistence; no generic event types or inferred client default are accepted. Production remains disabled pending qualified CP read, issuer, transport source and replay/reconciliation acceptance.

Verify: `./mvnw verify` including `TEST_DATABASE_URL` pointing to a disposable PostgreSQL 17 instance. Integration tests exercise real signed tokens, scope/client and audience negatives, canonical payload negatives, exact replay and changed-payload replay, persisted pending status after restart, tenant isolation and zero billing projection mutations. Run full cross-engine CP/IAM receipts and teardown separately before treating an L4 scenario as passed.

**Next dependency:** CP authoritative ProductSubscription workload read + secure refetch, signed/verified producer delivery topology, aggregate revision convergence and quarantine/retry worker. Only then can Subscriptions apply current state through its governed billing command path and close ADR-SUB-0003 lifecycle acceptance.
