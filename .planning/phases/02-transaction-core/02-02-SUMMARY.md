---
phase: 02-transaction-core
plan: "02"
subsystem: payments
tags: [event-log, hash-chain, sha256, hibernate-immutable, jpa, testcontainers, jsonb]

# Dependency graph
requires:
  - phase: 02-transaction-core/02-01
    provides: Transaction entity + TransactionStatus/TransactionEventType enums + V3 Flyway DDL for main.payment_event_log + BaseEntity with @Tsid ID
provides:
  - PaymentEventLog JPA entity with @Immutable (no UPDATE SQL ever generated)
  - PaymentEventLogRepository with findLatestHashByTransactionId + findByTransactionIdOrderByCreatedDateAsc
  - EventLogService.append() establishing hash chain per transaction with "GENESIS" anchor
  - EventLogService.verifyChain() re-computing each hash from canonical fields and validating chain integrity
affects:
  - 02-03-idempotency-service (no direct dependency, but established pattern for append-only tables)
  - 02-04-mtn-adapter (will call EventLogService.append() on every state transition)
  - 02-05-orange-adapter (same)
  - Phase 10 (audit/reporting) — verifyChain() defined here for use in reconciliation

# Tech tracking
tech-stack:
  added:
    - org.hibernate.annotations.JdbcTypeCode(SqlTypes.JSON) — required for Hibernate to correctly bind String fields mapped to PostgreSQL jsonb columns
  patterns:
    - Hash chain pattern: each event stores previous_hash + event_hash; first event uses "GENESIS"; verifyChain() re-derives each hash from canonical pipe-delimited string
    - Canonical hash input: transactionId|eventType|statusFrom|statusTo|actor|previousHash — stable fields only; no timestamps, no DB IDs in hash
    - @Immutable entity: Hibernate silently ignores any attempted UPDATE SQL — enforces append-only at the ORM level
    - Factory method pattern: PaymentEventLog.create() computes hash before construction so it can be passed to the all-args constructor

key-files:
  created:
    - src/main/java/com/softropic/payam/transaction/repo/PaymentEventLog.java
    - src/main/java/com/softropic/payam/transaction/repo/PaymentEventLogRepository.java
    - src/main/java/com/softropic/payam/transaction/service/EventLogService.java
    - src/test/java/com/softropic/payam/transaction/PaymentEventLogIT.java
  modified:
    - src/main/java/com/softropic/payam/transaction/repo/PaymentEventLog.java (jsonb fix — @JdbcTypeCode added)

key-decisions:
  - "@JdbcTypeCode(SqlTypes.JSON) required on String metadata field — @Column(columnDefinition='jsonb') alone does not instruct Hibernate to type-cast the JDBC parameter; PostgreSQL refuses varchar→jsonb without explicit cast"
  - "PaymentEventLog extends BaseEntity only (not AbstractAuditingEntity) — confirmed by V3 DDL which has no status/audit columns; AbstractAuditingEntity would add unmapped columns causing schema validation failure"
  - "createdDate set to Instant.now() inside factory method but NOT included in hash canonical string — timestamps are non-deterministic and would break hash reproducibility"
  - "Hash canonical string is pipe-delimited: transactionId|eventType|statusFrom|statusTo|actor|previousHash — all deterministic domain fields; statusFrom renders as 'null' string when absent (genesis event)"

patterns-established:
  - "Append-only entity: @Immutable + all columns updatable=false + static factory method computing hash before construction"
  - "Hash chain anchoring: first event uses previousHash='GENESIS'; subsequent events look up latestHash via findLatestHashByTransactionId ORDER BY createdDate DESC LIMIT 1"
  - "Chain verification: traverse ASC, maintain runningHash='GENESIS', verify previousHash matches runningHash and re-derived hash matches stored eventHash"

# Metrics
duration: 6min
completed: 2026-03-23
---

# Phase 2 Plan 2: Event Log Hash Chain Summary

**SHA-256 append-only PaymentEventLog hash chain with @Immutable JPA entity, GENESIS anchor, and verifyChain() traversal — 3 ITs green, 132 full-suite tests passing**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-23T22:59:08Z
- **Completed:** 2026-03-23T23:05:03Z
- **Tasks:** 2
- **Files modified:** 4 created, 1 modified

## Accomplishments

- PaymentEventLog JPA entity with `@Immutable` (Hibernate never generates UPDATE SQL), extending BaseEntity (not AbstractAuditingEntity — payment_event_log DDL has no audit columns), with static factory method `create()` computing SHA-256 from deterministic pipe-delimited canonical string before object construction
- EventLogService.append() looking up previous hash via `findLatestHashByTransactionId` (or "GENESIS" for first event), then delegating to `PaymentEventLog.create()` for hash computation and persisting the immutable row
- EventLogService.verifyChain() traversing all events in ascending order, re-deriving each hash from canonical fields, and returning false on any mismatch — defined here for Phase 10 reconciliation use
- 3 IT tests green: genesis hash anchor, hash chain linking (event[n].previousHash == event[n-1].eventHash), and full 3-event chain verification via verifyChain()
- Full test suite: 132 tests, 0 failures, 0 regressions

## Task Commits

Each task was committed atomically:

1. **Task 1: PaymentEventLog entity + repository** - `03ced3a` (feat)
2. **Task 2: EventLogService + PaymentEventLogIT** - `f35d83e` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `src/main/java/com/softropic/payam/transaction/repo/PaymentEventLog.java` - @Immutable JPA entity with SHA-256 hash chain factory method
- `src/main/java/com/softropic/payam/transaction/repo/PaymentEventLogRepository.java` - JpaRepository with findLatestHashByTransactionId + findByTransactionIdOrderByCreatedDateAsc
- `src/main/java/com/softropic/payam/transaction/service/EventLogService.java` - append() with GENESIS anchor + verifyChain() traversal
- `src/test/java/com/softropic/payam/transaction/PaymentEventLogIT.java` - 3 ITs: genesis, chain link, full verify

## Decisions Made

- **@JdbcTypeCode(SqlTypes.JSON) on metadata field:** `@Column(columnDefinition="jsonb")` alone does not instruct Hibernate to type-cast the JDBC parameter binding. PostgreSQL rejects `character varying` → `jsonb` without an explicit cast. Adding `@JdbcTypeCode(SqlTypes.JSON)` resolves this by telling Hibernate to use the JSON JDBC type handler.
- **Extends BaseEntity, not AbstractAuditingEntity:** The V3 DDL for `payment_event_log` has no `status`, `created_by`, `last_modified_by`, `last_modified_date`, `request_id`, or `session_id` columns. Using AbstractAuditingEntity would cause Hibernate schema validation to fail. BaseEntity provides only the `@Id @Tsid` field, which matches the DDL.
- **Timestamps excluded from hash input:** `createdDate` is set inside `create()` (non-deterministic) so it is NOT included in the canonical hash string. Any replay/verification must produce the same hash from domain fields alone.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Added @JdbcTypeCode(SqlTypes.JSON) to metadata field**

- **Found during:** Task 2 (PaymentEventLogIT execution)
- **Issue:** PostgreSQL error "column 'metadata' is of type jsonb but expression is of type character varying" when Hibernate tried to INSERT with a null metadata. `@Column(columnDefinition = "jsonb")` sets the DDL type hint but does not change how Hibernate binds the JDBC parameter — it still sends as VARCHAR.
- **Fix:** Added `@JdbcTypeCode(SqlTypes.JSON)` annotation to the `metadata` field in PaymentEventLog.java. This instructs Hibernate's type system to use the JSON type handler for this column.
- **Files modified:** `src/main/java/com/softropic/payam/transaction/repo/PaymentEventLog.java`
- **Verification:** All 3 PaymentEventLogIT tests pass (including null metadata case in test 1 and test 3)
- **Committed in:** f35d83e (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 Rule 1 - Bug)
**Impact on plan:** No scope change. Fix was essential for correct JSONB handling. All planned deliverables produced.

## Issues Encountered

None beyond the jsonb type binding bug documented in Deviations.

## Next Phase Readiness

- Hash chain infrastructure complete: any adapter (Orange, MTN) can call `eventLogService.append()` on every state transition
- verifyChain() defined and tested, ready for Phase 10 reconciliation
- No blockers for 02-03 (IdempotencyService) or 02-04 (MTN adapter)
- Pattern established: all adapter plans should call `eventLogService.append()` in each state-transition handler

---
*Phase: 02-transaction-core*
*Completed: 2026-03-23*
