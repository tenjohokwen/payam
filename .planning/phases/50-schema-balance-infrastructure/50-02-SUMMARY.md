---
phase: 50-schema-balance-infrastructure
plan: 02
subsystem: payments
tags: [jpa, hibernate, envers, pessimistic-lock, testcontainers, concurrency]

# Dependency graph
requires:
  - phase: 50-01
    provides: "V28 Flyway migration (main.disbursement + main.merchant_wallet_balance), DisbursementStatus enum"
provides:
  - "Disbursement JPA entity (disbursement_status column, applyTransition, @Audited)"
  - "DisbursementRepository with findByDisbursementId + findByTenantIdAndIdempotencyKey"
  - "MerchantWalletBalance JPA entity (balance, reservedAmount, @Version)"
  - "MerchantWalletBalanceRepository with findByTenantIdForUpdate @Lock(PESSIMISTIC_WRITE)"
  - "InsufficientBalanceException for balance check failures (HTTP 422 in Phase 51)"
  - "WalletBalanceService.checkAndReserve() + release() — @Transactional atomic balance gate"
  - "WalletBalanceConcurrencyIT — 20-thread BAL-01 proof with real PostgreSQL Testcontainer"
  - "WalletBalanceServiceTest — 7 unit tests including BAL-02 round-trip conservation"
  - "TestDataCleaner extended with 4 DELETE statements for disbursement tables"
affects: [phase-51-orchestrator, any phase using WalletBalanceService, any test using TestDataCleaner]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "PESSIMISTIC_WRITE lock via @Lock(LockModeType.PESSIMISTIC_WRITE) on JPA repository query — mirrors TransactionRepository pattern"
    - "checkAndReserve guard-before-mutate: throw InsufficientBalanceException BEFORE calling setBalance/setReservedAmount — wallet state never mutated on failure path"
    - "BAL-03 release boundary: release() forbidden on EXPIRED (only on FAILED); documented in Javadoc"
    - "CyclicBarrier(20) + fixed thread pool + Future unwrap pattern for concurrency IT — mirrors ApiKeyConcurrentRotationIT"
    - "@Builder.Default on BigDecimal fields with BigDecimal.ZERO default in MerchantWalletBalance"

key-files:
  created:
    - src/main/java/com/softropic/payam/disbursement/contract/exception/InsufficientBalanceException.java
    - src/main/java/com/softropic/payam/disbursement/repo/Disbursement.java
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java
    - src/main/java/com/softropic/payam/disbursement/repo/MerchantWalletBalance.java
    - src/main/java/com/softropic/payam/disbursement/repo/MerchantWalletBalanceRepository.java
    - src/main/java/com/softropic/payam/disbursement/service/WalletBalanceService.java
    - src/test/java/com/softropic/payam/disbursement/service/WalletBalanceServiceTest.java
    - src/test/java/com/softropic/payam/disbursement/service/WalletBalanceConcurrencyIT.java
  modified:
    - src/test/java/com/softropic/payam/config/TestDataCleaner.java

key-decisions:
  - "PESSIMISTIC_WRITE lock chosen over optimistic (@Version only): optimistic retry allows a second thread to drain the wallet after the first succeeds — defeats the BAL-01 invariant"
  - "release() throws IllegalStateException (not InsufficientBalanceException) on missing wallet: a missing wallet on release is a programmer bug (wallet must have existed for checkAndReserve to succeed); InsufficientBalanceException on missing wallet in checkAndReserve is correct (tenant genuinely cannot disburse)"
  - "@Version retained on MerchantWalletBalance as a safety net despite PESSIMISTIC_WRITE being primary strategy — adds defense-in-depth at negligible cost"
  - "release() semantics: called ONLY on FAILED terminal state (BAL-02). NEVER called on EXPIRED (BAL-03): EXPIRED means provider may have accepted the payout; releasing reserved balance would allow overdraft. Manual ops must resolve EXPIRED disbursements."

patterns-established:
  - "Pattern: Balance gate service — @Transactional guard-before-mutate with PESSIMISTIC_WRITE lock; throw BEFORE state mutation on failure"
  - "Pattern: CyclicBarrier concurrency IT — 20 threads simultaneously attempt operation with exactly-1 success assertion; Future.get() unwraps ExecutionException for typed counting"

requirements-completed: [BAL-01, BAL-02]

# Metrics
duration: 67min
completed: 2026-04-25
---

# Phase 50 Plan 02: Balance Gate Service Summary

**JPA layer + WalletBalanceService with PESSIMISTIC_WRITE lock proven by 20-thread CyclicBarrier concurrency IT against real PostgreSQL Testcontainer — exactly 1 reserve succeeds, 19 throw InsufficientBalanceException, no overdraft**

## Performance

- **Duration:** 67 min
- **Started:** 2026-04-25T04:06:25Z
- **Completed:** 2026-04-25T07:13:00Z
- **Tasks:** 3
- **Files modified:** 9

## Accomplishments
- 5 production files: Disbursement + DisbursementRepository + MerchantWalletBalance + MerchantWalletBalanceRepository (with `@Lock(PESSIMISTIC_WRITE)`) + InsufficientBalanceException — all compile with `mvn -q compile`
- WalletBalanceService with @Transactional on both checkAndReserve (guard-before-mutate pattern) and release (Javadoc forbids call on EXPIRED per BAL-03)
- WalletBalanceConcurrencyIT (3 tests) passed against real PostgreSQL Testcontainer with Flyway V28 — exactly 1 of 20 concurrent threads succeeded, 19 threw InsufficientBalanceException, final balance = 0 (no overdraft)
- TestDataCleaner extended with 4 DELETE statements in FK-safe order (_aud before base tables)
- Full `mvn verify` suite passed (BUILD SUCCESS, 23 min including all ITs)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create JPA entities, repositories, and InsufficientBalanceException** - `997cbcb` (feat)
2. **Task 2: WalletBalanceService tests (RED)** - `707d46d` (test)
3. **Task 2: WalletBalanceService implementation (GREEN)** - `9ecc833` (feat)
4. **Task 3: WalletBalanceConcurrencyIT + TestDataCleaner** - `d574e32` (test)

_Note: Task 2 used TDD — separate RED and GREEN commits as required._

## Files Created/Modified
- `src/main/java/com/softropic/payam/disbursement/contract/exception/InsufficientBalanceException.java` — RuntimeException for BAL-01 check failures; maps to HTTP 422 in Phase 51
- `src/main/java/com/softropic/payam/disbursement/repo/Disbursement.java` — @Audited entity with `disbursement_status` column (avoids AbstractAuditingEntity.status collision), applyTransition guard
- `src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java` — findByDisbursementId + findByTenantIdAndIdempotencyKey (Phase 51 idempotency path)
- `src/main/java/com/softropic/payam/disbursement/repo/MerchantWalletBalance.java` — balance + reservedAmount fields, @Version safety net
- `src/main/java/com/softropic/payam/disbursement/repo/MerchantWalletBalanceRepository.java` — findByTenantId (non-locking reads) + findByTenantIdForUpdate @Lock(PESSIMISTIC_WRITE)
- `src/main/java/com/softropic/payam/disbursement/service/WalletBalanceService.java` — @Service with @Transactional checkAndReserve + release; Javadoc explicitly forbids release on EXPIRED (BAL-03)
- `src/test/java/com/softropic/payam/disbursement/service/WalletBalanceServiceTest.java` — 7 unit tests (mocked repo); includes BAL-02 round-trip conservation
- `src/test/java/com/softropic/payam/disbursement/service/WalletBalanceConcurrencyIT.java` — 3 IT tests; 20-thread BAL-01 proof, BAL-02 round-trip with real DB, insufficient balance no-mutation
- `src/test/java/com/softropic/payam/config/TestDataCleaner.java` — Added 4 DELETEs for disbursement_aud, disbursement, merchant_wallet_balance_aud, merchant_wallet_balance in FK-safe order

## Decisions Made
- **PESSIMISTIC_WRITE over optimistic-only**: optimistic retry (just `@Version`) would allow a second thread to drain after the first succeeds — the pessimistic lock ensures the loser blocks until the winner commits, at which point the loser sees balance=0 and throws InsufficientBalanceException.
- **release() throws IllegalStateException on missing wallet (not InsufficientBalanceException)**: a missing wallet during release is a programmer error — the wallet must have existed for checkAndReserve to have been called. Using IllegalStateException communicates this is a programming contract violation.
- **guard-before-mutate in checkAndReserve**: the balance check (`wallet.getBalance().compareTo(amount) < 0`) happens before any `setBalance`/`setReservedAmount` call, so the JPA entity is never dirty on the failure path.

## Deviations from Plan
None - plan executed exactly as written. Docker daemon needed to be started to run Testcontainers; this is an environment prerequisite, not a code deviation.

## Issues Encountered
- Docker daemon was not running when first running the integration test. Started Docker Desktop and re-ran — test passed cleanly.

## User Setup Required
None - no external service configuration required.

## Notes for Phase 51 Executor

- **`WalletBalanceService.release()` is called ONLY on FAILED terminal transitions.** For EXPIRED (BAL-03), do NOT call release — the reserved balance is held for manual ops resolution.
- **`DisbursementRepository.findByTenantIdAndIdempotencyKey()` is the DB-backed idempotency lookup** for Phase 51's SEC-01 path (supplements Redis).
- **Wallet seeding**: v10 ships without an admin top-up endpoint (deferred to v11 per REQUIREMENTS.md). Seed wallets via direct DB INSERT or a test/admin SQL script until v11.
- **Fully qualified class names** for Phase 51 reference:
  - `com.softropic.payam.disbursement.service.WalletBalanceService`
  - `com.softropic.payam.disbursement.repo.Disbursement`
  - `com.softropic.payam.disbursement.repo.DisbursementRepository`
  - `com.softropic.payam.disbursement.repo.MerchantWalletBalance`
  - `com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository`
  - `com.softropic.payam.disbursement.contract.exception.InsufficientBalanceException`
- **`reserved_amount` semantics**: decremented only on SUCCESSFUL reserve (not on failure path); incremented only on SUCCESSFUL reserve; release does the reverse pair (balance += amount, reservedAmount -= amount).
- **WalletBalanceConcurrencyIT observed**: exactly 1 success, 19 InsufficientBalanceException under 20-thread race with balance = 1 × PER_REQUEST_AMOUNT. Final balance = 0, no overdraft.

---
*Phase: 50-schema-balance-infrastructure*
*Completed: 2026-04-25*

## Self-Check: PASSED

All created files verified present. All task commits verified in git log.

| Check | Result |
|-------|--------|
| InsufficientBalanceException.java | FOUND |
| Disbursement.java | FOUND |
| DisbursementRepository.java | FOUND |
| MerchantWalletBalance.java | FOUND |
| MerchantWalletBalanceRepository.java | FOUND |
| WalletBalanceService.java | FOUND |
| WalletBalanceServiceTest.java | FOUND |
| WalletBalanceConcurrencyIT.java | FOUND |
| Commit 997cbcb (Task 1 entities) | FOUND |
| Commit 707d46d (Task 2 RED) | FOUND |
| Commit 9ecc833 (Task 2 GREEN) | FOUND |
| Commit d574e32 (Task 3 IT + TestDataCleaner) | FOUND |
| mvn verify | BUILD SUCCESS (exit 0) |
