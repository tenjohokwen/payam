---
milestone: v1.0
audited: 2026-04-06T19:30:00Z
status: gaps_found
scores:
  requirements: 15/17
  phases: 8/11
  integration: 12/14
  flows: 4/6
gaps:
  requirements:
    - id: "AKEY-01"
      status: "unsatisfied"
      phase: "Phase 27"
      claimed_by_plans: ["27-01-PLAN.md", "27-02-PLAN.md"]
      completed_by_plans: ["27-02-SUMMARY.md"]
      verification_status: "claimed passed — incorrect claim"
      evidence: "Phase 27 VERIFICATION.md marks AKEY-01 satisfied because `Tenant.keyPrefix` column is correctly stored (`updatable=false`, derived from tenant name). However, `ApiKeyService.generateSecureKey()` (line 89-93) returns 32-byte Base64 URL string. The `rawKey` presented to callers is NOT `PREFIX_UUID` format. `prefix = tenant.getKeyPrefix()` (line 43) is stored in the DB column but never prepended to `rawKey`. Callers receive opaque Base64 blobs (e.g. `xK7vP2...`) instead of `ACM_550e8400-e29b-41d4-a716-...`."
    - id: "TENT-02"
      status: "partial"
      phase: "Phase 28"
      claimed_by_plans: ["28-01-PLAN.md"]
      completed_by_plans: ["28-02-SUMMARY.md"]
      verification_status: "service layer verified; HTTP surface missing"
      evidence: "`TenantService.updateName()` implemented and tested via direct service injection. `TenantAdminResource` has no PATCH/PUT endpoint for name update. Admin cannot invoke this operation via HTTP. Planned for Phase 31."
    - id: "TENT-03"
      status: "partial"
      phase: "Phase 28"
      claimed_by_plans: ["28-01-PLAN.md"]
      completed_by_plans: ["28-02-SUMMARY.md"]
      verification_status: "service layer verified; HTTP surface missing"
      evidence: "`TenantService.updateEmail()` implemented. No HTTP endpoint. Admin cannot invoke via HTTP."
    - id: "TENT-04"
      status: "partial"
      phase: "Phase 28"
      claimed_by_plans: ["28-01-PLAN.md"]
      completed_by_plans: ["28-02-SUMMARY.md"]
      verification_status: "service layer verified; HTTP surface missing"
      evidence: "`TenantService.updateWebhookUrl()` implemented. No HTTP endpoint. Admin cannot invoke via HTTP."
    - id: "TENT-07"
      status: "partial"
      phase: "Phase 28"
      claimed_by_plans: ["28-01-PLAN.md"]
      completed_by_plans: ["28-02-SUMMARY.md"]
      verification_status: "service layer verified; HTTP surface missing"
      evidence: "`TenantService.suspend()` implemented with atomic bulk key revocation. No HTTP endpoint. Admin cannot invoke via HTTP."
    - id: "TENT-08"
      status: "partial"
      phase: "Phase 28"
      claimed_by_plans: ["28-01-PLAN.md"]
      completed_by_plans: ["28-02-SUMMARY.md"]
      verification_status: "service layer verified; HTTP surface missing"
      evidence: "`TenantService.reactivate()` implemented, issues new PROD key. No HTTP endpoint. Admin cannot invoke via HTTP."
    - id: "WSEC-03"
      status: "partial"
      phase: "Phase 28"
      claimed_by_plans: ["28-01-PLAN.md"]
      completed_by_plans: ["28-02-SUMMARY.md"]
      verification_status: "service layer verified; HTTP surface missing"
      evidence: "`TenantService.regenerateWebhookSecret()` implemented. No HTTP endpoint. Admin cannot invoke via HTTP."
  integration:
    - "AKEY-01: `ApiKeyService.generateSecureKey()` returns Base64, not PREFIX_UUID. `keyPrefix` stored in DB column correctly but not prepended to rawKey."
    - "ApiKeyBuilder (test builder) derives keyPrefix from rawKey.substring(0,8) — will produce incorrect keyPrefix column values once AKEY-01 format is fixed."
  flows:
    - "Lifecycle mutation flows (update name/email/webhookUrl, suspend, reactivate, regenerate secret): service layer complete but no HTTP entry point — admin cannot initiate these operations via REST."
    - "AKEY-01 key format flow: admin receives opaque Base64 key rather than human-readable PREFIX_UUID at creation, rotation, and reactivation events."
tech_debt:
  - phase: 28-service-layer
    items:
      - "TenantProvisioningIT.tearDown() does not clean audit tables (tenant_api_key_aud, tenant_aud, revinfo) — rows accumulate across test runs, no functional impact."
      - "ApiKeyAuthenticationFilter logs rawKey.substring(0,8) as debug prefix — will be semantically wrong once AKEY-01 format changes (currently harmless)."
  - phase: 27-schema-and-enum-migration
    items:
      - "ApiKeyBuilder test builder derives keyPrefix from rawKey.substring(0,8) instead of tenant-name-derived prefix — inconsistent with v5 keyPrefix semantics, will surface when AKEY-01 is fixed."
unverified_phases:
  - phase: 24-platform-configuration
    issue: "No VERIFICATION.md found. Phase is marked complete with 3/3 summaries. Implements Platform MSISDN management (validated in v4 per PROJECT.md — not in current REQUIREMENTS.md traceability)."
  - phase: 25-provider-health-indicators
    issue: "No VERIFICATION.md found. Phase is marked complete with 1/1 summary. Implements Spring Actuator health indicators (validated in v4)."
  - phase: 26-health-dashboard-ui
    issue: "No VERIFICATION.md found. Phase is marked complete with 1/1 summary. Implements Health Dashboard UI (validated in v4)."
nyquist:
  compliant_phases: [27, 28]
  partial_phases: []
  missing_phases: [18, 19, 20, 21, 22, 23, 24, 25, 26]
  overall: partial
---

# Milestone v1.0 Audit Report

**Milestone:** v1.0 Tenant & API Key Management Service Layer
**Audited:** 2026-04-06
**Status:** gaps_found
**Phases in scope:** 18–28 (11 phases, 25 plans)
**Requirements in scope:** 17 (AKEY-01..08, TENT-01..08, WSEC-01/03, AUDIT-01..03 from REQUIREMENTS.md traceability for phases 27-28)

---

## Requirements Coverage (3-Source Cross-Reference)

| Requirement | Phase | VERIFICATION.md | SUMMARY Frontmatter | REQUIREMENTS.md | Final Status |
|-------------|-------|-----------------|--------------------|-----------------|----|
| AKEY-01 | 27 | Claimed satisfied (incorrect) | listed | `[ ]` (Pending) | **UNSATISFIED** |
| AKEY-02 | 28 | Passed | listed | `[x]` | satisfied |
| AKEY-03 | 27 | Passed | listed | `[ ]` (Pending) | **satisfied** (checkbox stale) |
| AKEY-04 | 28 | Passed | listed | `[x]` | satisfied |
| AKEY-06 | 28 | Passed | listed | `[x]` | satisfied |
| AKEY-08 | 28 | Passed | listed | `[x]` | satisfied |
| TENT-01 | 28 | Passed | listed | `[x]` | satisfied |
| TENT-02 | 28 | Passed (service) | listed | `[x]` | **partial** (no HTTP endpoint) |
| TENT-03 | 28 | Passed (service) | listed | `[x]` | **partial** (no HTTP endpoint) |
| TENT-04 | 28 | Passed (service) | listed | `[x]` | **partial** (no HTTP endpoint) |
| TENT-07 | 28 | Passed (service) | listed | `[x]` | **partial** (no HTTP endpoint) |
| TENT-08 | 28 | Passed (service) | listed | `[x]` | **partial** (no HTTP endpoint) |
| WSEC-01 | 28 | Passed | listed | `[x]` | satisfied |
| WSEC-03 | 28 | Passed (service) | listed | `[x]` | **partial** (no HTTP endpoint) |
| AUDIT-01 | 28 | Passed | listed | `[x]` | satisfied |
| AUDIT-02 | 28 | Passed | listed | `[x]` | satisfied |
| AUDIT-03 | 28 | Passed | listed | `[x]` | satisfied |

**Satisfied: 10/17 (fully) | Partial: 6/17 (service done, HTTP missing) | Unsatisfied: 1/17 (AKEY-01 format wrong)**

---

## Phase Verification Status

| Phase | Name | Plans | VERIFICATION.md | Status |
|-------|------|-------|-----------------|--------|
| 18 | Test Infrastructure | 2/2 | ✓ | 5/5 passed |
| 19 | Verifiers + Test Data Builders | 2/2 | ✓ | 12/12 passed |
| 20 | Payment Flow Tests | 2/2 | ✓ | 9/9 passed |
| 21 | Webhook Flow Tests | 2/2 | ✓ | 4/4 passed |
| 22 | Fraud, Reconciliation, Admin Tests | 2/2 | ✓ | 4/4 passed |
| 23 | Invariants, Concurrency, SM, Mutation | 5/5 | ✓ | 5/5 passed |
| 24 | Platform Configuration | 3/3 | **MISSING** | Unverified |
| 25 | Provider Health Indicators | 1/1 | **MISSING** | Unverified |
| 26 | Health Dashboard UI | 1/1 | **MISSING** | Unverified |
| 27 | Schema and Enum Migration | 2/2 | ✓ | 5/5 passed (AKEY-01 claim incorrect) |
| 28 | Service Layer | 2/2 | ✓ | 20/20 passed |

Note: Phases 24/25/26 implement features validated in the prior milestone (v4) per PROJECT.md. Their requirements are not in the current REQUIREMENTS.md traceability table. Missing VERIFICATION.md is a documentation gap, not a functional regression risk — the features were built before the current requirements scope was defined.

---

## Cross-Phase Integration Findings

### Solid Wiring (12 integration points verified)

1. **Phase 27 `Tenant.keyPrefix` (immutable) → Phase 28 `ApiKeyService.generateAndStore()`** — `keyPrefix` stored correctly with `updatable=false`, no setter. `ApiKeyService` reads it and stores in `TenantApiKey.keyPrefix`. ✓
2. **Phase 27 partial unique index → Phase 28 AKEY-02 guard** — dual enforcement: DB `uidx_tenant_api_key_active_env` + service `findActiveKeyByTenantIdAndEnvironment()` guard. ✓
3. **Phase 27 `ApiKeyEnvironment` enum → Phase 28 service parameters** — all service methods typed; `TenantAdminResource` validates and parses via enum; V19 CHECK constraint mirrors enum values. ✓
4. **Phase 28 V20 DDL → all 3 YAML profiles** — `envers.default_schema: main` in application.yaml, application-dev.yaml, application-uat.yaml. ✓
5. **`TenantService.suspend()` → bulk `@Modifying` JPQL in one transaction** — atomic key revocation. ✓
6. **AKEY-08 rotate() `saveAndFlush` ordering** — prevents partial unique index violation from Hibernate batch ordering. ✓
7. **Envers admin identity chain** — `SecurityContextHolder` → `SpringSecurityAuditorAware` → `AuditingEntityListener` → `created_by` in audit tables. ✓
8. **`TenantService.reactivate()` → `ApiKeyService.generateAndStore(PROD)`** — correct delegation pattern, no duplication. ✓
9. **All 3 existing HTTP endpoints `@PreAuthorize(HAS_ADMIN_ROLE)`** — auth guard solid. ✓
10. **E2E flow: create tenant** — `POST /v1/admin/tenants` → `TenantService.createTenant()` → key + webhookSecret returned. ✓
11. **E2E flow: rotate key** — `POST /v1/admin/tenants/{id}/keys/{keyId}/rotate` → `ApiKeyService.rotate()` → AKEY-08 prior-ROTATED revocation. ✓
12. **E2E flow: revoke key** — `DELETE /v1/admin/tenants/{id}/keys/{keyId}` → `ApiKeyService.revoke()`. ✓

### Gaps (2 integration issues)

**Gap 1 — AKEY-01: Raw key format is Base64, not PREFIX_UUID (blocker)**

`ApiKeyService.generateSecureKey()` returns 32-byte Base64 URL-encoded string. The `prefix` from `tenant.getKeyPrefix()` is stored in the `key_prefix` column but NOT prepended to `rawKey`. Callers receive opaque Base64 at create/rotate/reactivate time instead of `ACM_550e8400-...` format.

- **Break:** `ApiKeyService.java:89-93` — `generateSecureKey()` must return `tenant.getKeyPrefix() + "_" + UUID.randomUUID()`
- **Secondary:** `ApiKeyBuilder.java:63` — `keyPrefix` derived from `rawKey.substring(0,8)` (wrong under v5 semantics)
- **Affected requirements:** AKEY-01

**Gap 2 — 6 TenantService lifecycle methods have no HTTP endpoints**

`updateName`, `updateEmail`, `updateWebhookUrl`, `suspend`, `reactivate`, `regenerateWebhookSecret` — all fully implemented and tested via direct service injection. `TenantAdminResource` exposes none of them. Admin cannot invoke via REST.

- **Break:** `TenantAdminResource.java` — missing PATCH/POST endpoint handlers
- **Consistent with:** Phase 31 (Admin UI tenant management) planned in roadmap
- **Affected requirements:** TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, WSEC-03

---

## E2E Flow Status

| Flow | Status | Breaks At |
|------|--------|-----------|
| Create tenant (with initial PROD key + webhookSecret) | ✓ Complete | — |
| Rotate key (AKEY-08 prior-ROTATED revocation) | ✓ Complete | — |
| Revoke key (immediate) | ✓ Complete | — |
| Authenticate with rotated key in grace period | ✓ Complete | — |
| Admin update tenant (name/email/webhookUrl) | Partial | No HTTP endpoint (Phase 31) |
| Admin suspend/reactivate tenant | Partial | No HTTP endpoint (Phase 31) |
| Key format validation (PREFIX_UUID) | Broken | `generateSecureKey()` returns Base64 |

---

## Nyquist Compliance

| Phase | VALIDATION.md | Compliant | Note |
|-------|---------------|-----------|------|
| 18–26 | MISSING | N/A | Phases without VALIDATION.md |
| 27 | ✓ Exists | Compliant | Schema migration validated |
| 28 | ✓ Exists | Compliant | Service layer validated |

Phases 18-26 have no VALIDATION.md. Nyquist validation (`/gsd:validate-phase`) not run for these phases.

---

## Tech Debt Summary

| Phase | Item | Severity |
|-------|------|----------|
| 28 | `TenantProvisioningIT.tearDown()` doesn't clean audit tables | Info |
| 28 | `ApiKeyAuthenticationFilter` logs `rawKey.substring(0,8)` as debug prefix (wrong semantics post-AKEY-01 fix) | Low |
| 27 | `ApiKeyBuilder` derives `keyPrefix` from `rawKey.substring(0,8)` — will be wrong when AKEY-01 format is fixed | Low |

---

## Audit Conclusion

**Status: `gaps_found`**

One genuine requirement defect (AKEY-01 raw key format) and one planned gap (HTTP surface for 6 lifecycle operations, deferred to Phase 31). The service layer, audit trail, schema, and test coverage for Phase 27/28 are otherwise well-implemented. The AKEY-01 fix is a targeted change to `generateSecureKey()` and `ApiKeyBuilder`.

Phases 18-23 (test infrastructure and E2E testing) verified and passed. Phases 24-26 are missing VERIFICATION.md but implement features from a prior milestone scope.

---

*Audited: 2026-04-06 | Auditor: Claude (gsd-audit-milestone)*
