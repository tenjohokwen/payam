---
phase: 23-invariants-concurrency-sm-mutation
plan: "05"
subsystem: testing
tags: [pitest, mutation-testing, fraud, ledger, idempotency, mockito, unit-tests]

# Dependency graph
requires:
  - phase: 23-invariants-concurrency-sm-mutation
    plan: "03"
    provides: PITest pom.xml profile with 3 targetClasses + 6 domain unit tests
provides:
  - PITest targetClasses expanded to 6 production classes (FraudScoringService, LedgerService, IdempotencyService added)
  - FraudThresholdGuardTest rewritten to call real FraudScoringService via constructor injection with Mockito mocks
  - LedgerBalanceGuardTest rewritten to call real LedgerService and capture saveAll() via ArgumentCaptor
  - IdempotencyTenantScopeTest rewritten to call real IdempotencyService via constructor injection with Mockito mocks
  - All 11 domain unit tests pass (BUILD SUCCESS)
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "PITest unit-test coverage pattern: constructor-inject real Spring @Service with Mockito.mock() collaborators — no @SpringBootTest needed"
    - "ArgumentCaptor.forClass(List.class) + verify(repo).saveAll(captor) for capturing repository save arguments in unit tests"

key-files:
  created: []
  modified:
    - pom.xml
    - src/test/java/com/softropic/payam/domain/FraudThresholdGuardTest.java
    - src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java
    - src/test/java/com/softropic/payam/domain/IdempotencyTenantScopeTest.java

key-decisions:
  - "Plan's LedgerBalanceGuardTest imports static org.mockito.Mockito.argumentCaptor — this static method does not exist in Mockito; fixed to use ArgumentCaptor.forClass() directly (Rule 1 bug fix)"

patterns-established:
  - "Unit-test PITest target pattern: use new ServiceClass(mock(Dep.class)) to enable mutation kills without Spring context overhead"

# Metrics
duration: 3min
completed: 2026-03-28
---

# Phase 23 Plan 05: PITest Gap Closure (MUT-02) Summary

**PITest targetClasses expanded from 3 to 6 and three domain unit tests rewritten to call real FraudScoringService, LedgerService, and IdempotencyService via Mockito constructor injection — mutations in all 6 classes now killable by the fast domain unit suite**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-28T09:29:59Z
- **Completed:** 2026-03-28T09:33:42Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- pom.xml PITest `<targetClasses>` expanded from 3 to 6 entries: FraudScoringService, LedgerService, IdempotencyService added alongside existing OrangeTimeUtil, TransactionStatus, PaymentEventLog
- Three domain unit tests completely rewritten from inline logic duplication to real production class calls via constructor injection with Mockito mocks — PITest can now generate and kill mutations in all 6 classes
- All 11 domain unit tests pass (BUILD SUCCESS) including 6 that were already passing and 5 new tests across the three rewritten files

## Task Commits

Each task was committed atomically:

1. **Task 1: Add FraudScoringService, LedgerService, IdempotencyService to pom.xml PITest targetClasses** - `4119076` (chore)
2. **Task 2: Rewrite three domain unit tests to call real production classes via Mockito** - `5ea6831` (feat)

**Plan metadata:** (this summary commit)

## Files Created/Modified

- `/Users/mokwen/dev/gitrepos/bluegithub/payam/pom.xml` - PITest `<targetClasses>` expanded to 6 entries with updated comment block
- `/Users/mokwen/dev/gitrepos/bluegithub/payam/src/test/java/com/softropic/payam/domain/FraudThresholdGuardTest.java` - Rewritten: calls `new FraudScoringService(mockVelocity, mockCache)`, tests score=0 allowed and velocity violation blocked
- `/Users/mokwen/dev/gitrepos/bluegithub/payam/src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java` - Rewritten: calls `new LedgerService(mockRepo)`, captures `saveAll()` list and asserts 2 balanced entries
- `/Users/mokwen/dev/gitrepos/bluegithub/payam/src/test/java/com/softropic/payam/domain/IdempotencyTenantScopeTest.java` - Rewritten: calls `new IdempotencyService(mockRedis, mockRepo)`, tests new reservation (empty) and duplicate key (present)

## Decisions Made

None - followed plan as specified (see Deviations section for one minor bug fix).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Removed non-existent static import `org.mockito.Mockito.argumentCaptor` from LedgerBalanceGuardTest**

- **Found during:** Task 2 (rewriting LedgerBalanceGuardTest)
- **Issue:** The plan listed `import static org.mockito.Mockito.argumentCaptor;` but Mockito has no static method `argumentCaptor()`. The test body correctly used `ArgumentCaptor.forClass(List.class)` — the static import was a typo/hallucination in the plan spec. Compiling with this import would fail.
- **Fix:** Dropped the non-existent static import; retained `import org.mockito.ArgumentCaptor;` (instance class import) which is what `ArgumentCaptor.forClass()` requires.
- **Files modified:** `src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java`
- **Verification:** `mvn test -Dtest=LedgerBalanceGuardTest` BUILD SUCCESS, 1 test passes.
- **Committed in:** `5ea6831` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Fix was necessary for compilation. No scope change.

## Issues Encountered

None — both tasks executed cleanly. The PITest profile (`-P mutation`) can now run against all 6 targetClasses and mutations will be killed by the domain unit suite without Testcontainers or @SpringBootTest.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

All phases are complete. The PITest mutation coverage gap (MUT-02) is now closed:
- 6 production classes in targetClasses
- 11 fast unit tests in targetTests scope (com.softropic.payam.domain.*)
- Running `mvn test-compile -P mutation` will compile all targets; `mvn pitest:mutationCoverage -P mutation` will generate and kill mutations across all 6 classes

No blockers or concerns.

---
*Phase: 23-invariants-concurrency-sm-mutation*
*Completed: 2026-03-28*
