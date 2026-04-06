---
phase: 29
slug: quartz-rotation-cleanup-job
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-06
---

# Phase 29 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + AssertJ |
| **Config file** | `src/test/resources/application.properties` |
| **Quick run command** | `./mvnw test -pl . -Dtest=RotatedKeyCleanupJobIT -Dsurefire.failIfNoSpecifiedTests=false` |
| **Full suite command** | `./mvnw verify` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw test -Dtest=RotatedKeyCleanupJobIT -Dsurefire.failIfNoSpecifiedTests=false`
- **After every plan wave:** Run `./mvnw test -Dtest=RotatedKeyCleanupJobIT`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 29-01-01 | 01 | 0 | AKEY-05 | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT` | ❌ Wave 0 | ⬜ pending |
| 29-01-02 | 01 | 1 | AKEY-05 | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT#revokeExpiredRotatedKeys_revokesOverdueKey` | ❌ Wave 0 | ⬜ pending |
| 29-01-03 | 01 | 1 | AKEY-05 | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT#revokeExpiredRotatedKeys_leavesUnderGraceKeyUntouched` | ❌ Wave 0 | ⬜ pending |
| 29-01-04 | 01 | 1 | AKEY-05 | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT#revokeExpiredRotatedKeys_isIdempotent_noOp` | ❌ Wave 0 | ⬜ pending |
| 29-01-05 | 01 | 1 | AKEY-05/AUDIT-02 | integration | `./mvnw test -Dtest=RotatedKeyCleanupJobIT#revokeExpiredRotatedKeys_createsEnversAuditRow` | ❌ Wave 0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/softropic/payam/tenant/RotatedKeyCleanupJobIT.java` — stubs for all AKEY-05 test cases (revokes over-24h key, leaves under-24h key, no-op idempotency, Envers audit row)

*Existing `TestConfig`, `TransactionTemplate`, `JdbcTemplate`, and Testcontainers PostgreSQL infrastructure are already available — no new test fixtures needed.*

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
