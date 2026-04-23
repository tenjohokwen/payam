---
phase: 46
slug: flyway-v25-schema-migration
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-21
---

# Phase 46 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test |
| **Config file** | `pom.xml` |
| **Quick run command** | `mvn test -pl src/test -Dtest=LedgerConstraintIT` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl src/test -Dtest=LedgerConstraintIT`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 46-01-01 | 01 | 1 | SCHEMA-01 | integration | `mvn verify` | ✅ | ⬜ pending |
| 46-01-02 | 01 | 1 | SCHEMA-02 | integration | `mvn verify` | ✅ | ⬜ pending |
| 46-01-03 | 01 | 1 | SCHEMA-03 | integration | `mvn verify` | ✅ | ⬜ pending |
| 46-01-04 | 01 | 1 | SCHEMA-04 | integration | `mvn verify` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `LedgerConstraintIT.java` — update existing test to assert trigger error message; add 3-entry group test, zero-amount test, flow column nullable test

*Existing infrastructure covers migration execution.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| V25 migration runs on existing production DB snapshot | SCHEMA-01 | Requires production data dump | Run `mvn flyway:migrate` against sanitized prod snapshot |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
