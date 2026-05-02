---
phase: 55
slug: transaction-validation-fee-removal
status: approved
nyquist_compliant: true
wave_0_complete: true
created: 2026-05-02
updated: 2026-05-02
---

# Phase 55 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test / Mockito / AssertJ / Testcontainers |
| **Config file** | `pom.xml` / `src/test/resources/application-test.properties` |
| **Quick run command** | `mvn test -pl . -Dtest="DisbursementOrchestratorTest,TransactionClaimValidationServiceTest,Fee02RegressionTest" -q` |
| **Full unit suite command** | `mvn test -pl . -q` |
| **Full integration suite command** | `mvn verify -pl . -q` |
| **Estimated unit runtime** | ~60 seconds |
| **Estimated integration runtime** | ~3 minutes (Testcontainers PostgreSQL) |

---

## Sampling Rate

- **After every task commit:** Run the quick command above (unit tests for the affected service).
- **After every plan wave:** Run `mvn test -pl . -q`.
- **Before `/gsd:verify-work`:** Full `mvn verify -pl . -q` must be green (unit + IT).
- **Max feedback latency:** 120 seconds (unit). IT only at wave/plan boundaries.

---

## Per-Task Verification Map

Each row reflects an actual test class created by the inline-TDD steps in Plan 02 / Plan 03 (no separate Wave 0 stubs are required — TDD authoring inside each task creates the failing test before implementation, then turns it green).

| Task ID | Plan | Wave | Requirement | Test Type | Test Class | Automated Command |
|---------|------|------|-------------|-----------|-----------|-------------------|
| 55-01-01 | 01 | 1 | (V31 anti-regression) | unit (regression) | `DisbursementOrchestratorTest` | `mvn test -Dtest="DisbursementOrchestratorTest" -q` |
| 55-01-02 | 01 | 1 | TXN-01..04 (contracts) | unit (compile/regression) | `DisbursementOrchestratorTest` | `mvn test -Dtest="DisbursementOrchestratorTest" -q` |
| 55-01-03 | 01 | 1 | TXN-01..04 (contracts) | unit (compile/regression) | `DisbursementOrchestratorTest`, `DisbursementResourceIT` | `mvn test -Dtest="DisbursementOrchestratorTest,DisbursementResourceIT" -q` |
| 55-02-01 | 02 | 2 | TXN-01, TXN-02, TXN-03, TXN-04, TXN-06 | unit (TDD) | `TransactionClaimValidationServiceTest` | `mvn test -Dtest="TransactionClaimValidationServiceTest" -q` |
| 55-02-02 | 02 | 2 | TXN-01..04, TXN-06, FEE-01 | unit (TDD additions to existing class) | `DisbursementOrchestratorTest` (≥6 new @Test methods) | `mvn test -Dtest="DisbursementOrchestratorTest" -q` |
| 55-03-01 | 03 | 3 | TXN-05 | integration (concurrency) | `DisbursementClaimConcurrencyIT` | `mvn verify -pl . -Dit.test=DisbursementClaimConcurrencyIT -DfailIfNoTests=false -q` |
| 55-03-02 | 03 | 3 | FEE-02; TXN-01..04 end-to-end | unit (static audit) + integration | `Fee02RegressionTest`, `DisbursementOrchestratorIT` | `mvn test -Dtest="Fee02RegressionTest" -q && mvn verify -pl . -Dit.test=DisbursementOrchestratorIT -DfailIfNoTests=false -q` |

*Status legend (set during execution): ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

### Why no separate Wave 0 in this phase

Plans 02 and 03 use **inline TDD** (`tdd="true"` on the relevant tasks) — each task's Step 1 writes the failing test, Step 2 implements to green. The test class names listed above are the canonical ones produced by those steps. Wave 0 would only re-do that work in a separate plan.

Plan 01 is purely contract scaffolding (entity fields, error codes, exceptions, repository methods, request DTO extension) — it changes no behavior, so the existing `DisbursementOrchestratorTest` (regression-only) is the correct verifier.

---

## Wave 0 Requirements

**No separate Wave 0 plan is required.** Inline TDD inside Plans 02 and 03 produces all needed failing-then-green tests. The list below is informational only — these are the test classes that the inline TDD steps WILL create:

- [x] `src/test/java/com/softropic/payam/disbursement/service/TransactionClaimValidationServiceTest.java` — Plan 02 Task 1 (TXN-01..04, TXN-06)
- [x] `src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java` (extensions) — Plan 02 Task 2 (TXN-01..04 wiring, FEE-01 regression)
- [x] `src/test/java/com/softropic/payam/disbursement/service/DisbursementClaimConcurrencyIT.java` — Plan 03 Task 1 (TXN-05)
- [x] `src/test/java/com/softropic/payam/disbursement/service/Fee02RegressionTest.java` — Plan 03 Task 2 (FEE-02 static audit)
- [x] `src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorIT.java` (updates) — Plan 03 Task 2 (real seeded transactions replace dummy placeholders)

Existing infrastructure (`@SpringBootTest`, `IntegrationTest`, Testcontainers PostgreSQL, `TestDataCleaner`, WireMock) covers all framework needs.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| (none) | — | All Phase 55 requirements have automated coverage | — |

TXN-05 (deadlock-free under concurrent overlap) is **automated** by `DisbursementClaimConcurrencyIT` (Plan 03 Task 1) — three @Test methods cover overlap-race-loser, no-overlap-both-win, and reverse-order-no-deadlock under Testcontainers PostgreSQL with `SELECT FOR UPDATE` locks.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or are covered by inline TDD
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Test class names in this map match what Plans 02/03 actually create
- [x] No watch-mode flags
- [x] Feedback latency < 120s for unit; integration deferred to wave boundaries
- [x] `nyquist_compliant: true` set in frontmatter
- [x] `wave_0_complete: true` (inline TDD subsumes a separate Wave 0)

**Approval:** approved (revised after checker feedback)
