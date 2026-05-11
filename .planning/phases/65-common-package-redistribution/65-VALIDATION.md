---
phase: 65
slug: common-package-redistribution
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-12
---

# Phase 65 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Maven (JUnit 5 + Spring Boot Test) |
| **Config file** | pom.xml |
| **Quick run command** | `mvn test-compile -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~3-5 minutes |

---

## Sampling Rate

- **After every task commit:** Run `mvn test-compile -q`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~30 seconds (compile check)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 65-01-01 | 01 | 1 | CMN-02 | compile | `mvn test-compile -q` | ✅ | ⬜ pending |
| 65-01-02 | 01 | 1 | CMN-02 | compile | `mvn test-compile -q` | ✅ | ⬜ pending |
| 65-02-01 | 02 | 1 | CMN-01 | compile | `mvn test-compile -q` | ✅ | ⬜ pending |
| 65-02-02 | 02 | 1 | CMN-01 | compile | `mvn test-compile -q` | ✅ | ⬜ pending |
| 65-03-01 | 03 | 2 | CMN-03 | compile | `mvn test-compile -q` | ✅ | ⬜ pending |
| 65-04-01 | 04 | 3 | CMN-04 | integration | `mvn verify` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Existing infrastructure covers all phase requirements — Maven + JUnit 5 already installed.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `com.softropic.payam.common` directory absent | CMN-04 | File system check | `find src -type d -name "common" \| grep payam` returns no results |
| Zero `common.*` imports remain | CMN-04 | Cross-file audit | `find src -name "*.java" \| xargs grep "com.softropic.payam.common"` returns no results |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
