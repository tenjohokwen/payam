---
phase: 64
slug: provider-infrastructure-encapsulation
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-11
---

# Phase 64 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Maven Surefire/Failsafe |
| **Config file** | `pom.xml` |
| **Quick run command** | `mvn test -pl . -Dtest="*Test" -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest="*Test" -q`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 64-01-01 | 01 | 1 | PROV-01 | compile | `mvn compile -q` | ✅ | ⬜ pending |
| 64-01-02 | 01 | 1 | PROV-01 | compile | `mvn compile -q` | ✅ | ⬜ pending |
| 64-02-01 | 02 | 1 | PROV-02 | compile | `mvn compile -q` | ✅ | ⬜ pending |
| 64-02-02 | 02 | 1 | PROV-02 | compile | `mvn compile -q` | ✅ | ⬜ pending |
| 64-03-01 | 03 | 2 | PROV-01, PROV-02 | integration | `mvn verify` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Existing infrastructure covers all phase requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| No flat `mtn.*` or `orange.*` imports remain | PROV-01, PROV-02 | Grep verification | `grep -rn "^import mtn\.\|^import orange\." src/` returns 0 results |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
