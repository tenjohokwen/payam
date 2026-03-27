---
phase: 22-fraud-recon-admin-tests
plan: "02"
subsystem: testing
tags: [spring-boot-test, mockbean, reconciliation, admin, jdbc, tenant-isolation, e2e]

# Dependency graph
requires:
  - phase: 18-test-infrastructure
    provides: AbstractPayamE2ETest, TestDataCleaner, E2ESecurityConfig
  - phase: 19-verifiers-builders
    provides: TenantBuilder, AdminLogin

provides:
  - FLOWS-RECON-01: matchedTransaction — 0 discrepancies when Payam SUCCESS and provider SUCCESSFUL agree
  - FLOWS-RECON-02: missingTransaction — 1 MISSING_IN_PROVIDER discrepancy when provider rawStatus is null
  - FLOWS-RECON-03: mismatchedTransaction — 1 STATUS_MISMATCH discrepancy when Payam SUCCESS vs provider FAILED
  - FLOWS-RECON-04: watTimestampBoundary — T23:30:00Z tx in YESTERDAY window; 0 rows in TODAY report
  - FLOWS-ADMIN-01: admin search by transactionId, externalReference, traceId; tenant isolation via tenantId Long PK
  - DailyReconciliationE2ETest.java — reconciliation E2E test class extending AbstractPayamE2ETest
  - TransactionInvestigationE2ETest.java — admin transaction investigation E2E test class

affects:
  - Phase 22 complete: full 9-test suite (1 fraud + 4 reconciliation + 4 admin) all passing

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@MockBean MtnMoMoPort/OrangeMoneyPort for reconciliation tests — prevents real HTTP calls; rawStatus null triggers MISSING_IN_PROVIDER path"
    - "transactionTemplate.execute() wraps all JDBC inserts for FK constraint satisfaction in admin user seeding"
    - "URI.create() with manual %2B encoding for + in query params — RestTemplate.exchange(URI) sends as-is; string URL templates double-encode"
    - "discrepancy_type is the correct column name in reconciliation_discrepancy (not 'type')"

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/reconciliation/DailyReconciliationE2ETest.java
    - src/test/java/com/softropic/payam/e2e/admin/TransactionInvestigationE2ETest.java
  modified: []

key-decisions:
  - "ProviderResult(null, null, false, null, null) triggers MISSING_IN_PROVIDER: MtnReportAdapter passes result.rawStatus() as providerStatus; null rawStatus → null providerStatus → MISSING_IN_PROVIDER path in ReconciliationService.compareTransaction()"
  - "ProviderResult.success('ref', 'FAILED') triggers STATUS_MISMATCH: rawStatus='FAILED' is terminal; Payam SUCCESS vs provider FAILED are both terminal and do not match"
  - "Admin tenantId param is Long (database PK), not UUID string: AdminTransactionQueryService.search() and TransactionRepository.adminSearch() accept Long tenantId"
  - "URI.create() for externalReference with + character: RestTemplate.exchange(String url) re-encodes %2B to %252B; URI.create() passes URL as-is to http connection"
  - "transactionTemplate.execute() wraps all admin user seeding: bare jdbcTemplate.execute() for user_authority before authority FK constraint is committed causes DataIntegrityViolationException"
  - "FLOWS-RECON-04 WAT boundary: reconciliation uses LedgerSnapshotService UTC window [YESTERDAY 00:00Z, TODAY 00:00Z); T23:30:00Z is in YESTERDAY window regardless of provider; Orange WAT parsing (OrangeTimeUtil) is a webhook ingest concern, not a reconciliation concern"

patterns-established:
  - "DailyReconciliationE2ETest direct call pattern: extend AbstractPayamE2ETest, @MockBean provider ports, JDBC seed, call reconciliationService.runForDate() directly"
  - "Admin search with + in query param: URI.create() with manual + → %2B replacement"

# Metrics
duration: ~25min
completed: 2026-03-28
---

# Phase 22 Plan 02: Reconciliation and Admin Flow Tests Summary

**Daily reconciliation E2E tests (4 scenarios) and admin transaction investigation E2E test (4 scenarios) — @MockBean provider ports for reconciliation, JWT admin auth for admin search, all 8 tests passing**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-03-27T22:45:00Z
- **Completed:** 2026-03-28T00:05:00Z
- **Tasks:** 2
- **Files created:** 2

## Accomplishments

- **FLOWS-RECON-01** (matchedTransaction): reconciliationService.runForDate() produces 0 discrepancy rows when MTN provider returns SUCCESSFUL matching Payam SUCCESS
- **FLOWS-RECON-02** (missingTransaction): 1 MISSING_IN_PROVIDER discrepancy row produced when MtnMoMoPort returns ProviderResult with null rawStatus
- **FLOWS-RECON-03** (mismatchedTransaction): 1 STATUS_MISMATCH discrepancy row produced when provider returns rawStatus="FAILED" against Payam SUCCESS
- **FLOWS-RECON-04** (watTimestampBoundary): Transaction at YESTERDAY+T23:30:00Z is in YESTERDAY's reconciliation window (total_checked=1 on MTN report); 0 rows on TODAY's report confirming UTC boundary [YESTERDAY 00:00Z, TODAY 00:00Z)
- **FLOWS-ADMIN-01** (searchByTransactionId): Admin GET /v1/admin/transactions?transactionId=X returns 1 result
- **FLOWS-ADMIN-01** (searchByExternalReference): Admin GET with externalReference=%2B237672000099 returns 1 result; URI.create() encoding resolves + ambiguity
- **FLOWS-ADMIN-01** (searchByTraceId): Admin GET with traceId=X returns 1 result
- **FLOWS-ADMIN-01** (tenantIsolation): Admin GET with tenantB Long PK returns 0 rows; same with tenantA Long PK returns 1 row
- Full phase-22 suite: 9/9 tests passing (1 fraud + 4 reconciliation + 4 admin)

## Task Commits

Each task committed atomically:

1. **Task 1: DailyReconciliationE2ETest** — `47cb682` (feat)
2. **Task 2: TransactionInvestigationE2ETest** — `d29d340` (feat)

**Plan metadata:** (in this commit)

## Files Created/Modified

- `src/test/java/com/softropic/payam/e2e/reconciliation/DailyReconciliationE2ETest.java` — FLOWS-RECON-01 through FLOWS-RECON-04: @MockBean provider ports, JDBC transaction seeding, direct runForDate() call
- `src/test/java/com/softropic/payam/e2e/admin/TransactionInvestigationE2ETest.java` — FLOWS-ADMIN-01: AdminLogin.loginAsAdmin, noRetryRestTemplate, search by transactionId/externalReference/traceId/tenantId, tenant isolation

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] discrepancy_type vs type column name**

- **Found during:** Task 1 (missingTransaction/mismatchedTransaction tests)
- **Issue:** Plan code samples used `AND type = 'MISSING_IN_PROVIDER'` but the actual column in `main.reconciliation_discrepancy` is `discrepancy_type` per the JPA `@Column(name = "discrepancy_type")` annotation
- **Fix:** Changed SQL queries to use `AND discrepancy_type = 'MISSING_IN_PROVIDER'` and `AND discrepancy_type = 'STATUS_MISMATCH'`
- **Files modified:** DailyReconciliationE2ETest.java
- **Commit:** 47cb682

**2. [Rule 1 - Bug] Admin user seeding FK constraint violation**

- **Found during:** Task 2 (all tests)
- **Issue:** Plain `jdbcTemplate.execute()` calls for authority + user + user_authority ran as separate auto-commit statements; inserting `user_authority` before `authority` row was visible (or committed) caused FK constraint violation: `INSERT INTO main.user_authority ... violates foreign key constraint "fkgvxjs381k6f48d5d2yi11uh89"`
- **Fix:** Wrapped all 5 seeding inserts inside a single `transactionTemplate.execute()` block
- **Files modified:** TransactionInvestigationE2ETest.java
- **Commit:** d29d340

**3. [Rule 1 - Bug] URL encoding of + character in externalReference query param**

- **Found during:** Task 2 (searchByExternalReference test)
- **Issue:** `+237672000099` passed as query param caused server to decode `+` as space, making the query `externalReference=' 237672000099'` which didn't match the stored value. Multiple encoding approaches tried: URLEncoder (RestTemplate double-encodes %2B to %252B), UriComponentsBuilder.queryParam() (leaves + as +), java.net.URI constructor (RestTemplate still re-encodes), encode() method (does not encode + in query strings per RFC)
- **Fix:** Used `URI.create()` with manual `+` → `%2B` replacement in the raw URL string. RestTemplate.exchange(URI) passes the URI to the HTTP connection as-is without re-encoding
- **Files modified:** TransactionInvestigationE2ETest.java
- **Commit:** d29d340

**4. [Rule 1 - Bug] MISSING_IN_PROVIDER trigger: null rawStatus on ProviderResult**

- **Found during:** Task 1 (missingTransaction test — plan suggested ProviderResult.notFound() which doesn't exist)**
- **Issue:** `ProviderResult` does not have a `notFound()` factory method. The plan suggested checking the API, which showed: MtnReportAdapter.fetchProviderRecord() uses `result.rawStatus()` as providerStatus; ReconciliationService.compareTransaction() checks `record.providerStatus() == null` for MISSING_IN_PROVIDER
- **Fix:** Used `new ProviderResult(null, null, false, null, null)` so rawStatus() returns null → providerStatus is null → MISSING_IN_PROVIDER path triggered
- **Files modified:** DailyReconciliationE2ETest.java
- **Commit:** 47cb682

**5. [Rule 1 - Bug] Admin tenantId param is Long, not UUID**

- **Found during:** Task 2 (tenantIsolation test — plan suggested using tenantRef UUID string)**
- **Issue:** Plan suggested `"/v1/admin/transactions?tenantId=" + tenantBRef` using UUID tenantRef. Actual `AdminTransactionResource.search()` declares `@RequestParam(required = false) Long tenantId` — the param is a Long database PK, not a UUID string
- **Fix:** Used `tenantA.tenantId()` and `tenantB.tenantId()` (Long PKs) from TenantBuilder.CreatedTenant
- **Files modified:** TransactionInvestigationE2ETest.java
- **Commit:** d29d340

## Decisions Made

| ID | Decision | Rationale |
|----|----------|-----------|
| 22-02-A | ProviderResult(null, null, false, null, null) as notFound sentinel | No factory method exists; null rawStatus is what MtnReportAdapter passes as providerStatus to ReconciliationService |
| 22-02-B | URI.create() for + in query params | Only approach that sends %2B without RestTemplate re-encoding; other approaches (URLEncoder, UriComponentsBuilder, new URI()) all fail in different ways |
| 22-02-C | Admin tenantId as Long PK not UUID | AdminTransactionResource.search() @RequestParam type is Long; JPQL compares t.tenantId (Long PK) directly |
| 22-02-D | transactionTemplate wraps all admin user seeding | Prevents FK constraint errors on user_authority → authority FK; bare jdbcTemplate.execute() runs as auto-commit which may not see uncommitted authority rows |

## Next Phase Readiness

Phase 22 is now complete:
- Plan 22-01: FraudVelocityBlockE2ETest (FLOWS-FRAUD-01/02/03) — already present and passing
- Plan 22-02: DailyReconciliationE2ETest + TransactionInvestigationE2ETest — implemented in this plan
- All 9 phase-22 tests pass together: 9/9 BUILD SUCCESS

No blockers for subsequent phases.
