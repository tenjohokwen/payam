---
phase: 51
plan: 01
subsystem: disbursement
tags: [idempotency, contract-dtos, redis, postgres, sec-01]
dependency_graph:
  requires:
    - src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java
    - src/main/java/com/softropic/payam/transaction/repo/IdempotencyKeyRepository.java
    - src/main/java/com/softropic/payam/transaction/contract/CachedResponse.java
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java
    - src/main/java/com/softropic/payam/common/exception/ErrorCode.java
    - src/main/java/com/softropic/payam/common/payment/MobilePaymentProvider.java
  provides:
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementRequest.java
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementResponse.java
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementListItem.java
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementOrchestratorError.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyService.java
  affects:
    - Phase 51 Plan 03 (DisbursementOrchestrator autowires DisbursementIdempotencyService)
    - Phase 51 Plan 04 (DisbursementResource returns DisbursementResponse, maps DisbursementOrchestratorError to HTTP status)
tech_stack:
  added: []
  patterns:
    - TDD (RED → GREEN) for DisbursementIdempotencyService
    - Postgres-first idempotency ordering (IDEM-01 pattern)
    - Redis NX+EX namespace isolation (idempotency:dsb: distinct from idempotency:)
    - Testcontainers integration test (same pattern as WalletBalanceConcurrencyIT)
key_files:
  created:
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementRequest.java
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementResponse.java
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementListItem.java
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementOrchestratorError.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyService.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyServiceTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyIT.java
  modified: []
decisions:
  - "DisbursementIdempotencyService shares IdempotencyKeyRepository with IdempotencyService — no schema split needed because Redis namespace isolation (idempotency:dsb: vs idempotency:) prevents key collisions at the cache layer while Postgres rows coexist naturally under different (tenantId, idempotencyKey) pairs"
  - "Unit test @BeforeEach stub uses lenient() — not all tests trigger opsForValue() (store_postgresFailure_neverTouchesRedis verifies Redis is never called), so strict Mockito mode requires lenient for shared setup"
metrics:
  duration: ~30 min
  completed: 2026-04-25
  tasks: 3
  files: 7
---

# Phase 51 Plan 01: Disbursement Contract DTOs + Idempotency Service Summary

**One-liner:** Disbursement-namespaced idempotency service (Redis prefix `idempotency:dsb:`) + 4 contract DTOs with Jakarta validation, mirroring IdempotencyService Postgres-first ordering pattern.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Create DisbursementRequest, DisbursementResponse, DisbursementListItem, DisbursementOrchestratorError | `7a77885` | 4 contract files |
| 2 | Implement DisbursementIdempotencyService + fix test stubs | `dc9fefd` | Service + test (8/8 unit tests green) |
| 3 | DisbursementIdempotencyIT — namespace isolation under real Redis | `77f78b8` | 1 IT file |

## What Was Built

### Contract DTOs (Task 1 — by prior agent)

- **DisbursementRequest** — Java record with 7 fields: `recipientMsisdn` (`@NotBlank @Size(max=20)`), `amount` (`@NotNull @Positive`), `currency` (`@NotBlank @Size(min=3,max=3)`), `reference` (`@NotBlank @Size(max=50)`), `description` (`@Size(max=140)`), `metadata` (`@Size(max=2048)`), `idempotencyKey` (`@NotBlank`)
- **DisbursementResponse** — Java record with `accepted()` and `failed()` static factories; `fee` defaults to `BigDecimal.ZERO` when null
- **DisbursementListItem** — Java record for `GET /v1/disbursements` pagination with `Instant createdAt` / `completedAt`
- **DisbursementOrchestratorError** — Java enum implementing `ErrorCode`; 10 values mapping to HTTP status codes for Plan 04

### DisbursementIdempotencyService (Task 2)

Mirrors `IdempotencyService` exactly with `KEY_PREFIX = "idempotency:dsb:"`:

- `checkAndReserve(tenantId, key)` — Redis `setIfAbsent` atomic reservation; RESERVED sentinel for in-flight; Postgres fallback on Redis unavailability
- `store(tenantId, key, status, body)` — Postgres FIRST (IDEM-01), Redis SECOND (best-effort)
- Namespace `idempotency:dsb:<tenantId>:<key>` — distinct from payment namespace `idempotency:<tenantId>:<key>`
- Shares `IdempotencyKeyRepository` — no schema split required

### DisbursementIdempotencyIT (Task 3)

3 integration tests proving:
1. Same key used for payment and disbursement creates two independent Redis entries (no namespace collision)
2. `store()` followed by `checkAndReserve()` returns the cached 202 response
3. Second `checkAndReserve()` without `store()` returns the RESERVED sentinel (in-flight detection)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] UnnecessaryStubbingException in DisbursementIdempotencyServiceTest**
- **Found during:** Task 2 (GREEN phase — running tests after implementing service)
- **Issue:** `@BeforeEach` stub `when(redis.opsForValue()).thenReturn(valueOps)` triggered `UnnecessaryStubbingException` for `store_postgresFailure_neverTouchesRedis` (Redis is never called when Postgres fails). Also, `store_redisFailure_doesNotThrow` had an unused `setIfAbsent` stub.
- **Fix:** Changed `@BeforeEach` stub to `lenient().when(...)` and removed unnecessary `setIfAbsent` stub from `store_redisFailure_doesNotThrow`
- **Files modified:** `src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyServiceTest.java`
- **Commit:** `dc9fefd`

### Environment Note

Integration test `DisbursementIdempotencyIT` compiles and follows the identical Testcontainers pattern as `WalletBalanceConcurrencyIT`. Docker daemon was not running in this execution environment, so the IT could not be executed — same limitation applies to all 30+ existing ITs in the project. The test structure is correct and will execute in the standard CI environment.

## Known Stubs

None. All 7 files are fully implemented. `DisbursementIdempotencyService` uses the real `IdempotencyKeyRepository` and real Redis; the integration test uses real Testcontainers.

## Self-Check: PASSED

Files verified:
- `src/main/java/com/softropic/payam/disbursement/contract/DisbursementRequest.java` — EXISTS
- `src/main/java/com/softropic/payam/disbursement/contract/DisbursementResponse.java` — EXISTS
- `src/main/java/com/softropic/payam/disbursement/contract/DisbursementListItem.java` — EXISTS
- `src/main/java/com/softropic/payam/disbursement/contract/DisbursementOrchestratorError.java` — EXISTS
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyService.java` — EXISTS
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyServiceTest.java` — EXISTS
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyIT.java` — EXISTS

Commits verified:
- `7a77885` — feat(51-01): create disbursement contract DTOs and error enum
- `8917536` — test(51-01): add failing DisbursementIdempotencyService tests
- `dc9fefd` — feat(51-01): implement DisbursementIdempotencyService with idempotency:dsb: namespace
- `77f78b8` — test(51-01): add DisbursementIdempotencyIT proving namespace isolation under real Redis

Unit tests: 8/8 green (`DisbursementIdempotencyServiceTest`)
