---
phase: 02-transaction-core
plan: "01"
subsystem: payments
tags: [transaction, state-machine, flyway, redis, jpa, hibernate-envers, testcontainers, micrometer-tracing, mdc]

# Dependency graph
requires:
  - phase: 01-multi-tenant-foundation
    provides: AbstractAuditingEntity, BaseEntity (@Tsid IDs), Tenant entity with FK target, TestConfig testcontainers pattern
provides:
  - Transaction JPA entity extending AbstractAuditingEntity with tx_status column and applyTransition()
  - TransactionStatus enum (7 states) with guarded transitionTo() throwing IllegalStateTransitionException
  - TransactionEventType enum (9 constants) for payment_event_log entries
  - V3 Flyway migration for main.transaction and main.payment_event_log tables
  - TransactionService.initiate() persisting INITIATED transaction with MDC trace_id/transaction_id
  - TransactionRepository with findByTransactionId and findByTenantIdOrderByCreatedDateDesc
  - spring-boot-starter-data-redis + commons-pool2 + testcontainers base dependencies added
  - Redis lettuce pool config in application.yaml
affects:
  - 02-02-orange-adapter (needs Transaction.INITIATED before provider call)
  - 02-03-idempotency-service (uses Redis deps added here)
  - 02-04-mtn-adapter (needs Transaction.INITIATED before provider call)
  - All future payment orchestration phases

# Tech tracking
tech-stack:
  added:
    - spring-boot-starter-data-redis (Lettuce pool, needed for IdempotencyService in 02-03)
    - commons-pool2 (Lettuce connection pool backing)
    - testcontainers base artifact (generic container support)
  patterns:
    - State machine enum pattern: each state declares its own allowedTransitions() Set; transitionTo() throws IllegalStateTransitionException for invalid moves
    - JPA entity with no public setter for state field; transition only via applyTransition() method
    - MDC enrichment in service layer: transaction_id, trace_id, external_reference keyed into SLF4J MDC on initiation
    - Reuse common/payment/MobilePaymentProvider to avoid domain duplication

key-files:
  created:
    - src/main/resources/db/migration/V3__transaction_schema.sql
    - src/main/java/com/softropic/payam/transaction/contract/TransactionStatus.java
    - src/main/java/com/softropic/payam/transaction/contract/TransactionEventType.java
    - src/main/java/com/softropic/payam/transaction/contract/exception/IllegalStateTransitionException.java
    - src/main/java/com/softropic/payam/transaction/repo/Transaction.java
    - src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java
    - src/main/java/com/softropic/payam/transaction/service/TransactionService.java
    - src/test/java/com/softropic/payam/transaction/TransactionStateMachineIT.java
  modified:
    - pom.xml (3 new dependencies)
    - src/main/resources/application.yaml (Redis lettuce pool config)

key-decisions:
  - "MobilePaymentProvider reused from common/payment package (MTN, ORANGE, NEXTTEL) — plan specified creating a duplicate in transaction/contract; reused existing to avoid duplication (Rule 1 auto-fix)"
  - "Transaction.txStatus has no public setter — state transitions only via applyTransition() which enforces state machine guards"
  - "payment_event_log extends BaseEntity only (not AbstractAuditingEntity) — append-only log table; no status/audit columns per plan specification"
  - "TransactionService uses Micrometer Tracer.currentSpan() with transactionId fallback when no active span"
  - "TransactionStateMachineIT creates tenant via TenantService in @BeforeEach — TSID-based IDs preclude fixed numeric tenant IDs"

patterns-established:
  - "State machine guard pattern: enum with abstract allowedTransitions() Set per state + transitionTo() throws on invalid move"
  - "Entity transition encapsulation: no public setter for state; applyTransition() is the only mutation point"
  - "IT test cleanup: @AfterEach deletes main.transaction before tenant FK parents"

# Metrics
duration: 5min
completed: 2026-03-23
---

# Phase 2 Plan 1: Transaction Foundation Summary

**TransactionStatus 7-state guarded state machine + Transaction JPA entity + V3 Flyway DDL + TransactionService.initiate() with MDC enrichment, 4 ITs green**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-23T22:51:44Z
- **Completed:** 2026-03-23T22:56:50Z
- **Tasks:** 3
- **Files modified:** 10

## Accomplishments
- TransactionStatus enum with 7 states (INITIATED/AUTH_PENDING/AUTHORIZED/PROCESSING/SUCCESS/FAILED/REVERSED), each declaring its own allowed transitions, guarded by transitionTo() that throws IllegalStateTransitionException on invalid moves
- Transaction JPA entity extending AbstractAuditingEntity with tx_status column, @Audited for Envers, no public setter for txStatus — state mutated only via applyTransition()
- V3 Flyway migration creating main.transaction (with all audit columns and FK to tenant) and main.payment_event_log (append-only, no audit columns)
- TransactionService.initiate() persisting INITIATED transaction and enriching SLF4J MDC with transaction_id, trace_id, external_reference
- spring-boot-starter-data-redis, commons-pool2, testcontainers base deps added for Wave 2 use

## Task Commits

Each task was committed atomically:

1. **Task 1: Maven dependencies + Redis config + Flyway V3 migration** - `6acf709` (feat)
2. **Task 2: TransactionStatus + Transaction entity + repository + service** - `7818ac5` (feat)
3. **Task 3: TransactionStateMachineIT integration test** - `4b0018e` (test)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `src/main/resources/db/migration/V3__transaction_schema.sql` - DDL for main.transaction and main.payment_event_log
- `src/main/java/com/softropic/payam/transaction/contract/TransactionStatus.java` - 7-state enum with guarded transitionTo()
- `src/main/java/com/softropic/payam/transaction/contract/TransactionEventType.java` - 9 event type constants
- `src/main/java/com/softropic/payam/transaction/contract/exception/IllegalStateTransitionException.java` - RuntimeException for invalid transitions
- `src/main/java/com/softropic/payam/transaction/repo/Transaction.java` - JPA entity with tx_status and applyTransition()
- `src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java` - JpaRepository with findByTransactionId
- `src/main/java/com/softropic/payam/transaction/service/TransactionService.java` - initiate() with MDC enrichment
- `src/test/java/com/softropic/payam/transaction/TransactionStateMachineIT.java` - 4 ITs covering initiation + valid/invalid transitions
- `pom.xml` - spring-boot-starter-data-redis, commons-pool2, testcontainers base deps
- `src/main/resources/application.yaml` - Redis lettuce pool config

## Decisions Made
- **MobilePaymentProvider reused from common/payment:** Plan specified creating `transaction/contract/MobilePaymentProvider.java` but the enum already exists at `common/payment/MobilePaymentProvider.java` with MTN, ORANGE, NEXTTEL. Auto-fixed by reusing existing to avoid duplication.
- **TransactionStateMachineIT creates tenant via TenantService:** The tenant FK constraint requires a real tenant row. Since BaseEntity uses TSID generation (not sequential IDs), `tenantId = 1L` would not exist. Used TenantService.createTenant() in @BeforeEach instead.
- **No public setter for txStatus:** Encapsulation design decision — state transitions must go through applyTransition() to enforce state machine guards.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Reused common/payment/MobilePaymentProvider instead of creating duplicate**
- **Found during:** Task 2 (Transaction entity implementation)
- **Issue:** Plan specified creating `transaction/contract/MobilePaymentProvider.java` with MTN and ORANGE; the enum already exists at `common/payment/MobilePaymentProvider.java` with MTN, ORANGE, and NEXTTEL. Creating a second enum would cause ambiguity and import confusion.
- **Fix:** Transaction entity and TransactionService import `com.softropic.payam.common.payment.MobilePaymentProvider` directly. No duplicate created.
- **Files modified:** Transaction.java, TransactionService.java (imports to common package)
- **Verification:** mvn compiler:compile -q succeeds; IT tests pass using MobilePaymentProvider.MTN and ORANGE
- **Committed in:** 7818ac5 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 Rule 1 - duplicate avoidance)
**Impact on plan:** No scope change. The contract files specified (TransactionStatus, TransactionEventType) were all created. Only the redundant MobilePaymentProvider was skipped in favor of the existing one.

## Issues Encountered
None - test approach worked first time. 4/4 ITs green with no failures.

## User Setup Required
None - no external service configuration required for this plan.

## Next Phase Readiness
- Transaction module foundation complete: state machine, entity, repository, service all ready
- Orange adapter (02-02) can now call TransactionService.initiate() before any provider call
- MTN adapter (02-04) same
- IdempotencyService (02-03) can use the Redis dependencies added here
- No blockers for Wave 2 plans

---
*Phase: 02-transaction-core*
*Completed: 2026-03-23*
