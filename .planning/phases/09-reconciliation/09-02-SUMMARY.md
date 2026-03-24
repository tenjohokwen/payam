---
phase: 09-reconciliation
plan: "02"
subsystem: api
tags: [spring-boot, quasar, vue3, rest, csv-export, json-export, jwt, role-admin, flyway, reconciliation]

# Dependency graph
requires:
  - phase: 09-01
    provides: ReconciliationReport + ReconciliationDiscrepancy JPA entities, repositories, ReconciliationService, Quartz job

provides:
  - "GET /v1/admin/reconciliation/reports — paginated run history (JWT+ROLE_ADMIN)"
  - "GET /v1/admin/reconciliation/reports/{id}/discrepancies — all discrepancy rows for a run"
  - "GET /v1/admin/reconciliation/reports/{id}/export?format=csv|json — file download"
  - "ReconciliationExportService — CSV and JSON byte[] generation"
  - "ReconciliationPage.vue — Quasar SPA page with run history + discrepancy tables + export button"
  - "V13 migration — idx_recon_discrepancy_report_id index for fast lookup"

affects:
  - phase-10-hardening

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Export service pattern: toCsv()/toJson() producing byte[] for ResponseEntity<byte[]> download endpoints"
    - "Real-login IT auth pattern: seed user + POST /authenticate + forward Set-Cookie on admin requests"
    - "FilterRegistrationBean(setEnabled=false) to prevent OncePerRequestFilter double-registration"

key-files:
  created:
    - src/main/resources/db/migration/V13__reconciliation_export_index.sql
    - src/main/java/com/softropic/payam/reconciliation/contract/ReconciliationReportDto.java
    - src/main/java/com/softropic/payam/reconciliation/contract/ReconciliationDiscrepancyDto.java
    - src/main/java/com/softropic/payam/reconciliation/service/ReconciliationExportService.java
    - src/main/java/com/softropic/payam/reconciliation/api/ReconciliationResource.java
    - src/frontend/src/pages/admin/ReconciliationPage.vue
    - src/test/java/com/softropic/payam/reconciliation/ReconciliationApiIT.java
  modified:
    - src/frontend/src/api/admin.api.js
    - src/frontend/src/router/routes.js
    - src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java
    - src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java
    - src/test/java/com/softropic/payam/tenant/TenantFilterChainIT.java

key-decisions:
  - "09-02 decision: ReconciliationApiIT uses real /authenticate login flow — seeds admin user, POSTs credentials, extracts Set-Cookie, forwards on admin requests; avoids hand-crafted JWT issues"
  - "09-02 decision: FilterRegistrationBean(setEnabled=false) added to TenantSecurityConfig — prevents ApiKeyAuthenticationFilter from running as servlet-registered filter on ALL requests (including /v1/admin/**); filter now only runs within tenantApiKeyFilterChain scope"
  - "09-02 decision: /v1/admin/** added to ApiKeyAuthenticationFilter shouldNotFilter bypass list — defence-in-depth alongside securityMatcher exclusion already in TenantSecurityConfig"
  - "09-02 decision: TenantFilterChainIT updated to use /v1/webhooks/deliveries/tx-123 instead of /v1/admin/tenants — admin paths now excluded from API-key chain; POST /v1/admin/tenants would bypass filter and return different status"
  - "09-02 decision: @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE) retained on ReconciliationResource — enforces ROLE_ADMIN/ROLE_LTD_ADMIN; without it, ROLE_USER callers could access reconciliation data"

patterns-established:
  - "Export endpoint pattern: ResponseEntity<byte[]> with Content-Disposition + MediaType + exportService.toCsv/toJson()"
  - "IT test real-login pattern: loginAsAdmin() helper seeds user+authorities, POSTs /authenticate, extracts Set-Cookie header values"

# Metrics
duration: 35min
completed: 2026-03-25
---

# Phase 9 Plan 02: Reconciliation Admin API Surface Summary

**Three admin REST endpoints + CSV/JSON export service + ReconciliationPage.vue Quasar SPA page, all JWT+ROLE_ADMIN protected, with 5/5 integration tests passing**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-03-25T00:20:00Z
- **Completed:** 2026-03-25T00:56:00Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments

- Three admin REST endpoints under `/v1/admin/reconciliation`: list runs (paginated), list discrepancies, CSV/JSON export download
- `ReconciliationExportService` producing UTF-8 CSV (comma-escaped) and JSON (reportDate + provider + summary + discrepancies array)
- `ReconciliationPage.vue` with run history `q-table`, discrepancy `q-table`, and CSV/JSON download buttons triggering blob-URL download
- V13 Flyway migration adding `idx_recon_discrepancy_report_id` index for fast discrepancy lookup by report
- `ReconciliationApiIT` 5/5 tests: auth enforcement, paginated list, discrepancy listing, CSV export content-type+header, JSON export keys

## Task Commits

Each task was committed atomically:

1. **Task 1: V13 migration + DTO contracts + ReconciliationExportService + ReconciliationResource** - `55c9d63` (feat)
2. **Task 2: Quasar ReconciliationPage + admin.api.js + routes + ReconciliationApiIT** - `f301ccd` (feat)

**Plan metadata:** (see docs commit below)

## Files Created/Modified

- `src/main/resources/db/migration/V13__reconciliation_export_index.sql` - Index on reconciliation_discrepancy(report_id)
- `src/main/java/com/softropic/payam/reconciliation/contract/ReconciliationReportDto.java` - Record DTO with from(entity) factory
- `src/main/java/com/softropic/payam/reconciliation/contract/ReconciliationDiscrepancyDto.java` - Record DTO with from(entity) factory
- `src/main/java/com/softropic/payam/reconciliation/service/ReconciliationExportService.java` - toCsv() and toJson() byte[] generation
- `src/main/java/com/softropic/payam/reconciliation/api/ReconciliationResource.java` - Three admin GET endpoints, @PreAuthorize(HAS_ADMIN_ROLE)
- `src/frontend/src/pages/admin/ReconciliationPage.vue` - Quasar page with two tables and export buttons
- `src/frontend/src/api/admin.api.js` - Added listReconciliationReports, getReconciliationDiscrepancies, exportReconciliationReport
- `src/frontend/src/router/routes.js` - Added admin/reconciliation route
- `src/test/java/com/softropic/payam/reconciliation/ReconciliationApiIT.java` - 5 integration tests
- `src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java` - Added FilterRegistrationBean to prevent double-registration
- `src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java` - Added /v1/admin/** to bypass patterns
- `src/test/java/com/softropic/payam/tenant/TenantFilterChainIT.java` - Updated to use non-admin endpoint for API-key chain tests

## Decisions Made

- **Real-login IT auth pattern**: `ReconciliationApiIT.loginAsAdmin()` seeds a real admin user, POSTs to `/authenticate`, extracts JWT cookies from `Set-Cookie`, and forwards them on subsequent admin requests. This ensures the full JWT filter chain is exercised and avoids hand-crafted JWT fragility.
- **FilterRegistrationBean fix**: `ApiKeyAuthenticationFilter` is defined as `@Bean` not `@Component`, but Spring Boot was still auto-registering it as a servlet filter. Added `FilterRegistrationBean(setEnabled=false)` to prevent double execution on all requests.
- **@PreAuthorize retained on ReconciliationResource**: The JWT chain itself only requires any authenticated user for `/v1/**`; `@PreAuthorize(HAS_ADMIN_ROLE)` is needed to enforce ROLE_ADMIN/ROLE_LTD_ADMIN specifically on reconciliation endpoints.
- **TenantFilterChainIT endpoint change**: Tests changed from `POST /v1/admin/tenants` to `GET /v1/webhooks/deliveries/tx-123` since admin paths are now explicitly excluded from the API-key filter chain scope.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Restored @PreAuthorize annotation removed from ReconciliationResource working copy**

- **Found during:** Task 2 verification
- **Issue:** Working copy had `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` removed from `ReconciliationResource`, creating a security regression — ROLE_USER callers could access reconciliation admin endpoints
- **Fix:** Restored import and annotation, matching the committed HEAD version
- **Files modified:** `src/main/java/com/softropic/payam/reconciliation/api/ReconciliationResource.java`
- **Verification:** `grep PreAuthorize` confirms annotation present; `ReconciliationApiIT.listReports_requiresAuth` asserts 401/403 without credentials
- **Committed in:** Working tree correction before `f301ccd`

**2. [Rule 2 - Missing Critical] Added FilterRegistrationBean to prevent ApiKeyFilter double-registration**

- **Found during:** Task 2 (security context analysis while adding /v1/admin/** bypass)
- **Issue:** `ApiKeyAuthenticationFilter` defined as `@Bean` was still being auto-registered by Spring Boot servlet container, causing it to run on ALL requests including `/v1/admin/**` despite securityMatcher exclusion
- **Fix:** Added `FilterRegistrationBean(setEnabled=false)` in `TenantSecurityConfig` to disable servlet auto-registration
- **Files modified:** `src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java`
- **Verification:** `ReconciliationApiIT` 5/5 tests pass including auth enforcement; `TenantFilterChainIT` tests updated and passing
- **Committed in:** `f301ccd`

---

**Total deviations:** 2 auto-fixed (1 security bug restoration, 1 missing critical filter config)
**Impact on plan:** Both fixes essential for security correctness. No scope creep.

## Issues Encountered

None beyond the deviations documented above.

## Next Phase Readiness

- Phase 9 (Reconciliation) complete: Quartz job (09-01) + admin surface (09-02) both done
- Finance team can view daily reconciliation results and download CSV/JSON reports
- All 5 phase success criteria satisfied: Quartz job + provider verification + discrepancy flagging + dashboard surface + WAT timestamp handling
- Phase 10 (hardening) can proceed; no blockers from Phase 9

---
*Phase: 09-reconciliation*
*Completed: 2026-03-25*
