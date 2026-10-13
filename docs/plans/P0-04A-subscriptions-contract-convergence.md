# P0-04A — Subscriptions contract convergence

Date: 13 October 2026. Follows Trade #145, ERP #95 and Payments #36 in CP's
ADR-BCP-026/027 completion sequence. Accepted ADR-SUB-0002/0006/0016 govern
projection ownership, classification and event integrity. This is an
implementation/evidence record; genuine P0-03 and protected P0-04B remain separate.

## Audited baseline

| Repository | Immutable main | Findings before this task's PRs were opened |
|---|---|---|
| Subscriptions | `66fc159423447ecc16c55c9fb1f9eabe654abc96` | No open PRs. Current CP INTERNAL client, durable verified-workload founding inbox and protected command-origin safeguards exist. Manual wire checks do not fully enforce closed owner schemas. |
| Shared | `a9c53087d63caa356edf54ec8037ba1713c4827f` | One old declared evidence file changed: six additive document-class values. Existing authority/event and producer definitions retain their semantics. Dependency PRs #290–294 remain independent. |
| CP | `9e782760e58b554fe48560ac449ba8ee944bf6ec` | No open PRs. Current INTERNAL decision endpoint and Payments programme follow-up exist. |
| IAM | `ad7989ca085831303c265f943ef2b97735f911ad` | No open PRs. Workload admission/provider acceptance are not supplied by a consumer pin. |

Historical INTERNAL authority, founding inbox and lock branches were compared
with main. Their useful components are already present; old branch trees remove
later migrations, verified-client replay and protected command-origin fixes.
They are not merged wholesale. No AGENTS.md applies. No owner contract changes
or payment/invoice provider implementation are needed for this slice.

The only registered Subscriptions workload at this pin is PROVISIONED for the
Payments audience/scopes. It cannot obtain CP `subscription:internal-authority`
from that profile. The CP billing producer is also PROVISIONED, with
`billing:manage`/`billing:read`; its profile supplies neither usage recording nor
founding-event intake admission. These gaps require separate Shared/IAM/CP review.
No profile is activated or widened here.

## Changes and maintenance

The exact lock declares 22 consumed schemas/policy/metadata files. Immutable Git
blobs generate the complete resource inventory and SHA-256 provenance. Every
reference must be local, declared and safe; the script checks all owner entry
pointers before replacing only generated resources. Regeneration removes stale
assets. The registry verifies original hashes, uses embedded schema strings,
asserts formats, caches validators and disables remote retrieval. Only declared
schema files may be addressed. Networknt 3 remains on its existing Jackson 3
dependency while application JSON stays Jackson 2, crossing as JSON text.

Twelve authority entry points cover the current INTERNAL decision, its three GET
tuple property grammars, the CloudEvents envelope and all seven founding payloads.
The CP client checks the owner response before exact tenant/subscription/reference,
current eligibility and a positive five-second maximum lease. Valid negative and
foreign/stale decisions still deny. There is no separate owner GET request schema;
using the response property pointers avoids inventing or copying a grammar.
The founding inbox checks owner envelope/payload before its existing producer,
type/schema/subject binding and before database receipt. Events never grant
classification or current INTERNAL permission.

JSON commands/facts/events reject duplicate members recursively, trailing input
and excessive depth. Diagnostics report owner schema locations without instance
values or untrusted property names. CP retention is bounded to 16 KiB before
oversized chunks are copied; a complete-response future deadline also limits slow
bodies after prompt headers. Redirect-enabled injected HTTP clients are refused.
Workload token files are read anew with an 8 KiB bound. Cancellation and thread
interruption are handled without permissive fallback.

For an intentional owner upgrade:

```sh
# Inspect owner diffs, update the exact pin and declare all new closure paths.
python -m pip install 'PyYAML==6.0.3' 'jsonschema[format-nongpl]==4.26.0'
python scripts/sync_contracts.py --shared-checkout ../shared
python scripts/sync_contracts.py --shared-checkout ../shared --check
python scripts/verify_contract_owner.py --shared-checkout ../shared
SHARED_CONTRACTS_DIR=../shared ./mvnw --batch-mode --no-transfer-progress verify
python scripts/verify_contract_artifact.py --shared-checkout ../shared --jar target/baobab-subscriptions-0.1.0-SNAPSHOT.jar
docker build -t baobab-subscriptions .
```

The Shared checkout HEAD must equal the lock; mutable checkout bytes are never
the generation authority. Review provenance, complete resource inventory and
entry pointers together. Keep source comments and these status records aligned.
No dependency/toolchain version is changed by this convergence slice.

## Validation and limits

The persisted 144-vector synthetic matrix checks all twelve entries with positive,
negative, missing/unknown/type/UUID/date-time/length and platform/tenant cases.
An independent Python validator reads original Git blobs and compares them with
Java. Existing billing projection/usage/event producer scenarios remain the
producer conformance evidence. Golden vectors are wire facts, never authentic
founding records, current eligibility, provider certification or live support.

Tests cover unknown envelope/payload fields, short-form UUIDs, ambiguous JSON,
current binding/revocation, invalid local tuples before CP contact, redirect and
chunked/slow-body denials, retained-byte bounds and malformed events leaving no
PostgreSQL receipt. Existing raw-byte/verified-client replay, zero-charge/no-payment,
tenant isolation, revisions and protected command-origin checks stay in force.

The actual release JAR is checked for original bytes and full resource inventory,
Java 21 class targeting and runtime `lib/` dependencies. It is copied to a clean
temporary directory and probed without source/test classes, issuer credentials,
database or schema retrieval. An invisible caller TCCL verifies resource ownership;
the original thread context is always restored. Docker runs the same offline
probes against the built package.

Local immutable-owner/generation/inventory and syntax/diff checks are separate
from hosted compilation/JAR/container/PostgreSQL evidence. This executor has only
a Java 17 runtime, and Maven Central dependencies cannot resolve. No local Java 21
compilation or database proof is claimed. Final-head hosted CI and resolved review
are mandatory before merge; no gate or meaningful test is relaxed.

Real workload/profile admission, approved command producer and sidecar identity,
genuine legal/control/mandate facts, chargeable invoice responsibility, Kill Bill,
Payments handoff, full billing lifecycle and protected end-to-end acceptance
remain independently open. Next owning-engine consumer: Trade Docs.

## Primary implementation references

- [Networknt 3.0.8 immutable schema maps](https://github.com/networknt/json-schema-validator/blob/3.0.8/doc/schema-retrieval.md)
- [Networknt 3.0.8 schema loader retrieval controls](https://github.com/networknt/json-schema-validator/blob/3.0.8/src/main/java/com/networknt/schema/resource/SchemaLoader.java)
- [Networknt 3.0.8 error locations](https://github.com/networknt/json-schema-validator/blob/3.0.8/src/main/java/com/networknt/schema/Error.java)

The existing ERP complete-body subscriber pattern was reviewed for the JDK
transport. Subscriptions retains its own source and package, not a dependency on
another engine's runtime or a new Shared canonical wire definition.
