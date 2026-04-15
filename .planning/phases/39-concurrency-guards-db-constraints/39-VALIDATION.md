---
phase: 39
slug: concurrency-guards-db-constraints
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-15
---

# Phase 39 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 via `spring-boot-starter-test` (Spring Boot 3.5.11) |
| **Config file** | `pom.xml` — Surefire (unit) + Failsafe (IT via `*IT.java` naming) |
| **Quick run command** | `mvn test -Dtest=ApiKeyConcurrentRotationIT,LedgerConstraintIT` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~3–5 minutes (Testcontainers spin-up + IT execution) |

---

## Sampling Rate

- **After every task commit:** Run `mvn verify -Dit.test=ApiKeyConcurrentRotationIT` or `mvn verify -Dit.test=LedgerConstraintIT` (whichever is relevant to the task)
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~300 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 39-01-01 | 01 | 0 | AKEY-09 | IT stub | `mvn verify -Dit.test=ApiKeyConcurrentRotationIT` | ❌ W0 | ⬜ pending |
| 39-01-02 | 01 | 1 | AKEY-09 | migration | `mvn verify` | ❌ W0 | ⬜ pending |
| 39-01-03 | 01 | 1 | AKEY-09 | unit | `mvn test` | ✅ | ⬜ pending |
| 39-01-04 | 01 | 1 | AKEY-09 | unit | `mvn test` | ✅ | ⬜ pending |
| 39-01-05 | 01 | 2 | AKEY-09 | IT | `mvn verify -Dit.test=ApiKeyConcurrentRotationIT` | ❌ W0 | ⬜ pending |
| 39-02-01 | 02 | 0 | LEDGER-01 | IT stub | `mvn verify -Dit.test=LedgerConstraintIT` | ❌ W0 | ⬜ pending |
| 39-02-02 | 02 | 1 | LEDGER-01 | migration | `mvn verify` | ❌ W0 | ⬜ pending |
| 39-02-03 | 02 | 2 | LEDGER-01 | IT | `mvn verify -Dit.test=LedgerConstraintIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/softropic/payam/tenant/ApiKeyConcurrentRotationIT.java` — stub class with `@SpringBootTest`, `@Import(TestConfig.class)`, `@ActiveProfiles("dev")` and empty test methods (covers AKEY-09)
- [ ] `src/test/java/com/softropic/payam/transaction/LedgerConstraintIT.java` — stub class with same annotations and empty test methods (covers LEDGER-01)

*Both stubs must compile and be discovered by Failsafe (`mvn verify`) before wave 1 work begins.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Envers audit records written after rotation with `version` column present | AKEY-09 | Envers AUD column parity cannot be fully verified by unit test | After running `mvn verify`, inspect `tenant_api_key_aud` table in Testcontainers DB; verify `version` column exists and is populated |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
