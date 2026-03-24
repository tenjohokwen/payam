---
phase: 08-admin-dashboard
plan: 01
subsystem: admin-security-endpoints
tags: [spring-security, jwt, admin-api, jpa, flyway, rest]

dependency-graph:
  requires:
    - "07-02: FraudEngine wired into orchestrator (riskScore on Transaction)"
    - "01-02: TenantSecurityConfig API-key chain established"
    - "02-01: Transaction entity and TransactionRepository"
    - "02-02: PaymentEventLog and PaymentEventLogRepository"
  provides:
    - "NegatedRequestMatcher excludes /v1/admin/** from API-key chain"
    - "Flyway V11: idx_transaction_trace_id on main.transaction"
    - "GET /v1/admin/transactions (paginated search, all params optional)"
    - "GET /v1/admin/transactions/{id}/events (full event timeline ASC)"
    - "countByTxStatus() on TransactionRepository for Phase 8 metrics gauge"
  affects:
    - "08-02: PaymentMetricsService uses countByTxStatus()"
    - "08-03: AdminLoginResource already exists; these endpoints complete the admin API surface"

tech-stack:
  added: []
  patterns:
    - "NegatedRequestMatcher(OrRequestMatcher) for multi-path API-key chain exclusion"
    - "Java records as DTOs (TransactionSummaryDto, EventLogEntryDto, TransactionDetailDto)"
    - "@PreAuthorize at class level for uniform admin authorization"
    - "@Transactional(readOnly=true) service with Page<T> mapping"

key-files:
  created:
    - src/main/resources/db/migration/V11__admin_search_index.sql
    - src/main/java/com/softropic/payam/admin/contract/TransactionSummaryDto.java
    - src/main/java/com/softropic/payam/admin/contract/EventLogEntryDto.java
    - src/main/java/com/softropic/payam/admin/contract/TransactionDetailDto.java
    - src/main/java/com/softropic/payam/admin/service/AdminTransactionQueryService.java
    - src/main/java/com/softropic/payam/admin/api/AdminTransactionResource.java
  modified:
    - src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java
    - src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java

decisions:
  - id: "08-01-A"
    decision: "NegatedRequestMatcher wraps OrRequestMatcher with both /v1/account/** and /v1/admin/**"
    rationale: "Single NegatedRequestMatcher(OrRequestMatcher(...)) is cleaner than chained AND-NOT-AND-NOT; matches plan spec exactly"
  - id: "08-01-B"
    decision: "statusFrom null-safe in EventLogEntryDto mapping (returns null string, not NPE)"
    rationale: "PaymentEventLog.statusFrom is nullable (initial INITIATED event has no prior status); mapping must handle null"
  - id: "08-01-C"
    decision: "adminSearch JPQL uses ORDER BY in query, not PageRequest sort"
    rationale: "Plan spec uses ORDER BY t.createdDate DESC inline; PageRequest.of(page, size) without sort keeps query as written"

metrics:
  duration: "~8 min"
  completed: "2026-03-24"
---

# Phase 8 Plan 01: Admin REST Endpoints Summary

**One-liner:** JWT-only admin investigation API with NegatedRequestMatcher security fix, V11 trace_id index, paginated transaction search, and full event timeline endpoint.

## What Was Built

Two read-only admin endpoints at `/v1/admin/transactions`, protected by JWT + ROLE_ADMIN:

- `GET /v1/admin/transactions` — paginated cross-tenant search with optional filters (transactionId, traceId, externalReference, tenantId)
- `GET /v1/admin/transactions/{transactionId}/events` — full event timeline for a single transaction, ordered ASC

The critical security fix: `TenantSecurityConfig` was intercepting `/v1/admin/**` with the API-key chain (`@Order(1)`), causing every admin JWT request to return 401. The fix uses `NegatedRequestMatcher(OrRequestMatcher(...))` to exclude both `/v1/account/**` and `/v1/admin/**` from the API-key chain scope, allowing the JWT chain to handle admin routes.

## Decisions Made

| Decision | What | Why |
|----------|------|-----|
| 08-01-A | OrRequestMatcher wraps both exclusions in single NegatedRequestMatcher | Matches plan spec; avoids chained AND-NOT-AND-NOT pattern |
| 08-01-B | statusFrom null-safe in toEventEntry mapping | PaymentEventLog.statusFrom is nullable; NPE would break timeline endpoint |
| 08-01-C | ORDER BY in JPQL, not PageRequest sort | Plan specifies ORDER BY in query text; PageRequest.of without sort preserves that ordering |

## Files Created/Modified

| File | Action | Purpose |
|------|--------|---------|
| TenantSecurityConfig.java | Modified | NegatedRequestMatcher(OrRequestMatcher) excludes /v1/admin/** |
| V11__admin_search_index.sql | Created | idx_transaction_trace_id on main.transaction |
| TransactionRepository.java | Modified | adminSearch() JPQL + countByTxStatus() |
| TransactionSummaryDto.java | Created | Record: 9 fields, no internal Transaction fields |
| EventLogEntryDto.java | Created | Record: 7 fields from PaymentEventLog |
| TransactionDetailDto.java | Created | Record: summary + events list |
| AdminTransactionQueryService.java | Created | search() + detail() with read-only @Transactional |
| AdminTransactionResource.java | Created | REST controller with class-level @PreAuthorize(HAS_ADMIN_ROLE) |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Null-safe statusFrom in EventLogEntryDto mapping**

- **Found during:** Task 2, Step 3
- **Issue:** Plan's toEventEntry() called `e.getStatusFrom().name()` directly — but PaymentEventLog.statusFrom is nullable (the genesis INITIATED event has no prior status)
- **Fix:** Added null check: `e.getStatusFrom() != null ? e.getStatusFrom().name() : null`
- **Files modified:** AdminTransactionQueryService.java
- **Commit:** 1886a33

No other deviations — plan executed as specified.

## Verification Results

All success criteria met:

- TenantSecurityConfig: NegatedRequestMatcher(OrRequestMatcher) excludes /v1/account/** AND /v1/admin/**
- V11 migration: CREATE INDEX idx_transaction_trace_id confirmed
- AdminTransactionResource: @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE) at class level
- TransactionSummaryDto: does NOT contain payToken, pollAttempts, deviceFingerprint
- Event timeline: findByTransactionIdOrderByCreatedDateAsc (ASC order)
- mvn compiler:compile: BUILD SUCCESS (11s, no errors)

## Next Phase Readiness

Phase 8 Plan 02 (PaymentMetricsService) can proceed:

- `countByTxStatus(TransactionStatus)` is available on TransactionRepository
- Admin package structure (`com.softropic.payam.admin.*`) established
- Security chain correctly routes /v1/admin/** to JWT chain
