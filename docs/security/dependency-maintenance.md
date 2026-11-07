# BA-SEC-DEP-001 — baobab-subscriptions dependency and security maintenance audit

**Review date:** 7 November 2026. **Repository:** `baobab-platform/baobab-subscriptions`. **Status:** SOURCE_COMMITTED only.

## Observed manifests and Dependabot coverage

GitHub Actions /; Maven / (root pom.xml); Docker / (runtime Dockerfile); Docker Compose /.devcontainer (development topology).

## Actual dependency/security surveillance

Daily Maven-native OWASP Dependency-Check 12.1.0 runs against the real reactor with failBuildOnCVSS=7; an optional NVD_API_KEY supports vulnerability-database access. No NVD response or scanner failure is counted as PASS. Existing Java 21 + PostgreSQL 17 native subscription/contract checks remain separate.

## Findings, unqualified boundaries and exception custody

Maven dependency management/BOM and provider graph require real resolution and CVE review. The service is a development/integration subscription provider; no billing activation, ERP finance consequence or commercial subscription mandate is authorised here. Dependency-Check advisory database freshness/network access and production artifact scan remain acceptance gates.

**Current disposition:** Maven native audit is configured but not run; database-feed availability, tool compatibility, security results and native customer isolation are not verified.

## Governance and evidence levels

Governing authority: [approved BA-SEC-DEP-001 v1.0, Shared #429](https://github.com/baobab-platform/shared/pull/429). The proposed stricter Shared validator is NOT released; this branch uses the SHA-pinned accepted Shared v2.3.0 structural validator and an owner-local positive/negative source suite. Weekly Monday Africa/Johannesburg version updates are distinct from advisory-driven security response. Routine groups apply only to minor/patch version-updates; major or security updates require independent review.

Evidence state **SOURCE_COMMITTED** means source reviewed/committed, not executed. **CI_VERIFIED**, **SECURITY_ALERT_PATH_VERIFIED**, **CONSUMER_QUALIFIED**, **STAGING_VERIFIED**, and **PRODUCTION_VERIFIED** are NOT claimed. Findings are **BLOCKED** pending an independently approved remediation or expiring exception. **WAIVED is not PASSED**. Security CODEOWNERS review applies to manifest and exception changes.

## Exit criteria

1. Run the actual final-head policy regression and accepted Shared validator; inspect live GitHub Dependabot job logs, dependency graph, Alerts, automatic security update settings and actual dependency scan results.
2. Repair reported security vulnerabilities promptly using real installed package/image provenance; independent time-bounded evidence and Security approval are required for any unpatched exceptions. Do not permit scanner errors, no-ops or ignored advisories to appear as success.
3. Re-run owner-specific native runtime, IAM/CP authority, supplier/consumer isolation, replay, migration and negative acceptance tests after a major or security update. No synthetic PSP, carrier, rule or issuer success counts as a real integration.
4. Obtain separate staging/production approvals and deployment evidence. No protected environment, credential, provider or release is activated by this policy adoption.

No CI run or monitoring, PR merge or protected business approval has been claimed.
