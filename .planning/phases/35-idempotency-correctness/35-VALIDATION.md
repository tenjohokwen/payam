---
phase: 35
slug: idempotency-correctness
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 35 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test 3.5.11 |
| **Config file** | none (annotation-driven) |
| **Quick run command** | `mvn verify -Dtest=IdempotencyServiceIT` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn verify -Dtest=IdempotencyServiceIT`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 35-01-01 | 01 | 0 | IDEM-01, IDEM-02 | IT stub | `mvn verify -Dtest=IdempotencyServiceIT` | ❌ W0 | ⬜ pending |
| 35-01-02 | 01 | 1 | IDEM-02 | unit/IT | `mvn verify -Dtest=IdempotencyServiceIT` | ✅ | ⬜ pending |
| 35-01-03 | 01 | 1 | IDEM-01 | IT | `mvn verify -Dtest=IdempotencyServiceIT` | ✅ | ⬜ pending |
| 35-01-04 | 01 | 2 | IDEM-01, IDEM-02 | E2E regression | `mvn verify` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/.../transaction/IdempotencyServiceIT.java` — add two new test method stubs: `storeDoesNotWriteRedisWhenPostgresFails()` (IDEM-01) and `concurrentStoreCalls_ProduceExactlyOneDbRow()` (IDEM-02). Class exists; additions only.

*Existing infrastructure (JUnit 5, Spring Boot Test, Testcontainers) covers all phase requirements.*

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
